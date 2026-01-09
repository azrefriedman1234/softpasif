package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class BackgroundSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private fun tryProcessImageReflective(
        context: android.content.Context,
        inputPath: String,
        outputPath: String,
        rects: java.util.ArrayList<Any>,
        logoUri: android.net.Uri?,
        lx: Float,
        ly: Float,
        lw: Float
    ): Boolean {
        val candidates = listOf(
            "com.pasiflonet.mobile.utils.ImageUtils",
            "com.pasiflonet.mobile.utils.MediaProcessor"
        )
        for (cn in candidates) {
            try {
                val cls = Class.forName(cn)
                val inst = try { cls.getDeclaredField("INSTANCE").get(null) } catch (_: Throwable) { null }
                for (m in cls.methods) {
                    if (m.name != "processImage") continue
                    try {
                        val pt = m.parameterTypes
                        if (pt.size == 8 &&
                            android.content.Context::class.java.isAssignableFrom(pt[0]) &&
                            pt[1] == String::class.java &&
                            pt[2] == String::class.java
                        ) {
                            val res = m.invoke(inst, context, inputPath, outputPath, rects, logoUri, lx, ly, lw)
                            return when (res) {
                                is Boolean -> res
                                else -> java.io.File(outputPath).exists()
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
        android.util.Log.w(TAG, "No processImage() found by reflection; will send original image")
        return false
    }


    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val target = inputData.getString(KEY_TARGET).orEmpty()
            val caption = inputData.getString(KEY_CAPTION).orEmpty()
            val isVideo = inputData.getBoolean(KEY_IS_VIDEO, false)
            val fileIdLong = inputData.getLong(KEY_FILE_ID, 0L)
            val fileId = fileIdLong.toInt()
            val fallbackPath = inputData.getString(KEY_FALLBACK_PATH)

            val lx = inputData.getFloat(KEY_LX, 0f)
            val ly = inputData.getFloat(KEY_LY, 0f)
            val lw = inputData.getFloat(KEY_LW, 0.2f)

            val logoUriStr = inputData.getString(KEY_LOGO_URI)
            val logoUri: Uri? = try { logoUriStr?.let { Uri.parse(it) } } catch (_: Throwable) { null }

            val rectsJson = inputData.getString(KEY_RECTS_JSON)
            val rects = buildBlurRects(rectsJson)

            if (target.isBlank()) {
                Log.e(TAG, "No target provided")
                return@withContext Result.failure()
            }

            // Resolve input path
            var inputPath: String? = null
            if (fileId != 0) {
                inputPath = TdLibManager.getFilePath(fileId)
                if (inputPath.isNullOrBlank() || !File(inputPath).exists()) {
                    TdLibManager.downloadFile(fileId)
                    // wait a bit for TDLib download
                    repeat(12) {
                        delay(500)
                        val p = TdLibManager.getFilePath(fileId)
                        if (!p.isNullOrBlank() && File(p).exists()) {
                            inputPath = p
                            return@repeat
                        }
                    }
                }
            }
            if (inputPath.isNullOrBlank()) inputPath = fallbackPath

            if (inputPath.isNullOrBlank()) {
                Log.e(TAG, "No input path resolved (fileId=$fileId, fallback=$fallbackPath)")
                return@withContext Result.failure()
            }

            // content:// -> real file
            inputPath = ensureLocalFilePath(inputPath!!, isVideo)
            if (inputPath.isNullOrBlank() || !File(inputPath!!).exists()) {
                Log.e(TAG, "Input file does not exist after resolve: $inputPath")
                return@withContext Result.failure()
            }

            // If there are edits, try to process to a new file; otherwise send original
            val hasEdits = (rects.isNotEmpty()) || (logoUri != null)
            val outFile = File(applicationContext.cacheDir, "processed_worker_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}")
            val outPath = outFile.absolutePath

            val sendPath: String = if (hasEdits) {
                val ok = if (isVideo) {
                    // video processing: try MediaProcessor reflection (avoids signature mismatch compile-time)
                    tryProcessVideoReflective(
                        applicationContext,
                        inputPath!!,
                        outPath,
                        rects,
                        logoUri,
                        lx,
                        ly,
                        lw
                    )
                } else {
                    try {
                        tryProcessImageReflective(applicationContext, inputPath!!, outPath, rects, logoUri, lx, ly, lw)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ImageUtils.processImage failed", t)
                        false
                    }
                }

                if (ok && outFile.exists()) outPath else inputPath!!
            } else {
                inputPath!!
            }

            // Send via TDLib
            try {
                TdLibManager.sendFinalMessage(target, caption, sendPath, isVideo)
                Result.success()
            } catch (t: Throwable) {
                Log.e(TAG, "sendFinalMessage failed", t)
                Result.retry()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Worker crashed", t)
            Result.retry()
        }
    }

    // -------- helpers --------

    private fun ensureLocalFilePath(pathOrUri: String, isVideo: Boolean): String? {
        return try {
            when {
                pathOrUri.startsWith("content://") -> {
                    val uri = Uri.parse(pathOrUri)
                    val ext = if (isVideo) "mp4" else "bin"
                    val out = File(applicationContext.cacheDir, "in_${System.currentTimeMillis()}.$ext")
                    applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    } ?: return null
                    out.absolutePath
                }
                pathOrUri.startsWith("file://") -> Uri.parse(pathOrUri).path
                else -> pathOrUri
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ensureLocalFilePath failed", e)
            null
        }
    }

    /**
     * Build ArrayList<BlurRect> using reflection so it matches your existing BlurRect class.
     * Falls back to empty list if class isn't found.
     */
    private fun buildBlurRects(rectsJson: String?): java.util.ArrayList<Any> {
        val out = java.util.ArrayList<Any>()
        if (rectsJson.isNullOrBlank()) return out
        val cls = findBlurRectClass() ?: return out
        val ctor = try { cls.getConstructor(Float::class.java, Float::class.java, Float::class.java, Float::class.java) } catch (_: Throwable) { null }
        if (ctor == null) return out
        try {
            val arr = JSONArray(rectsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val l = o.optDouble("l", 0.0).toFloat()
                val t = o.optDouble("t", 0.0).toFloat()
                val r = o.optDouble("r", 0.0).toFloat()
                val b = o.optDouble("b", 0.0).toFloat()
                out.add(ctor.newInstance(l, t, r, b))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed parsing rectsJson", t)
        }
        return out
    }

    private fun findBlurRectClass(): Class<*>? {
        val candidates = listOf(
            "com.pasiflonet.mobile.BlurRect",
            "com.pasiflonet.mobile.utils.BlurRect",
            "com.pasiflonet.mobile.ui.BlurRect",
            "com.pasiflonet.mobile.model.BlurRect"
        )
        for (n in candidates) {
            try { return Class.forName(n) } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Try to call your existing video processor without binding to an exact signature.
     * If it can't find a method, returns false (we'll send original media).
     */
    private fun tryProcessVideoReflective(
        context: Context,
        inputPath: String,
        outputPath: String,
        rects: java.util.ArrayList<Any>,
        logoUri: Uri?,
        lx: Float,
        ly: Float,
        lw: Float
    ): Boolean {
        val candidates = listOf(
            "com.pasiflonet.mobile.utils.MediaProcessor",
            "com.pasiflonet.mobile.utils.VideoProcessor",
            "com.pasiflonet.mobile.utils.VideoUtils"
        )

        for (cn in candidates) {
            try {
                val cls = Class.forName(cn)
                val inst = try { cls.getDeclaredField("INSTANCE").get(null) } catch (_: Throwable) { null }

                val methods = cls.methods.filter { m ->
                    val name = m.name.lowercase()
                    name.contains("process") && name.contains("video")
                } + cls.methods.filter { m -> m.name == "processVideoSuspending" || m.name == "processVideo" }

                for (m in methods.distinct()) {
                    try {
                        val pt = m.parameterTypes
                        // We try a few common shapes:
                        // (Context, String, String, ArrayList, Uri?, Float, Float, Float)
                        if (pt.size == 8 &&
                            Context::class.java.isAssignableFrom(pt[0]) &&
                            pt[1] == String::class.java &&
                            pt[2] == String::class.java
                        ) {
                            val res = m.invoke(inst, context, inputPath, outputPath, rects, logoUri, lx, ly, lw)
                            return when (res) {
                                is Boolean -> res
                                null -> File(outputPath).exists()
                                else -> File(outputPath).exists()
                            }
                        }

                        // (Context, String, String, ArrayList, Uri?, Float, Float, Float, Boolean)
                        if (pt.size == 9 &&
                            Context::class.java.isAssignableFrom(pt[0]) &&
                            pt[1] == String::class.java &&
                            pt[2] == String::class.java
                        ) {
                            val res = m.invoke(inst, context, inputPath, outputPath, rects, logoUri, lx, ly, lw, true)
                            return when (res) {
                                is Boolean -> res
                                null -> File(outputPath).exists()
                                else -> File(outputPath).exists()
                            }
                        }
                    } catch (_: Throwable) {
                        // try next
                    }
                }
            } catch (_: Throwable) {
                // try next class
            }
        }

        Log.w(TAG, "No video processor method found by reflection; will send original video")
        return false
    }

    companion object {
        private const val TAG = "BackgroundSendWorker"

        const val KEY_TARGET = "target"
        const val KEY_CAPTION = "caption"
        const val KEY_IS_VIDEO = "isVideo"
        const val KEY_FILE_ID = "fileId"
        const val KEY_FALLBACK_PATH = "fallbackPath"
        const val KEY_RECTS_JSON = "rectsJson"
        const val KEY_LOGO_URI = "logoUri"
        const val KEY_LX = "lx"
        const val KEY_LY = "ly"
        const val KEY_LW = "lw"

        fun enqueue(
            context: Context,
            target: String,
            caption: String,
            isVideo: Boolean,
            fileId: Long,
            fallbackPath: String?,
            rectsJson: String?,
            logoUri: String?,
            lx: Float,
            ly: Float,
            lw: Float
        ) {
            val data = Data.Builder()
                .putString(KEY_TARGET, target)
                .putString(KEY_CAPTION, caption)
                .putBoolean(KEY_IS_VIDEO, isVideo)
                .putLong(KEY_FILE_ID, fileId)
                .putString(KEY_FALLBACK_PATH, fallbackPath)
                .putString(KEY_RECTS_JSON, rectsJson)
                .putString(KEY_LOGO_URI, logoUri)
                .putFloat(KEY_LX, lx)
                .putFloat(KEY_LY, ly)
                .putFloat(KEY_LW, lw)
                .build()

            val req = OneTimeWorkRequestBuilder<BackgroundSendWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(req)
        }
    }

    private fun isImagePath(p: String): Boolean {
        val lp = p.lowercase()
        return lp.endsWith(".jpg") || lp.endsWith(".jpeg") || lp.endsWith(".png") || lp.endsWith(".webp")
    }

}
