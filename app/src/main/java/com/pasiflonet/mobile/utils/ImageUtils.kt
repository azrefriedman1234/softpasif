package com.pasiflonet.mobile.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

// קובץ זה נקי לחלוטין מספריות חיצוניות כבדות
object ImageUtils {

    private fun rotateBitmapIfNeeded(inputPath: String, bmp: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(inputPath)
            val ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (ori) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
                else -> return bmp
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (_: Exception) {
            bmp
        }
    }


    fun processImage(
        context: Context,
        inputPath: String,
        outputPath: String,
        blurRects: List<BlurRect>,
        logoUri: Uri?,
        relX: Float, relY: Float, relW: Float
    ): Boolean {
        try {
            // 1. טעינה חכמה (Downsampling) למניעת פיצוץ זיכרון
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(inputPath, options)
            
            // חישוב גודל: מגבילים ל-2500 פיקסלים (איכות גבוהה אבל בטוחה)
            options.inSampleSize = calculateInSampleSize(options, 4096, 4096)
            options.inJustDecodeBounds = false
            options.inMutable = true // חשוב כדי שנוכל לצייר עליה
            
            var bitmap = BitmapFactory.decodeFile(inputPath, options)
            if (bitmap == null) {
                Log.e("ImageUtils", "Failed to decode bitmap")
                return false
            }

            val canvas = Canvas(bitmap)
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()

            // 2. טשטוש (Pixelate Effect)
            if (blurRects.isNotEmpty()) {
                val paint = Paint()
                for (rect in blurRects) {
                    val left = (rect.left * w).toInt()
                    val top = (rect.top * h).toInt()
                    val right = (rect.right * w).toInt()
                    val bottom = (rect.bottom * h).toInt()
                    
                    if (right > left && bottom > top) {
                        // גזירת האזור
                        val roi = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                        // הקטנה פי 20 (יוצר את אפקט הפיקסלים)
                        val small = Bitmap.createScaledBitmap(roi, Math.max(1, roi.width/20), Math.max(1, roi.height/20), true)
                        // הגדלה חזרה
                        val pixelated = Bitmap.createScaledBitmap(small, roi.width, roi.height, false)
                        
                        // ציור חזרה
                        canvas.drawBitmap(pixelated, left.toFloat(), top.toFloat(), paint)
                        
                        // ניקוי זיכרון מידי
                        roi.recycle(); small.recycle(); pixelated.recycle()
                    }
                }
            }

            // 3. הוספת לוגו
            if (logoUri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(logoUri)
                    val logoBmp = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (logoBmp != null) {
                        val finalLogoW = (w * relW).toInt()
                        val ratio = logoBmp.height.toFloat() / logoBmp.width.toFloat()
                        val finalLogoH = (finalLogoW * ratio).toInt()
                        
                        val scaledLogo = Bitmap.createScaledBitmap(logoBmp, finalLogoW, finalLogoH, true)
                        
                        val drawX = w * relX
                        val drawY = h * relY
                        
                        canvas.drawBitmap(scaledLogo, drawX, drawY, null)
                        if (scaledLogo != logoBmp) scaledLogo.recycle()
                        logoBmp.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("ImageUtils", "Logo error", e)
                }
            }

            // 4. שמירה לקובץ
            val outStream = FileOutputStream(outputPath)
            val isPng = inputPath.lowercase().endsWith(".png")
            val fmt = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val q = if (fmt == Bitmap.CompressFormat.JPEG) 95 else 100
            bitmap.compress(fmt, q, outStream)
            outStream.flush()
            outStream.close()
            
            bitmap.recycle()
            return true

        } catch (t: Throwable) {
            Log.e("ImageUtils", "Critical Image Error", t)
            return false
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
