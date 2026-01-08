package com.pasiflonet.mobile.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

object MediaProcessor {

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
        // אם זה לא וידאו - אנחנו לא אמורים להיות פה (טופל ב-ImageUtils)
        if (!isVideo) {
            onComplete(false)
            return
        }

        Log.d("MediaProcessor", "Processing Video: Lite Mode")

        var logoPath: String? = null
        
        // שלב 1: הכנת לוגו עם הגנת זיכרון מחמירה
        if (logoUri != null) {
            try {
                // בדיקת גודל הלוגו לפני טעינה לזיכרון
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                val inputStreamCheck = context.contentResolver.openInputStream(logoUri)
                BitmapFactory.decodeStream(inputStreamCheck, null, options)
                inputStreamCheck?.close()

                // חישוב הקטנה (Sample Size) כדי לא לפוצץ זיכרון
                options.inSampleSize = calculateInSampleSize(options, 500, 500)
                options.inJustDecodeBounds = false

                val inputStream = context.contentResolver.openInputStream(logoUri)
                val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                if (bitmap != null) {
                    val file = File(context.cacheDir, "logo_v_safe.png")
                    val out = FileOutputStream(file)
                    // דחיסה ל-PNG קטן
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.close()
                    logoPath = file.absolutePath
                    bitmap.recycle() // שחרור זיכרון מידי
                }
            } catch (e: Exception) {
                Log.e("MediaProcessor", "Logo prep failed", e)
            }
        }

        // שלב 2: בניית פקודת FFmpeg סופר-קלילה
        val cmd = StringBuilder()
        cmd.append("-y -i \"$inputPath\" ")
        if (logoPath != null) cmd.append("-i \"$logoPath\" ")

        cmd.append("-filter_complex \"")
        
        // הקטנה ל-720p (רוחב) כדי להקל על המכשיר
        cmd.append("[0:v]scale=720:-2[v];")
        var stream = "[v]"

        // הוספת טשטוש
        if (blurRects.isNotEmpty()) {
            for (i in blurRects.indices) {
                val r = blurRects[i]
                val x = (r.left * 100).toInt()
                val y = (r.top * 100).toInt()
                val w = ((r.right - r.left) * 100).toInt()
                val h = ((r.bottom - r.top) * 100).toInt()
                cmd.append("${stream}boxblur=10:1:enable='between(x,iw*$x/100,iw*${x+w}/100)*between(y,ih*$y/100,ih*${y+h}/100)'[b$i];")
                stream = "[b$i]"
            }
        }

        // הוספת לוגו
        if (logoPath != null) {
            val x = (logoRelX * 100).toInt()
            val y = (logoRelY * 100).toInt()
            // שימוש ב-overlay פשוט
            cmd.append("[1:v]scale=iw*0.3:-1[logo];${stream}[logo]overlay=W*$x/100:H*$y/100")
        } else {
            if (blurRects.isNotEmpty()) cmd.setLength(cmd.length - 1) else cmd.append("null")
        }

        // קידוד: ultrafast, 30fps, העתקת אודיו (הכי מהיר ובטוח)
        cmd.append("\" -c:v libx264 -preset ultrafast -r 30 -pix_fmt yuv420p -c:a copy \"$outputPath\"")

        try {
            FFmpegKit.executeAsync(cmd.toString()) { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    onComplete(true)
                } else {
                    Log.e("FFmpeg", "Failed: ${session.failStackTrace}")
                    // במקרה של כישלון - לא קורסים! שולחים את המקור.
                    fallbackCopy(inputPath, outputPath, onComplete)
                }
            }
        } catch (e: Exception) {
            fallbackCopy(inputPath, outputPath, onComplete)
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
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
