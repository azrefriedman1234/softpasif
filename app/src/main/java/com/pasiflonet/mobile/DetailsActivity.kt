package com.pasiflonet.mobile
import org.json.JSONObject
import org.json.JSONArray

import androidx.work.Data
import com.pasiflonet.mobile.worker.BackgroundSendWorker
import android.content.Context
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.request.CachePolicy
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.utils.MediaProcessor
import com.pasiflonet.mobile.utils.ImageUtils
import com.pasiflonet.mobile.utils.TranslationManager
import com.pasiflonet.mobile.utils.BlurRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DetailsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDetailsBinding
    private var thumbPath: String? = null
    private var isVideo = false
    private var fileId = 0
    private var thumbId = 0
    private var imageBounds = RectF()
    private var savedLogoRelX = 0.5f; private var savedLogoRelY = 0.5f; private var savedLogoScale = 1.0f

    // משגר בחירת לוגו
    private val pickLogoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                // התיקון החשוב: בקשת הרשאה קבועה לקובץ כדי שלא יעלם בפעם הבאה
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { Log.e("Logo", "Perm error", e) }

            getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putString("logo_uri", uri.toString()).apply()
            
            b.ivDraggableLogo.load(uri)
            b.ivDraggableLogo.visibility = android.view.View.VISIBLE
            b.drawingView.isBlurMode = false
            b.ivDraggableLogo.post { calculateMatrixBounds(); savedLogoRelX = 0.5f; savedLogoRelY = 0.5f; restoreLogoPosition() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            b = ActivityDetailsBinding.inflate(layoutInflater)
            setContentView(b.root)
            b.ivPreview.scaleType = ImageView.ScaleType.FIT_CENTER
            
            val intentCaption = intent.getStringExtra("CAPTION")
            val intentThumb = intent.getStringExtra("THUMB_PATH")
            
            if (intentThumb != null || intentCaption != null) {
                thumbPath = intentThumb
                val miniThumb = intent.getByteArrayExtra("MINI_THUMB")
                fileId = intent.getIntExtra("FILE_ID", 0); thumbId = intent.getIntExtra("THUMB_ID", 0)
                isVideo = intent.getBooleanExtra("IS_VIDEO", false)
                b.etCaption.setText(intentCaption ?: "")
                if (miniThumb != null) b.ivPreview.load(miniThumb)
                saveDraft()
            } else { if (restoreDraft()) safeToast("♻️ Restored session") }
            
            val targetId = if (thumbId != 0) thumbId else fileId
            if (targetId != 0) startHDImageHunter(targetId) else if (thumbPath != null) loadSharpImage(thumbPath!!)
            if (targetId == 0 && thumbPath.isNullOrEmpty()) { b.swIncludeMedia.isChecked = false; b.swIncludeMedia.isEnabled = false }
            
            b.ivPreview.viewTreeObserver.addOnGlobalLayoutListener { calculateMatrixBounds(); if (b.ivDraggableLogo.visibility == android.view.View.VISIBLE) restoreLogoPosition() }
            b.etCaption.addTextChangedListener(object : TextWatcher { override fun afterTextChanged(s: Editable?) { saveDraft() }; override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {} })
            setupTools(); setupMediaToggle()
            
        } catch (e: Exception) { safeToast("Init Error: ${e.message}") }
    }

    private fun saveDraft() { try { getSharedPreferences("draft_prefs", MODE_PRIVATE).edit().putString("draft_caption", b.etCaption.text.toString()).putString("draft_path", thumbPath).putBoolean("draft_is_video", isVideo).putInt("draft_file_id", fileId).apply() } catch (e: Exception) {} }
    private fun restoreDraft(): Boolean { val prefs = getSharedPreferences("draft_prefs", MODE_PRIVATE); val path = prefs.getString("draft_path", null); if (path != null || prefs.getString("draft_caption", "")!!.isNotEmpty()) { thumbPath = path; isVideo = prefs.getBoolean("draft_is_video", false); fileId = prefs.getInt("draft_file_id", 0); b.etCaption.setText(prefs.getString("draft_caption", "")); if (path != null) loadSharpImage(path); return true }; return false }
    private fun clearDraft() { getSharedPreferences("draft_prefs", MODE_PRIVATE).edit().clear().apply() }
    private fun safeToast(msg: String) { runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() } }
    
    private fun startHDImageHunter(targetId: Int) { TdLibManager.downloadFile(targetId); lifecycleScope.launch(Dispatchers.IO) { for (i in 0..60) { val realPath = TdLibManager.getFilePath(targetId); if (realPath != null && File(realPath).exists() && File(realPath).length() > 1000) {
                        // ONLY_STILL_PREVIEW_FOR_VIDEO: keep preview as still thumbnail
                        if (isVideo && targetId == fileId) {
                            // if someone passed FILE_ID here, ignore for preview; we want thumb only
                            continue
                        }
 withContext(Dispatchers.Main) { if(!isFinishing) { thumbPath = realPath; loadSharpImage(realPath); saveDraft() } }; break }; delay(500) } } }
    private fun loadSharpImage(path: String) { b.ivPreview.load(File(path)) { memoryCachePolicy(CachePolicy.DISABLED); diskCachePolicy(CachePolicy.DISABLED); crossfade(true); listener(onSuccess = { _, _ -> b.ivPreview.post { calculateMatrixBounds() } }) } }

    private fun setupTools() {
        b.btnModeBlur.setOnClickListener { b.drawingView.isBlurMode = true; b.drawingView.visibility = android.view.View.VISIBLE; b.ivDraggableLogo.alpha = 0.5f; calculateMatrixBounds() }
        
        // התיקון: כפתור לוגו חכם שבודק אם הקישור שבור
        b.btnModeLogo.setOnClickListener { 
            b.drawingView.isBlurMode = false
            b.ivDraggableLogo.visibility = android.view.View.VISIBLE
            b.ivDraggableLogo.alpha = 1.0f
            
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val uriStr = prefs.getString("logo_uri", null)
            var isBroken = true

            if (uriStr != null) {
                try {
                    val uri = Uri.parse(uriStr)
                    // בדיקה האם הקובץ באמת קריא
                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.close() // אם הצלחנו לפתוח ולסגור - הקובץ תקין
                    
                    b.ivDraggableLogo.load(uri)
                    isBroken = false
                } catch (e: Exception) {
                    isBroken = true // נכשל בפתיחה -> קישור שבור
                }
            }

            if (isBroken) {
                // אם הקישור שבור או לא קיים - פותחים גלריה אוטומטית
                safeToast("Link lost, please select logo again")
                pickLogoLauncher.launch("image/*")
            }
            
            b.ivDraggableLogo.post { calculateMatrixBounds(); restoreLogoPosition() } 
        }

        var dX = 0f; var dY = 0f
        b.ivDraggableLogo.setOnTouchListener { view, event -> 
            if (b.drawingView.isBlurMode) return@setOnTouchListener false
            when (event.action) { 
                android.view.MotionEvent.ACTION_DOWN -> { dX = view.x - event.rawX; dY = view.y - event.rawY }
                android.view.MotionEvent.ACTION_MOVE -> { 
                    var newX = event.rawX + dX; var newY = event.rawY + dY
                    if (imageBounds.width() > 0) { 
                        if(newX < imageBounds.left) newX = imageBounds.left; if(newX + view.width > imageBounds.right) newX = imageBounds.right - view.width
                        if(newY < imageBounds.top) newY = imageBounds.top; if(newY + view.height > imageBounds.bottom) newY = imageBounds.bottom - view.height
                        savedLogoRelX = (newX - imageBounds.left) / imageBounds.width()
                        savedLogoRelY = (newY - imageBounds.top) / imageBounds.height() 
                    }
                    view.x = newX; view.y = newY
                } 
            }
            true 
        }
        b.sbLogoSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { b.ivDraggableLogo.pivotX = 0f; b.ivDraggableLogo.pivotY = 0f; savedLogoScale = 0.5f + (p / 50f); b.ivDraggableLogo.scaleX = savedLogoScale; b.ivDraggableLogo.scaleY = savedLogoScale }; override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {} })
        b.btnTranslate.setOnClickListener { lifecycleScope.launch { val t = b.etCaption.text.toString(); if (t.isNotEmpty()) { b.etCaption.setText(TranslationManager.translateToHebrew(t)); saveDraft() } } }
        b.btnSend.setOnClickListener { performSafeSend() }
        b.btnCancel.setOnClickListener { finish() }
    }
    
    private fun calculateMatrixBounds() { val d = b.ivPreview.drawable ?: return; val m = b.ivPreview.imageMatrix; val v = FloatArray(9); m.getValues(v); val w = d.intrinsicWidth * v[Matrix.MSCALE_X]; val h = d.intrinsicHeight * v[Matrix.MSCALE_Y]; imageBounds.set(v[Matrix.MTRANS_X], v[Matrix.MTRANS_Y], v[Matrix.MTRANS_X] + w, v[Matrix.MTRANS_Y] + h); b.drawingView.setValidBounds(imageBounds) }
    private fun restoreLogoPosition() { if (imageBounds.width() > 0) { b.ivDraggableLogo.x = imageBounds.left + (savedLogoRelX * imageBounds.width()); b.ivDraggableLogo.y = imageBounds.top + (savedLogoRelY * imageBounds.height()) } }
    private fun setupMediaToggle() { b.swIncludeMedia.setOnCheckedChangeListener { _, isChecked -> b.vDisabledOverlay.visibility = if (isChecked) android.view.View.GONE else android.view.View.VISIBLE; b.mediaToolsContainer.alpha = if (isChecked) 1.0f else 0.3f; b.btnModeBlur.isEnabled = isChecked; b.btnModeLogo.isEnabled = isChecked } }

    private fun ensureLocalFilePath(pathOrUri: String, isVideo: Boolean): String? {
        return try {
            when {
                pathOrUri.startsWith("content://") -> {
                    val uri = android.net.Uri.parse(pathOrUri)
                    val ext = if (isVideo) "mp4" else "bin"
                    val out = java.io.File(cacheDir, "in_${System.currentTimeMillis()}.$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(out).use { output ->
                            input.copyTo(output)
                        }
                    } ?: return null
                    out.absolutePath
                }
                pathOrUri.startsWith("file://") -> android.net.Uri.parse(pathOrUri).path
                else -> pathOrUri
            }
        } catch (e: Exception) {
            android.util.Log.e("Details", "ensureLocalFilePath failed", e)
            null
        }
    }
