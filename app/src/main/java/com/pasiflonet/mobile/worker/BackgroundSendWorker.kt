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

    override suspend fun doWork(): Result {
        val target = inputData.getString("TARGET") ?: return Result.failure()
        val caption = inputData.getString("CAPTION") ?: ""
        val isVideo = inputData.getBoolean("IS_VIDEO", false)
        val fileId = inputData.getInt("FILE_ID", 0)
        val fallbackPath = inputData.getString("FALLBACK_PATH")
        val logoUriStr = inputData.getString("LOGO_URI")
        val logoRelX = inputData.getFloat("LOGO_REL_X", 0f)
        val logoRelY = inputData.getFloat("LOGO_REL_Y", 0f)
        val logoRelW = inputData.getFloat("LOGO_REL_W", 0.2f)
        val rectsJson = inputData.getString("RECTS_JSON") ?: "[]"
        val rects = parseRects(rectsJson)

        DebugLog.append(applicationContext, "BG worker start | target=$target isVideo=$isVideo fileId=$fileId fallback=$fallbackPath rects=${rects.size} hasLogo=${!logoUriStr.isNullOrBlank()}")

        // 1) resolve original input path (prefer fileId; fallback to path)
        val inputPath = resolveOriginalPath(fileId, fallbackPath)
        if (inputPath == null || !File(inputPath).exists()) {
            DebugLog.append(applicationContext, "BG worker FAIL: inputPath missing")
            return Result.failure()
        }

        // 2) decide output
        val outExt = if (isVideo) "mp4" else "jpg"
        val outFile = File(applicationContext.cacheDir, "edited_${System.currentTimeMillis()}.$outExt")
        val outPath = outFile.absolutePath

        val hasEdits = rects.isNotEmpty() || !logoUriStr.isNullOrBlank()
        var success = true

        // 3) process only if there are edits (logo/blur). If none – send original directly.
        if (hasEdits) {
            success = if (isVideo) {
                processVideo(inputPath, outPath, rects, logoUriStr, logoRelX, logoRelY, logoRelW)
            } else {
                processImage(inputPath, outPath, rects, logoUriStr, logoRelX, logoRelY, logoRelW)
            }
            DebugLog.append(applicationContext, "BG worker processed success=$success out=$outPath")
        } else {
            DebugLog.append(applicationContext, "BG worker: no edits, sending original")
        }

        // 4) choose what to send
        val sendPath = if (hasEdits && success && File(outPath).exists()) outPath else inputPath

        // 5) send
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
            // try immediate
            TdLibManager.getFilePath(fileId)?.let {
                if (File(it).exists()) return it
            }
            // trigger download + poll
            try { TdLibManager.downloadFile(fileId) } catch (_: Exception) {}
            for (i in 0..120) { // up to ~60s (500ms)
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
            inputPath = input,
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
            ImageUtils.processImage(applicationContext, input, output, rects, logoUriStr?.let { Uri.parse(it) }, lx, ly, lw)
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
                        x = o.getDouble("x").toFloat(),
                        y = o.getDouble("y").toFloat(),
                        w = o.getDouble("w").toFloat(),
                        h = o.getDouble("h").toFloat()
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
                o.put("x", r.x)
                o.put("y", r.y)
                o.put("w", r.w)
                o.put("h", r.h)
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
}
