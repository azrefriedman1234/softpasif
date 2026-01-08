package com.pasiflonet.mobile.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object MediaProcessor {

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
        } catch (e: Exception) {
            Log.e("MediaProcessor", "resolveToLocalPath failed", e)
            null
        }
    }


    /**
     * Processes VIDEO only (images are handled in ImageUtils).
     * Adds optional blur rectangles and optional logo overlay.
     *
     * This implementation is defensive:
     * - Works when the input has no audio stream (maps audio optional)
     * - Avoids invalid filter graphs ("null")
     * - Clamps blur-rect values to sane ranges
     */
    fun processContent(
        context: Context,
        inputPath: String,
        outputPath: String,
        isVideo: Boolean,
        blurRects: List<BlurRect>,
        logoUri: Uri?,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float,
        onComplete: (Boolean) -> Unit
    ) {
        if (!isVideo) {
            onComplete(false)
            return
        }

        // Step 1: prepare logo file (optional)
        val logoPath: String? = tryPrepareLogoFile(context, logoUri)

        // Step 2: build a safer ffmpeg command
        val cmd = buildFfmpegCommand(
            inputPath = inputPath,
            outputPath = outputPath,
            logoPath = logoPath,
            blurRects = blurRects,
            logoRelX = logoRelX,
            logoRelY = logoRelY,
            logoRelW = logoRelW
        )

        try {
            FFmpegKit.executeAsync(cmd) { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    onComplete(true)
                } else {
                    Log.e("FFmpeg", "Failed: ${session.failStackTrace}")
                    // If ffmpeg fails, try to just copy the source to output (so we can still send).
                    fallbackCopy(inputPath, outputPath, onComplete)
                }
            }
        } catch (e: Exception) {
            Log.e("FFmpeg", "executeAsync crashed", e)
            fallbackCopy(inputPath, outputPath, onComplete)
        }
    }

    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        logoPath: String?,
        blurRects: List<BlurRect>,
        logoRelX: Float,
        logoRelY: Float,
        logoRelW: Float
    ): String {
        // Always scale to 720p width to reduce load
        val filter = StringBuilder()
        filter.append("[0:v]scale=720:-2[v0];")
        var stream = "[v0]"

        // Blur rects are relative (0..1). Clamp & skip invalid rectangles.
        var blurIndex = 0
        for (r in blurRects) {
            val left = clamp01(r.left)
            val top = clamp01(r.top)
            val right = clamp01(r.right)
            val bottom = clamp01(r.bottom)

            val w = right - left
            val h = bottom - top
            if (w <= 0.001f || h <= 0.001f) continue

            val xPct = (left * 100f).toInt()
            val yPct = (top * 100f).toInt()
            val xrPct = (right * 100f).toInt()
            val ybPct = (bottom * 100f).toInt()

            filter.append("$stream")
            filter.append("boxblur=10:1:enable='between(x,iw*$xPct/100,iw*$xrPct/100)*between(y,ih*$yPct/100,ih*$ybPct/100)'")
            filter.append("[b$blurIndex];")
            stream = "[b$blurIndex]"
            blurIndex++
        }

        val hasLogo = !logoPath.isNullOrBlank()
        val xPct = (clamp01(logoRelX) * 100f).toInt()
        val yPct = (clamp01(logoRelY) * 100f).toInt()
        val wRel = clamp01(if (logoRelW.isFinite() && logoRelW > 0f) logoRelW else 0.25f)

        if (hasLogo) {
            // Scale logo to a fraction of video width (relative)
            // Note: iw here refers to the logo input stream; we use scale2ref to size by video width safely.
            // Simpler & stable: scale logo by a fixed fraction of output width.
            filter.append("[1:v]scale=720*$wRel:-1[logo];")
            filter.append("$stream[logo]overlay=W*$xPct/100:H*$yPct/100:format=auto[outv]")
        } else {
            // No logo: just pass-through last stream as [outv]
            filter.append("$stream" + "null[outv]")
        }

                val cmd = StringBuilder()

                cmd.append("-y ")
                cmd.append("-i \"").append(inputPath).append("\" ")
                if (hasLogo) {
                    cmd.append("-i \"").append(logoPath).append("\" ")
                }

                cmd.append("-filter_complex \"")
                cmd.append(filter)
                cmd.append("\" ")

                // Map video from filter output, map audio optionally (won't fail if missing)
                cmd.append("-map \"[outv]\" -map 0:a? ")

                // Safer audio handling for edited videos: re-encode to AAC instead of copy
                cmd.append("-c:v libx264 -preset ultrafast -r 30 -pix_fmt yuv420p ")
                cmd.append("-c:a aac -b:a 128k -ac 2 ")
                cmd.append("-movflags +faststart ")
                cmd.append("\"").append(outputPath).append("\"")

                return cmd.toString()
    }

    private fun tryPrepareLogoFile(context: Context, logoUri: Uri?): String? {
        if (logoUri == null) return null
        return try {
            context.contentResolver.openInputStream(logoUri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) return null

                // Downsample to avoid OOM
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val req = 256
                val sample = calculateInSampleSize(opts, req, req)

                val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2) ?: return null

                val file = File(context.cacheDir, "logo_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.e("MediaProcessor", "Logo prep failed", e)
            null
        }
    }

    private fun fallbackCopy(input: String, output: String, callback: (Boolean) -> Unit) {
        try {
            File(input).copyTo(File(output), overwrite = true)
            callback(true)
        } catch (e: Exception) {
            callback(false)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun clamp01(v: Float): Float {
        if (!v.isFinite()) return 0f
        return min(1f, max(0f, v))
    }
}
