package com.pasiflonet.mobile.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

object MediaProcessor {

    /**
     * blurRects: מצופה להיות ערכים יחסיים (0..1) של left/top/right/bottom.
     * logoRelX/Y/W: גם יחסיים (0..1) ביחס לרוחב/גובה הווידאו.
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
        // ✅ HARD GUARD: אם ל-ffmpeg-kit חסר smart-exception בזמן ריצה, לא לקרוס.
        try {
            Class.forName("com.arthenica.smartexception.java.Exceptions")
        } catch (t: Throwable) {
            Log.e("MediaProcessor", "Missing smart-exception-java (Exceptions). FFmpeg disabled.", t)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "FFmpegKit missing dependency (smart-exception). Install NEW APK build.",
                    Toast.LENGTH_LONG
                ).show()
            }
            callback(false)
            return
        }

        DebugLog.append(
            context,
            "processContent start | isVideo=$isVideo | in=$inputPath | out=$outputPath | rects=${blurRects.size} | hasLogo=$hasLogo"
        )

        val resolvedInput = resolveToLocalPath(context, inputPath, isVideo) ?: run {
            callback(false); return
        }

        // THUMBNAIL_GUARD_VIDEO_BUT_IMAGE
        val lowerIn = resolvedInput.lowercase()
        if (isVideo && (lowerIn.endsWith(".jpg") || lowerIn.endsWith(".jpeg") || lowerIn.endsWith(".png") || lowerIn.endsWith(".webp"))) {
            DebugLog.append(context, "ERROR: got image thumbnail as video input: $resolvedInput")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "נבחר Thumbnail במקום וידיאו אמיתי (tdlib/thumbnails). צריך לשלוח את קובץ הוידיאו המקורי.",
                    Toast.LENGTH_LONG
                ).show()
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
        if (rects.isEmpty() && resolvedLogo.isNullOrBlank()) {
            callback(fallbackCopy(resolvedInput, outputPath))
            return
        }

        val cmd = buildFfmpegCommand(
            inputPath = resolvedInput,
            outputPath = outputPath,
            rects = rects,
            logoPath = resolvedLogo,
            logoRelX = clamp01(logoRelX),
            logoRelY = clamp01(logoRelY),
            logoRelW = logoRelW.coerceIn(0.02f, 1.0f)
        )

        Log.d("MediaProcessor", "ffmpeg cmd: $cmd")
        DebugLog.append(context, "ffmpeg cmd: $cmd")

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
        val left = getFloatProp("left") ?: return null
        val top = getFloatProp("top") ?: return null
        val right = getFloatProp("right") ?: return null
        val bottom = getFloatProp("bottom") ?: return null

        val l = clamp01(left)
        val t = clamp01(top)
        val r = clamp01(right)
        val b = clamp01(bottom)

        val w = (r - l).coerceIn(0f, 1f)
        val h = (b - t).coerceIn(0f, 1f)
        if (w <= 0.0005f || h <= 0.0005f) return null

        return RelRect(x = l, y = t, w = w, h = h)
    }

    private fun Any.getFloatProp(name: String): Float? {
        return try {
            val getter = this.javaClass.methods.firstOrNull {
                it.name.equals("get${name.replaceFirstChar { c -> c.uppercase() }}") && it.parameterTypes.isEmpty()
            }
            if (getter != null) return (getter.invoke(this) as Number).toFloat()

            val f = this.javaClass.fields.firstOrNull { it.name == name }
            if (f != null) return (f.get(this) as Number).toFloat()

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

    private fun fallbackCopy(inputPath: String, outputPath: String): Boolean {
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

    /**
     * FFmpeg:
     * - blur rects: crop->boxblur->overlay (בפיקסלים יחסיים של הוידאו דרך iw/ih)
     * - logo: scale2ref כדי לשמור יחס (לא להימתח!), ואז overlay לפי W/H
     * - איכות: H.264 CRF 18 + AAC 192k
     */
    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        rects: List<RelRect>,
        logoPath: String?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float
    ): String {
        val hasLogo = !logoPath.isNullOrBlank()

        val sb = StringBuilder()
        sb.append("-y ")
        sb.append("-i \"").append(inputPath).append("\" ")
        if (hasLogo) sb.append("-i \"").append(logoPath).append("\" ")

        sb.append("-filter_complex \"")
        var stream = "[0:v]"

        // Blur rectangles
        rects.forEachIndexed { i, r ->
            // split base -> blur a cropped area -> overlay back
            sb.append("$stream split=2[base$i][tmp$i];")
            sb.append("[tmp$i]crop=w='iw*${r.w}':h='ih*${r.h}':x='iw*${r.x}':y='ih*${r.y}',boxblur=10:1[blur$i];")
            sb.append("[base$i][blur$i]overlay=x='iw*${r.x}':y='ih*${r.y}'[v$i];")
            stream = "[v$i]"
        }

        if (hasLogo) {
            // scale2ref keeps aspect ratio: logo width = video_w * logoRelW
            // outputs: [lg] scaled logo, [base] passthrough video
            sb.append("[1:v]format=rgba[logo];")
            sb.append("[logo]scale2ref=w='iw*${logoRelW}':h='-1'[lg][base];")
            sb.append("[base][lg]overlay=x='W*${logoRelX}':y='H*${logoRelY}':format=auto[outv]")
        } else {
            sb.append("$stream copy[outv]")
        }

        sb.append("\" ")

        // map video + (optional) audio
        sb.append("-map \"[outv]\" -map 0:a? ")

        // quality settings
        sb.append("-c:v libx264 -crf 18 -preset veryfast -pix_fmt yuv420p ")
        sb.append("-c:a aac -b:a 192k -ar 48000 ")
        sb.append("-movflags +faststart ")

        sb.append("\"").append(outputPath).append("\"")

        return sb.toString()
    }
}
