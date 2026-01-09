package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.utils.DebugLog
import com.pasiflonet.mobile.utils.MediaProcessor
import com.pasiflonet.mobile.utils.ImageUtils
import com.pasiflonet.mobile.utils.BlurRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

class BackgroundSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    
    // AUTO_DOWNLOAD_REAL_VIDEO_HELPERS
    private fun isThumbnailPath(path: String): Boolean {
        val p = path.lowercase()
        return p.contains("/thumbnails/") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp")
    }

    private suspend fun waitForRealVideoPath(fileId: Int, initialPath: String?): String? {
        if (fileId == 0) return initialPath
        val deadline = System.currentTimeMillis() + 120_000L // 2 דקות
        var last: String? = initialPath
        // טריגר הורדה
        try { TdLibManager.downloadFile(fileId) } catch (_: Throwable) {}

        while (System.currentTimeMillis() < deadline) {
            val p = try { TdLibManager.getFilePath(fileId) } catch (_: Throwable) { null }
            if (!p.isNullOrBlank()) {
                last = p
                val f = java.io.File(p)
                if (f.exists() && f.length() > 0 && !isThumbnailPath(p)) return p
            }
            delay(700)
        }
        return last // אולי עדיין thumbnail/לא קיים -> נחליט למטה retry/failure
    }
    // AUTO_DOWNLOAD_REAL_VIDEO_HELPERS
