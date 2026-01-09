package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.utils.BlurRect
import com.pasiflonet.mobile.utils.DebugLog
import com.pasiflonet.mobile.utils.ImageUtils
import com.pasiflonet.mobile.utils.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class BackgroundSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object BackgroundSendWorker {
        private const val K_TARGET = "target"
        private const val K_CAPTION = "caption"
        private const val K_IS_VIDEO = "isVideo"
        private const val K_FILE_ID = "fileId"
        private const val K_FALLBACK_PATH = "fallbackPath"
        private const val K_RECTS_JSON = "rectsJson"
        private const val K_LOGO_URI = "logoUri"
        private const val K_LX = "lx"
        private const val K_LY = "ly"
        private const val K_LW = "lw"

        fun encodeRects(rects: List<BlurRect>): String {
            val arr = JSONArray()
            for (r in rects) {
                val o = JSONArray()
                o.put(r.left); o.put(r.top); o.put(r.right); o.put(r.bottom)
                arr.put(o)
            }
            return arr.toString()
        }

        fun decodeRects(json: String?): List<BlurRect> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<BlurRect>(arr.length())
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONArray(i)
                    if (a.length() >= 4) {
                        out.add(BlurRect(
                            a.optDouble(0, 0.0).toFloat(),
                            a.optDouble(1, 0.0).toFloat(),
                            a.optDouble(2, 0.0).toFloat(),
                            a.optDouble(3, 0.0).toFloat(),
                        ))
                    }
                }
                out
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun buildInput(
            target: String,
            caption: String,
            isVideo: Boolean,
            fileId: Int,
            fallbackPath: String?,
            rectsJson: String?,
            logoUri: String?,
            lx: Float,
            ly: Float,
            lw: Float
        ): Data {
            val b = Data.Builder()
            b.putString(K_TARGET, target)
            b.putString(K_CAPTION, caption)
            b.putBoolean(K_IS_VIDEO, isVideo)
            b.putInt(K_FILE_ID, fileId)
            if (!fallbackPath.isNullOrBlank()) b.putString(K_FALLBACK_PATH, fallbackPath)
            if (!rectsJson.isNullOrBlank()) b.putString(K_RECTS_JSON, rectsJson)
            if (!logoUri.isNullOrBlank()) b.putString(K_LOGO_URI, logoUri)
            b.putFloat(K_LX, lx)
            b.putFloat(K_LY, ly)
            b.putFloat(K_LW, lw)
            return b.build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val target = inputData.getString(K_TARGET).orEmpty()
            val caption = inputData.getString(K_CAPTION).orEmpty()
            val isVideo = inputData.getBoolean(K_IS_VIDEO, false)
            val fileId = inputData.getInt(K_FILE_ID, 0)
            val fallbackPath = inputData.getString(K_FALLBACK_PATH)
            val rects = decodeRects(inputData.getString(K_RECTS_JSON))
            val logoUriStr = inputData.getString(K_LOGO_URI)
            val lx = inputData.getFloat(K_LX, 0.02f)
            val ly = inputData.getFloat(K_LY, 0.02f)
            val lw = inputData.getFloat(K_LW, 0.30f)

            if (target.isBlank()) {
                DebugLog.append(applicationContext, "BG: missing target")
                return@withContext Result.failure()
            }

            // ✅ Resolve original media path from TDLib fileId or fallback
            var inputPath: String? = null
            if (fileId != 0) {
                inputPath = TdLibManager.getFilePath(fileId)
                if (inputPath.isNullOrBlank() || !File(inputPath!!).exists()) {
                    TdLibManager.downloadFile(fileId)
                    // poll a bit
                    repeat(20) {
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

            val inputPathStr: String = inputPath ?: run {
                DebugLog.append(applicationContext, "BG: missing inputPath (fileId=$fileId)")
                return@withContext Result.failure()
            }

            if (!File(inputPathStr).exists() && !inputPathStr.startsWith("content://")) {
                DebugLog.append(applicationContext, "BG: inputPath not found: $inputPathStr")
                return@withContext Result.failure()
            }

            val outPath = File(applicationContext.cacheDir,
                "processed_bg_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}"
            ).absolutePath

            // ✅ Process
            val ok = if (isVideo) {
                processVideoSuspending(
                    applicationContext,
                    inputPathStr,
                    outPath,
                    rects,
                    logoUriStr,
                    lx, ly, lw
                )
            } else {
                // ImageUtils expects Uri? logo
                val logoUri = logoUriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ImageUtils.processImage(
                    applicationContext,
                    inputPathStr,
                    outPath,
                    rects,
                    logoUri,
                    lx, ly, lw
                )
            }

            if (!ok) {
                DebugLog.append(applicationContext, "BG: processing failed")
                return@withContext Result.failure()
            }

            // ✅ Send final message
            TdLibManager.sendFinalMessage(target, caption, outPath, isVideo)
            DebugLog.append(applicationContext, "BG: sent ok -> $target | $outPath")
            return@withContext Result.success()

        } catch (t: Throwable) {
            DebugLog.append(applicationContext, "BG: crash: ${t.javaClass.simpleName}: ${t.message}")
            return@withContext Result.failure()
        }
    }

    private suspend fun processVideoSuspending(
        context: Context,
        inputPath: String,
        outputPath: String,
        rects: List<BlurRect>,
        logoUriStr: String?,
        lx: Float, ly: Float, lw: Float
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            MediaProcessor.processContent(
                context = context,
                inputPath = inputPath,
                outputPath = outputPath,
                isVideo = true,
                blurRects = rects,
                hasLogo = !logoUriStr.isNullOrBlank(),
                logoPath = logoUriStr,
                logoRelX = lx,
                logoRelY = ly,
                logoRelW = lw
            ) { ok -> cont.resume(ok) }
        } catch (_: Throwable) {
            cont.resume(false)
        }
    }
}