private fun performSafeSend() {
    b.loadingOverlay.visibility = android.view.View.VISIBLE
    b.btnSend.isEnabled = false

    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
    val target = prefs.getString("target_username", "") ?: ""
    val caption = b.etCaption.text.toString()
    val includeMedia = b.swIncludeMedia.isChecked

    if (target.isBlank()) {
        safeToast("No target set!")
        b.loadingOverlay.visibility = android.view.View.GONE
        b.btnSend.isEnabled = true
        return
    }

    lifecycleScope.launch(Dispatchers.IO) {
            // FINISH_IMMEDIATELY_AFTER_SEND: לא להיתקע במסך Details
            runOnUiThread { if (!isFinishing) finish() }

        try {
            if (!includeMedia) {
                TdLibManager.sendFinalMessage(target, caption, null, false)
                clearDraft()
                runOnUiThread { if (!isFinishing) finish() }
                return@launch
            }

            // blur rects (0..1 relative to preview image bounds)
            val rects = ArrayList<BlurRect>()
            for (r in b.drawingView.rects) {
                rects.add(BlurRect(r.left, r.top, r.right, r.bottom))
            }

            // logo (from saved uri)
            var logoUri: Uri? = null
            if (b.ivDraggableLogo.visibility == android.view.View.VISIBLE) {
                try {
                    val u = prefs.getString("logo_uri", null)
                    if (!u.isNullOrBlank()) {
                        val uri = Uri.parse(u)
                        contentResolver.openInputStream(uri)?.close()
                        logoUri = uri
                    }
                } catch (_: Exception) {
                    // no logo
                }
            }

            // IMPORTANT: relW should match the exact visual size on preview (scaleX already applied on view)
            val relW: Float =
                if (imageBounds.width() > 0f)
                    (b.ivDraggableLogo.width * b.ivDraggableLogo.scaleX) / imageBounds.width()
                else 0.2f

            // fallbackPath: if full media not downloaded yet, worker will download by fileId.
            val fallbackPath: String? = try {
                if (fileId != 0) TdLibManager.getFilePath(fileId) else thumbPath
            } catch (_: Exception) {
                thumbPath
            }

            // ✅ SEND IN BACKGROUND: close immediately, processing/sending continues in WorkManager
            enqueueBackgroundSend(
                target = target,
                caption = caption,
                isVideo = isVideo,
                fileId = fileId.toLong(),
                fallbackPath = fallbackPath,
                rects = rects,
                logoUri = logoUri,
                lx = savedLogoRelX,
                ly = savedLogoRelY,
                lw = relW
            )

            clearDraft()
            runOnUiThread { if (!isFinishing) finish() }
            return@launch

        } catch (t: Throwable) {
            android.util.Log.e("Details", "performSafeSend failed", t)
            runOnUiThread {
                safeToast("Send failed: ${t.message ?: "unknown"}")
                if (!isFinishing) {
                    b.loadingOverlay.visibility = android.view.View.GONE
                    b.btnSend.isEnabled = true
                }
            }
        }
    }
}




    private suspend fun processVideoSuspending(
        ctx: Context, input: String, output: String, rects: List<BlurRect>, logo: Uri?, lx: Float, ly: Float, lw: Float
    ): Boolean = suspendCoroutine { cont ->
        MediaProcessor.processContent(
                        context = ctx,
                        inputPath = input,
                        outputPath = output,
                        isVideo = true,
                        blurRects = rects,
                        hasLogo = (logo != null),
                        logoPath = logo?.toString(),
                        logoRelX = lx,
                        logoRelY = ly,
                        logoRelW = lw
                    ) { result ->
            cont.resume(result)
        }
    }

    private fun enqueueBackgroundSend(
        target: String,
        caption: String,
        isVideo: Boolean,
        fileId: Long,
        fallbackPath: String?,
        rects: List<BlurRect>,
        logoUri: Uri?,
        lx: Float, ly: Float, lw: Float
    ) {
        // ✅ GUARD: never pass Telegram thumbnail as video fallback
        val fallbackPath = fallbackPath?.let { p ->
            val lp = p.lowercase()
            if (isVideo && (lp.endsWith(".jpg") || lp.endsWith(".jpeg") || lp.endsWith(".png") || lp.endsWith(".webp"))) null else p
        }

        val rectsJson = encodeRects(rects)
        val input = buildInput(
            target = target,
            caption = caption,
            isVideo = isVideo,
            fileId = fileId,
            fallbackPath = fallbackPath,
            rectsJson = rectsJson,
            logoUriStr = logoUri?.toString(),
            lx = lx, ly = ly, lw = lw
        )
        val req = OneTimeWorkRequestBuilder<BackgroundSendWorker>()
            .setInputData(input)
            .addTag("send_bg")
            .build()
        WorkManager.getInstance(applicationContext).enqueue(req)
    }


    // --- Added to fix CI: encodeRects/buildInput used by background send wiring ---

    private fun encodeRects(rects: List<BlurRect>): String {
        // JSON: [{l,t,r,b}, ...]  (relative coords expected by processor)
        val arr = org.json.JSONArray()
        for (r in rects) {
            val o = org.json.JSONObject()
            o.put("l", r.left)
            o.put("t", r.top)
            o.put("r", r.right)
            o.put("b", r.bottom)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun buildInput(
        target: String,
        caption: String,
        isVideo: Boolean,
        fileId: Long,
        fallbackPath: String?,
        rectsJson: String?,
        logoUriStr: String?,
        lx: Float,
        ly: Float,
        lw: Float
    ): Data {
        return Data.Builder()
            .putString(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_TARGET, target)
            .putString(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_CAPTION, caption)
            .putBoolean(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_IS_VIDEO, isVideo)
            .putLong(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_FILE_ID, fileId)
            .putString(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_FALLBACK_PATH, fallbackPath)
            .putString(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_RECTS_JSON, rectsJson)
            .putString(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_LOGO_URI, logoUriStr)
            .putFloat(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_LX, lx)
            .putFloat(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_LY, ly)
            .putFloat(com.pasiflonet.mobile.worker.BackgroundSendWorker.KEY_LW, lw)
            .build()
    }

}