override suspend fun doWork(): Result {
        // ✅ freeze inputPathStr to avoid smart-cast issues across lambdas (early)
        val fallbackPath = inputData.getString("fallbackPath")
            ?: inputData.getString("fallback_path")
            ?: inputData.getString("fallback")
        var inputPath: String? = fallbackPath
        val inputPathStr: String = (inputPath ?: fallbackPath) ?: return Result.failure()


        // ✅ fallback path from input data (may be null)
        // DUP_REMOVED val fallbackPath = inputData.getString("fallbackPath")
            ?: inputData.getString("fallback_path")
            ?: inputData.getString("fallback")

        val target = inputData.getString("TARGET") ?: return Result.failure()
        val caption = inputData.getString("CAPTION") ?: ""
        val isVideo = inputData.getBoolean("IS_VIDEO", false)
        val fileId = inputData.getInt("FILE_ID", 0)

        // AUTO_DOWNLOAD_REAL_VIDEO_FLOW
        // בניית inputPathStr נכון:
        // 1) אם יש fileId - נעדיף path מ-TDLib
        // 2) אחרת fallbackPath (יכול להיות content:// או file path)
        var inputPath: String? = null
        // ✅ PREFER REAL MEDIA VIA fileId (avoid thumbnails)
        run {
            if (fileId != 0) {
                var p = TdLibManager.getFilePath(fileId)
                if (p == null || !File(p).exists() || (isVideo && isImagePath(p))) {
                    TdLibManager.downloadFile(fileId)
                    repeat(20) {
                        delay(500)
                        p = TdLibManager.getFilePath(fileId)
                        if (p != null && File(p).exists() && !(isVideo && isImagePath(p))) return@repeat
                    }
                }
                if (p != null && File(p).exists() && !(isVideo && isImagePath(p))) {
                    inputPathStr = p
                }
            }
            if (isVideo && isImagePath(inputPathStr)) {
                DebugLog.append(applicationContext, "BG worker FAIL: thumbnail used as video input: $inputPathStr")
                throw IllegalArgumentException("thumbnail-as-video")
            }
            if (inputPathStr.isNullOrBlank()) {
                DebugLog.append(applicationContext, "BG worker FAIL: inputPathStr missing (fileId=$fileId)")
                throw IllegalArgumentException("inputPathStr-missing")
            }
        }
        // ✅ Freeze inputPathStr to avoid smart-cast issues (inputPathStr is a var used across lambdas)
        val inputPathStr = inputPath ?: run {
            DebugLog.append(applicationContext, "BG worker FAIL: inputPathStr missing (fileId=$fileId)")
            return Result.failure()
        }
        if (isVideo && isImagePath(inputPathStr)) {
            DebugLog.append(applicationContext, "BG worker FAIL: thumbnail used as video input: $inputPathStr")
            return Result.failure()
        }



        try {
            if (fileId != 0) inputPathStr = TdLibManager.getFilePath(fileId)
        } catch (_: Throwable) {}
        if (inputPathStr.isNullOrBlank()) inputPathStr = fallbackPath

        // אם אין מדיה בכלל -> טקסט בלבד
        if ((inputPathStr == null || inputPathStr.isBlank()) && fileId == 0) {
            DebugLog.append(applicationContext, "BG worker: text-only -> sendFinalMessage")
            TdLibManager.sendFinalMessage(target, caption, null, false)
            return Result.success()
        }

        // אם זה וידאו אבל יש thumbnail/אין קובץ עדיין -> להוריד אוטומטית את הוידאו האמיתי
        if (isVideo) {
            val before = inputPathStr
            inputPathStr = waitForRealVideoPath(fileId, inputPathStr)
            DebugLog.append(applicationContext, "BG worker: video path resolve | before=$before | after=$inputPathStr | fileId=$fileId")
            // אם עדיין thumbnail/לא קיים - ננסה שוב בריטרי (WorkManager)
            if (inputPathStr.isNullOrBlank() || isThumbnailPath(inputPathStr) || !java.io.File(inputPathStr).exists()) {
                DebugLog.append(applicationContext, "BG worker: video not ready yet -> retry")
                return Result.retry()
            }
        }
        // AUTO_DOWNLOAD_REAL_VIDEO_FLOW

        // DUP_REMOVED val fallbackPath = inputData.getString("FALLBACK_PATH")

        val logoUriStr = inputData.getString("LOGO_URI")
        val logoRelX = inputData.getFloat("LOGO_REL_X", 0f)
        val logoRelY = inputData.getFloat("LOGO_REL_Y", 0f)
        val logoRelW = inputData.getFloat("LOGO_REL_W", 0.2f)

        val rectsJson = inputData.getString("RECTS_JSON") ?: "[]"
        val rects = parseRects(rectsJson)

        DebugLog.append(applicationContext, "BG worker start | target=$target isVideo=$isVideo fileId=$fileId fallback=$fallbackPath rects=${rects.size} hasLogo=${!logoUriStr.isNullOrBlank()}")

        // Resolve ORIGINAL media: prefer FILE_ID. fallbackPath is preview thumbPath.
// (removed) inputPathStr is built above
        if (inputPath == null || !File(inputPathStr).exists()) {
            DebugLog.append(applicationContext, "BG worker FAIL: inputPathStr missing")
            return Result.failure()
        }

        val hasEdits = rects.isNotEmpty() || !logoUriStr.isNullOrBlank()
        if (!hasEdits) {
            // No edits -> send original immediately
            return try {
                TdLibManager.sendFinalMessage(target, caption, inputPathStr, isVideo)
                DebugLog.append(applicationContext, "BG worker sent original OK")
                Result.success()
            } catch (e: Exception) {
                DebugLog.appendErr(applicationContext, "BG worker send original FAIL", e)
                Result.retry()
            }
        }

        val outExt = if (isVideo) "mp4" else "jpg"
        val outFile = File(applicationContext.cacheDir, "edited_${System.currentTimeMillis()}.$outExt")
        val outPath = outFile.absolutePath

        val success = if (isVideo) {
            processVideo(inputPathStr, outPath, rects, logoUriStr, logoRelX, logoRelY, logoRelW)
        } else {
            processImage(inputPathStr, outPath, rects, logoUriStr, logoRelX, logoRelY, logoRelW)
        }

        DebugLog.append(applicationContext, "BG worker processed success=$success out=$outPath")

        val sendPath = if (success && File(outPath).exists() && File(outPath).length() > 1024) outPath else inputPathStr

        return try {
            TdLibManager.sendFinalMessage(target, caption, sendPath, isVideo)
            DebugLog.append(applicationContext, "BG worker sendFinalMessage OK | path=$sendPath")
            Result.success()
        } catch (e: Exception) {
            DebugLog.appendErr(applicationContext, "BG worker sendFinalMessage FAIL", e)
            Result.retry()
        }
    }

    private suspend fun resolveOriginalPath(fileId: Int, fallbackPath: String?): String? {
        if (fileId != 0) {
            TdLibManager.getFilePath(fileId)?.let { if (File(it).exists()) return it }
            try { TdLibManager.downloadFile(fileId) } catch (_: Exception) {}
            for (i in 0..120) { // ~60s
                val p = TdLibManager.getFilePath(fileId)
                if (p != null && File(p).exists() && File(p).length() > 1024) return p
                delay(500)
            }
        }
        return fallbackPath?.takeIf { File(it).exists() }
    }

    private suspend fun processVideo(
        input: String,
        output: String,
        rects: List<BlurRect>,
        logoUriStr: String?,
        lx: Float, ly: Float, lw: Float
    ): Boolean = suspendCancellableCoroutine { cont ->
        MediaProcessor.processContent(
            context = applicationContext,
            inputPathStr = input,
            outputPath = output,
            isVideo = true,
            blurRects = rects,
            hasLogo = !logoUriStr.isNullOrBlank(),
            logoPath = logoUriStr,
            logoRelX = lx,
            logoRelY = ly,
            logoRelW = lw
        ) { ok -> cont.resume(ok) }
    }

    private suspend fun processImage(
        input: String,
        output: String,
        rects: List<BlurRect>,
        logoUriStr: String?,
        lx: Float, ly: Float, lw: Float
    ): Boolean {
        return try {
            ImageUtils.processImage(
                applicationContext,
                input,
                output,
                rects,
                logoUriStr?.let { Uri.parse(it) },
                lx, ly, lw
            )
            File(output).exists() && File(output).length() > 1024
        } catch (e: Exception) {
            DebugLog.appendErr(applicationContext, "BG image process FAIL", e)
            false
        }
    }

    private fun parseRects(json: String): List<BlurRect> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<BlurRect>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    BlurRect(
                        left = o.getDouble("left").toFloat(),
                        top = o.getDouble("top").toFloat(),
                        right = o.getDouble("right").toFloat(),
                        bottom = o.getDouble("bottom").toFloat()
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun encodeRects(rects: List<BlurRect>): String {
            val arr = JSONArray()
            for (r in rects) {
                val o = JSONObject()
                o.put("left", r.left)
                o.put("top", r.top)
                o.put("right", r.right)
                o.put("bottom", r.bottom)
                arr.put(o)
            }
            return arr.toString()
        }

        fun buildInput(
            target: String,
            caption: String,
            isVideo: Boolean,
            fileId: Int,
            fallbackPath: String?,
            rectsJson: String,
            logoUri: String?,
            lx: Float, ly: Float, lw: Float
        ): Data {
            return Data.Builder()
                .putString("TARGET", target)
                .putString("CAPTION", caption)
                .putBoolean("IS_VIDEO", isVideo)
                .putInt("FILE_ID", fileId)
                .putString("FALLBACK_PATH", fallbackPath)
                .putString("RECTS_JSON", rectsJson)
                .putString("LOGO_URI", logoUri)
                .putFloat("LOGO_REL_X", lx)
                .putFloat("LOGO_REL_Y", ly)
                .putFloat("LOGO_REL_W", lw)
                .build()
        }
    }

    private fun isImagePath(p: String?): Boolean {
        if (p.isNullOrBlank()) return false
        val lp = p.lowercase()
        return lp.endsWith(".jpg") || lp.endsWith(".jpeg") || lp.endsWith(".png") || lp.endsWith(".webp")
    }

}