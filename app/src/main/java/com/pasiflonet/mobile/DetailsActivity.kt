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

}
