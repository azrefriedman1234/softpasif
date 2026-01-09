package com.pasiflonet.mobile.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pasiflonet.mobile.utils.DebugLog
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

object MediaProcessor {

    /**
     * blurRects: מקבל כל List (RectF או data class עם left/top/right/bottom).
     * הערכים מצופים להיות יחסיים (0..1). אם לא, אנחנו עדיין נ-clamp כדי לא לקרוס.
     */
    fun processContent(
        context: Context,
        inputPath: String,
        outputPath: String,
        isVideo: Boolean,
        blurRects: List<out Any> = emptyList(),
        hasLogo: Boolean = false,
        logoPath: String? = null,
        logoRelX: Float = 0.02f,
        logoRelY: Float = 0.02f,
        logoRelW: Float = 0.30f,
        callback: (Boolean) -> Unit
    ) {
        // ✅ HARD GUARD: אם ffmpeg-kit חסר לו smart-exception בזמן ריצה, לא לקרוס.
        try {
            Class.forName("com.arthenica.smartexception.java.Exceptions")
        } catch (t: Throwable) {
            Log.e("MediaProcessor", "Missing smart-exception-java (Exceptions). FFmpeg disabled.", t)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "FFmpegKit missing dependency (smart-exception). Install NEW APK build.", Toast.LENGTH_LONG).show()
            }
            callback(false)
            return
        }

        DebugLog.append(context, "processContent start | isVideo=$isVideo | in=$inputPath | out=$outputPath | rects=${blurRects.size} | hasLogo=$hasLogo")

        val resolvedInput = resolveToLocalPath(context, inputPath, isVideo) ?: run {
            callback(false); return
        }


        // THUMBNAIL_GUARD_VIDEO_BUT_IMAGE
        val lowerIn = resolvedInput.lowercase()
        if (isVideo && (lowerIn.endsWith(".jpg") || lowerIn.endsWith(".jpeg") || lowerIn.endsWith(".png") || lowerIn.endsWith(".webp"))) {
            DebugLog.append(context, "ERROR: got image thumbnail as video input: $resolvedInput")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "נבחר Thumbnail במקום וידיאו אמיתי (tdlib/thumbnails). צריך לשלוח את קובץ הוידיאו המקורי.", Toast.LENGTH_LONG).show()
            }
            callback(false)
            return
        }
        val resolvedLogo = if (hasLogo && !logoPath.isNullOrBlank()) {
            resolveToLocalPath(context, logoPath, false)
        } else null

        val rects = blurRects.mapNotNull { it.toRelRectOrNull() }
            .filter { it.w > 0.0005f && it.h > 0.0005f }

        // אם אין שום עיבוד — פשוט נעתיק קובץ
        if (rects.isEmpty() && !hasLogo) {
            if (fallbackCopy(context, resolvedInput, outputPath)) callback(true) else callback(false)
            return
        }

        val cmd = buildFfmpegCommand(
            inputPath = resolvedInput,
            outputPath = outputPath,
            isVideo = isVideo,
            rects = rects,
            logoPath = resolvedLogo,
            logoRelX = clamp01(logoRelX),
            logoRelY = clamp01(logoRelY),
            logoRelW = logoRelW.coerceIn(0.05f, 1.0f)
        )

        Log.d("MediaProcessor", "ffmpeg cmd: $cmd")
        DebugLog.append(context, "ffmpeg cmd: $cmd")

        // EXTRA SAFETY: גם אם משהו עדיין חסר – לא לקרוס.
        try {
            FFmpegKit.executeAsync(cmd) { session ->
                val rc = session.returnCode
                val ok = ReturnCode.isSuccess(rc)
                DebugLog.append(context, "ffmpeg rc=$rc success=$ok")
                if (!ok) {
                    Log.e("MediaProcessor", "ffmpeg failed rc=$rc\n${session.allLogsAsString}")
                    DebugLog.append(context, "ffmpeg logs:\n" + session.allLogsAsString)
                }
                callback(ok)
            }
        } catch (e: NoClassDefFoundError) {
            Log.e("MediaProcessor", "FFmpegKit crashed due to missing class", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "FFmpegKit missing class. Install NEW APK build.", Toast.LENGTH_LONG).show()
            }
            callback(false)
        }
    }

    // ------------------------ helpers ------------------------

    private data class RelRect(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun Any.toRelRectOrNull(): RelRect? {
        // תומך גם ב-android.graphics.RectF וגם data class עם left/top/right/bottom
        val left = getFloatProp("left") ?: return null
        val top = getFloatProp("top") ?: return null
        val right = getFloatProp("right") ?: return null
        val bottom = getFloatProp("bottom") ?: return null

        val x = clamp01(left)
        val y = clamp01(top)
        val w = clamp01(right) - clamp01(left)
        val h = clamp01(bottom) - clamp01(top)

        return RelRect(
            x = x,
            y = y,
            w = w.coerceIn(0f, 1f),
            h = h.coerceIn(0f, 1f)
        )
    }

    private fun Any.getFloatProp(name: String): Float? {
        return try {
            // getter
            val m = this.javaClass.methods.firstOrNull { it.name.equals("get${name.replaceFirstChar { it.uppercase() }}") && it.parameterTypes.isEmpty() }
            if (m != null) return (m.invoke(this) as Number).toFloat()

            // public field
            val f = this.javaClass.fields.firstOrNull { it.name == name }
            if (f != null) return (f.get(this) as Number).toFloat()

            // declared field
            val df = this.javaClass.declaredFields.firstOrNull { it.name == name }
            if (df != null) {
                df.isAccessible = true
                return (df.get(this) as Number).toFloat()
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    private fun resolveToLocalPath(context: Context, pathOrUri: String, isVideo: Boolean): String? {
        return try {
            when {
                pathOrUri.startsWith("content://") -> {
                    val uri = Uri.parse(pathOrUri)
                    val ext = if (isVideo) "mp4" else "bin"
                    val out = File(context.cacheDir, "src_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    } ?: return null
                    out.absolutePath
                }
                pathOrUri.startsWith("file://") -> Uri.parse(pathOrUri).path
                else -> pathOrUri
            }
        } catch (e: Throwable) {
            Log.e("MediaProcessor", "resolveToLocalPath failed", e)
            null
        }
    }

    private fun fallbackCopy(context: Context, inputPath: String, outputPath: String): Boolean {
        return try {
            val src = File(inputPath)
            val dst = File(outputPath)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            true
        } catch (e: Throwable) {
            Log.e("MediaProcessor", "fallbackCopy failed", e)
            false
        }
    }

    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        isVideo: Boolean,
        rects: List<RelRect>,
        logoPath: String?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float
    ): String {
        val sb = StringBuilder()
        sb.append("-y ")
        sb.append("-i \"").append(inputPath).append("\" ")
        val hasLogo = !logoPath.isNullOrBlank()
        if (hasLogo) sb.append("-i \"").append(logoPath).append("\" ")

        // filter_complex: blur + logo, יציאה תמיד ל-[outv]
        sb.append("-filter_complex \"")
        var stream = "[0:v]"

        // Blur rectangles: crop+blur+overlay (יציב, לא תלוי enable)
        rects.forEachIndexed { i, r ->
            val x = r.x
            val y = r.y
            val w = r.w
            val h = r.h
            // skip almost-zero
            if (w <= 0.0005f || h <= 0.0005f) return@forEachIndexed

            sb.append("$stream split=2[base$i][tmp$i];")
            sb.append("[tmp$i]crop=w='iw*${w}':h='ih*${h}':x='iw*${x}':y='ih*${y}',boxblur=10:1[blur$i];")
            sb.append("[base$i][blur$i]overlay=x='main_w*${x}':y='main_h*${y}'[v$i];")
            stream = "[v$i]"
        }

        if (hasLogo) {
            // ✅ scale logo relative to main stream using scale2ref (main_w/main_h valid here)
            sb.append("[1:v]$stream scale2ref=w='main_w*${logoRelW}':h=-1[logo][base];")
            sb.append("[base][logo]overlay=x='main_w*${logoRelX}':y='main_h*${logoRelY}'[outv]")
        } else {
            sb.append("$stream null[outv]")
        }

        sb.append("\" ")

        // Map + encode:
        // - video: H264 CRF18 + AAC (edited videos often break on -c:a copy)
        // - image: single frame
        sb.append("-map \"[outv]\" ")
        if (isVideo) {
            sb.append("-map 0:a? ")
            sb.append("-c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p -r 30 ")
            sb.append("-c:a aac -b:a 160k -ac 2 ")
            sb.append("-movflags +faststart ")
        } else {
            sb.append("-frames:v 1 ")
            // איכות גבוהה לתמונה (q נמוך = איכות גבוהה)
            sb.append("-q:v 2 ")
        }

        sb.append("\"").append(outputPath).append("\"")
        return sb.toString()
    }
}
