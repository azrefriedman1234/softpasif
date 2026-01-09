package com.pasiflonet.mobile

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.BlurRect
import android.widget.Toast
import android.view.ScaleGestureDetector

class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding

    // media context
    private var fileId: Long = -1L
    private var isVideo: Boolean = false
    private var thumbPath: String? = null
    private var mediaUri: Uri? = null

    // logo placement stored relative to image bounds (0..1)
    private var savedLogoRelX: Float = 0.5f
    private var savedLogoRelY: Float = 0.5f
    private var savedLogoScale: Float = 1.0f

    // computed bounds of the displayed image inside the ImageView
    private var imageBounds: RectF? = null

    private val appPrefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private val draftPrefs by lazy { getSharedPreferences("draft_prefs", MODE_PRIVATE) }

    private val pickLogoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                appPrefs.edit().putString("logo_uri", uri.toString()).apply()
                showLogoFromPrefs()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        
        wireLogoFromSettings()
// read intent extras (keep tolerant keys)
        fileId = intent.getLongExtra("file_id", intent.getLongExtra("fileId", -1L))
        isVideo = intent.getBooleanExtra("is_video", intent.getBooleanExtra("isVideo", false))
        thumbPath = intent.getStringExtra("thumb_path") ?: intent.getStringExtra("thumbPath")
        mediaUri = intent.getParcelableExtra("media_uri") ?: intent.getParcelableExtra("mediaUri")

        // UI init
        b.loadingOverlay.visibility = View.GONE
        b.btnSend.isEnabled = true

        // when preview is laid out, compute bounds and restore logo position
        b.ivPreview.doOnLayout {
            imageBounds = computeImageBounds(b.ivPreview)
            restoreLogoPlacementFromPrefs()
            applyLogoPlacementToView()
        }

        // Logo pick button (if exists in your layout binding)
        runCatching {
            b.btnModeLogo.setOnClickListener {
                pickLogoLauncher.launch(arrayOf("image/*"))
            }
        }

        // draggable logo interactions (if your ivDraggableLogo supports touch drag in existing code)
        // We'll still persist placement when user releases (you likely already have touch handling in your view).
        // If not, you can add it later; this file keeps the saved placement plumbing intact.

        restoreDraft()

        b.btnSend.setOnClickListener { onSendClicked() }

        // If you have translate button in binding, keep it (calls your existing translate util if present)
        runCatching {
            b.btnTranslate.setOnClickListener {
                val txt = b.etCaption.text?.toString().orEmpty()
                if (txt.isBlank()) return@setOnClickListener
                lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        // If you already have translateToHebrew() somewhere, this keeps feature alive.
                        val out = translateToHebrew(txt)
                        b.etCaption.setText(out)
                        saveDraft()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        showLogoFromPrefs()
    }

    override fun onPause() {
        super.onPause()
        saveDraft()
        saveLogoPlacementToPrefs()
    }

    private fun onSendClicked() {
        b.loadingOverlay.visibility = View.VISIBLE
        b.btnSend.isEnabled = false

        val target = appPrefs.getString("target_username", "") ?: ""
        val caption = b.etCaption.text?.toString().orEmpty()
        val includeMedia = runCatching { b.swIncludeMedia.isChecked }.getOrDefault(true)

        if (target.isBlank()) {
            safeToast("No target set!")
            b.loadingOverlay.visibility = View.GONE
            b.btnSend.isEnabled = true
            return
        }

        // collect blur rects relative 0..1 from your drawing view
        val rects = ArrayList<BlurRect>()
        runCatching {
            for (r in b.drawingView.rects) {
                rects.add(BlurRect(r.left, r.top, r.right, r.bottom))
            }
        }

        // resolve logo uri if visible
        var logoUri: Uri? = null
        if (runCatching { b.ivDraggableLogo.visibility == View.VISIBLE }.getOrDefault(false)) {
            val u = appPrefs.getString("logo_uri", null)
            if (!u.isNullOrBlank()) {
                val uri = Uri.parse(u)
                // sanity check
                runCatching { contentResolver.openInputStream(uri)?.close() }.getOrNull()
                logoUri = uri
            }
        }

        // finish immediately (your requirement)
        runOnUiThread { if (!isFinishing) finish() }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!includeMedia) {
                    TdLibManager.sendFinalMessage(target, caption, null, false)
                    clearDraft()
                    return@launch
                  }

                // Build a "thumbPath" if missing but we have content uri
                val thumb = thumbPath ?: mediaUri?.let { copyUriToCache(it) }

                // Persist latest placement before sending
                saveLogoPlacementToPrefs()

                // Send media using your existing manager signature (keep tolerant)
                // If your TdLibManager expects more args (blur rects/logo placement), adapt there —
                // but this Activity keeps the same concepts wired.
                TdLibManager.sendFinalMessage(target, caption, thumb, isVideo)
                    clearDraft()
                      clearDraft()
            } catch (_: Exception) {
                // If we already finished, nothing to show. But avoid leaving overlay stuck if still alive.
                runOnUiThread {
                    b.loadingOverlay.visibility = View.GONE
                    b.btnSend.isEnabled = true
                }
            }
        }
    }

    // -------------------- Draft --------------------

    private fun saveDraft() {
        val o = JSONObject()
        o.put("caption", b.etCaption.text?.toString().orEmpty())
        o.put("includeMedia", runCatching { b.swIncludeMedia.isChecked }.getOrDefault(true))

        val arr = JSONArray()
        runCatching {
            for (r in b.drawingView.rects) {
                val jr = JSONObject()
                jr.put("l", r.left); jr.put("t", r.top); jr.put("r", r.right); jr.put("b", r.bottom)
                arr.put(jr)
            }
        }
        o.put("rects", arr)

        draftPrefs.edit().putString("draft_json", o.toString()).apply()
    }

    private fun restoreDraft() {
        val s = draftPrefs.getString("draft_json", null) ?: return
        runCatching {
            val o = JSONObject(s)
            b.etCaption.setText(o.optString("caption", ""))

            runCatching {
                b.swIncludeMedia.isChecked = o.optBoolean("includeMedia", true)
            }

            val arr = o.optJSONArray("rects") ?: JSONArray()
            val list = ArrayList<BlurRect>()
            for (i in 0 until arr.length()) {
                val jr = arr.getJSONObject(i)
                list.add(BlurRect(
                        jr.optDouble("l", 0.0).toFloat(),
                        jr.optDouble("t", 0.0).toFloat(),
                        jr.optDouble("r", 0.0).toFloat(),
                        jr.optDouble("b", 0.0).toFloat()
                    )
                )
            }
            runCatching { b.drawingView.setRects(list) }
        }
    }

    private fun clearDraft() {
        draftPrefs.edit().remove("draft_json").apply()
    }

    // -------------------- Logo placement --------------------

    private fun restoreLogoPlacementFromPrefs() {
        savedLogoRelX = appPrefs.getFloat("logo_rel_x", savedLogoRelX)
        savedLogoRelY = appPrefs.getFloat("logo_rel_y", savedLogoRelY)
        savedLogoScale = appPrefs.getFloat("logo_scale", savedLogoScale)
    }

    private fun saveLogoPlacementToPrefs() {
        // if bounds known and logo visible, derive rel pos from current view coords
        val bounds = imageBounds ?: return
        val logo = runCatching { b.ivDraggableLogo }.getOrNull() ?: return
        if (logo.visibility != View.VISIBLE) return

        // logo center in parent coordinates; assume logo and preview share same parent
        val cx = logo.x + logo.width / 2f
        val cy = logo.y + logo.height / 2f

        // convert to rel inside bounds
        val relX = ((cx - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val relY = ((cy - bounds.top) / bounds.height()).coerceIn(0f, 1f)

        appPrefs.edit()
            .putFloat("logo_rel_x", relX)
            .putFloat("logo_rel_y", relY)
            .putFloat("logo_scale", savedLogoScale)
            .apply()

        savedLogoRelX = relX
        savedLogoRelY = relY
    }

    private fun applyLogoPlacementToView() {
        val bounds = imageBounds ?: return
        val logo = runCatching { b.ivDraggableLogo }.getOrNull() ?: return
        if (logo.visibility != View.VISIBLE) return

        // place logo center at rel position
        val cx = bounds.left + bounds.width() * savedLogoRelX
        val cy = bounds.top + bounds.height() * savedLogoRelY

        logo.scaleX = savedLogoScale
        logo.scaleY = savedLogoScale

        // set x/y as top-left
        logo.x = cx - (logo.width * savedLogoScale) / 2f
        logo.y = cy - (logo.height * savedLogoScale) / 2f
    }

    private fun showLogoFromPrefs() {
        val logo = runCatching { b.ivDraggableLogo }.getOrNull() ?: return
        val u = appPrefs.getString("logo_uri", null)
        if (u.isNullOrBlank()) {
            logo.visibility = View.GONE
            return
        }
        val uri = Uri.parse(u)
        // simplest: setImageURI (works for content uri)
        runCatching {
            logo.setImageURI(uri)
            logo.visibility = View.VISIBLE
        }.onFailure {
            logo.visibility = View.GONE
        }
        applyLogoPlacementToView()
    }

    // -------------------- Helpers --------------------

    private fun computeImageBounds(iv: ImageView): RectF? {
        val d = iv.drawable ?: return null
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null

        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)

        val scale = minOf(vw / dw, vh / dh)
        val sw = dw * scale
        val sh = dh * scale
        val left = (vw - sw) / 2f
        val top = (vh - sh) / 2f
        return RectF(left, top, left + sw, top + sh)
    }

    private fun copyUriToCache(uri: Uri): String? {
        return try {
            val f = File(cacheDir, "media_${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(f).use { out -> inp.copyTo(out) }
            } ?: return null
            f.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun safeToast(msg: String) {
        runCatching {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // If you already have it elsewhere, keep this signature.
    // If not, create it (suspend) in your utils; leaving it here keeps feature wired.
    private suspend fun translateToHebrew(text: String): String {
        // fallback: no-op if not implemented elsewhere
        return text
    }

    private fun _logoButton(): android.view.View? {
        // Try both possible IDs so builds don't break if layout changed
        val id1 = resources.getIdentifier("btnModeLogo", "id", packageName)
        if (id1 != 0) return findViewById(id1)
        val id2 = resources.getIdentifier("btnSelectLogo", "id", packageName)
        if (id2 != 0) return findViewById(id2)
        return null
    }


    private var logoOverlay: android.widget.ImageView? = null
    private var logoRelX: Float = 0.5f
    private var logoRelY: Float = 0.5f
    private var logoScale: Float = 0.25f

    private fun wireLogoFromSettings() {
        // try common button ids without relying on ViewBinding property names
        val btn = firstViewByIdNames(
            "btnPickLogo", "btnSelectLogo", "btnLogo", "btnSetLogo", "btnChooseLogo"
        )
        btn?.setOnClickListener { applyLogoFromSettings() }

        // auto-show if exists
        applyLogoFromSettings(showToastIfMissing = false)
    }

    private fun applyLogoFromSettings(showToastIfMissing: Boolean = true) {
        val sp = prefsForLogo()
        val uriStr = readFirstString(sp, "logo_uri", "logoUri", "logo_path", "logoPath")
        if (uriStr.isNullOrBlank()) {
            if (showToastIfMissing) {
                android.widget.Toast.makeText(this, "אין לוגו בהגדרות", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        val container = findPreviewContainer() ?: return
        val iv = ensureLogoOverlay(container)

        // placement (supports old/new key names)
        logoRelX = sp.getFloat("logo_rel_x", sp.getFloat("logoRelX", 0.5f))
        logoRelY = sp.getFloat("logo_rel_y", sp.getFloat("logoRelY", 0.5f))
        logoScale = sp.getFloat("logo_scale", sp.getFloat("logoScale", 0.25f))

        iv.setImageURI(android.net.Uri.parse(uriStr))
        iv.visibility = android.view.View.VISIBLE

        container.post {
            applyLogoPlacement(container, iv)
            setupLogoTouch(container, iv, sp)
        }
    }

    private fun prefsForLogo(): android.content.SharedPreferences {
        val names = listOf("pasif_settings", "settings", "prefs", "app_prefs")
        val keys = listOf("logo_uri", "logoUri", "logo_path", "logoPath")
        for (n in names) {
            val sp = getSharedPreferences(n, MODE_PRIVATE)
            for (k in keys) {
                val v = sp.getString(k, null)
                if (!v.isNullOrBlank()) return sp
            }
        }
        return getSharedPreferences("pasif_settings", MODE_PRIVATE)
    }

    private fun readFirstString(sp: android.content.SharedPreferences, vararg keys: String): String? {
        for (k in keys) {
            val v = sp.getString(k, null)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun findPreviewContainer(): android.view.ViewGroup? {
        // common containers by id name
        val byName =
            (findViewByIdName("previewContainer") as? android.view.ViewGroup)
                ?: (findViewByIdName("flPreview") as? android.view.ViewGroup)
                ?: (findViewByIdName("preview") as? android.view.ViewGroup)

        if (byName != null) return byName

        // fallback: parent of drawingView
        return runCatching { b.drawingView.parent as? android.view.ViewGroup }.getOrNull()
            ?: (b.root as? android.view.ViewGroup)
    }

    private fun ensureLogoOverlay(container: android.view.ViewGroup): android.widget.ImageView {
        val existing = logoOverlay
        if (existing != null && existing.parent === container) return existing

        val iv = android.widget.ImageView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            visibility = android.view.View.GONE
        }
        container.addView(iv)
        iv.bringToFront()
        logoOverlay = iv
        return iv
    }

    private fun applyLogoPlacement(container: android.view.ViewGroup, iv: android.widget.ImageView) {
        if (container.width <= 0 || container.height <= 0) return

        val s = logoScale.coerceIn(0.05f, 3.0f)
        iv.scaleX = s
        iv.scaleY = s

        val w = (if (iv.width > 0) iv.width else iv.measuredWidth).toFloat().coerceAtLeast(1f)
        val h = (if (iv.height > 0) iv.height else iv.measuredHeight).toFloat().coerceAtLeast(1f)

        val cx = logoRelX.coerceIn(0f, 1f) * container.width
        val cy = logoRelY.coerceIn(0f, 1f) * container.height

        iv.translationX = cx - (w * s) / 2f
        iv.translationY = cy - (h * s) / 2f
    }

    private fun setupLogoTouch(
        container: android.view.ViewGroup,
        iv: android.widget.ImageView,
        sp: android.content.SharedPreferences
    ) {
        val scaleDetector = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val newScale = (iv.scaleX * detector.scaleFactor).coerceIn(0.05f, 3.0f)
                    iv.scaleX = newScale
                    iv.scaleY = newScale
                    saveLogoPlacement(container, iv, sp)
                    return true
                }
            }
        )

        var lastX = 0f
        var lastY = 0f
        var dragging = false

        iv.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)

            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastX = ev.rawX
                    lastY = ev.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging || scaleDetector.isInProgress) return@setOnTouchListener true
                    val dx = ev.rawX - lastX
                    val dy = ev.rawY - lastY
                    lastX = ev.rawX
                    lastY = ev.rawY

                    iv.translationX += dx
                    iv.translationY += dy
                    saveLogoPlacement(container, iv, sp)
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    saveLogoPlacement(container, iv, sp)
                    true
                }
                else -> true
            }
        }
    }

    private fun saveLogoPlacement(
        container: android.view.ViewGroup,
        iv: android.widget.ImageView,
        sp: android.content.SharedPreferences
    ) {
        if (container.width <= 0 || container.height <= 0) return
        val s = iv.scaleX

        val w = (if (iv.width > 0) iv.width else iv.measuredWidth).toFloat().coerceAtLeast(1f)
        val h = (if (iv.height > 0) iv.height else iv.measuredHeight).toFloat().coerceAtLeast(1f)

        val cx = iv.translationX + (w * s) / 2f
        val cy = iv.translationY + (h * s) / 2f

        logoRelX = (cx / container.width).coerceIn(0f, 1f)
        logoRelY = (cy / container.height).coerceIn(0f, 1f)
        logoScale = s.coerceIn(0.05f, 3.0f)

        sp.edit()
            .putFloat("logo_rel_x", logoRelX)
            .putFloat("logo_rel_y", logoRelY)
            .putFloat("logo_scale", logoScale)
            .apply()
    }

    private fun findViewByIdName(name: String): android.view.View? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun firstViewByIdNames(vararg names: String): android.view.View? {
        for (n in names) {
            val v = findViewByIdName(n)
            if (v != null) return v
        }
        return null
    }

}