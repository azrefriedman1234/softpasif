package com.pasiflonet.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.pasiflonet.mobile.utils.DebugLog
import java.io.OutputStream
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.utils.KeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import android.util.Log

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- Crash Catcher Start ---
        try {
            b = ActivityMainBinding.inflate(layoutInflater)
            setContentView(b.root)
        // AUTO_EXPORT_DEBUG_LOG_ON_START
        exportDebugLogToDownloads()
        showSmartExceptionStatus()
        showLastCrashIfAny()
        } catch (e: Exception) {
            // אם העיצוב נכשל, נציג מסך חירום
            Log.e("UI_CRASH", "Layout Inflation Failed", e)
            val errorView = android.widget.TextView(this)
            errorView.text = "CRITICAL UI ERROR:\n${e.message}\n\nCheck Logcat for details."
            errorView.textSize = 20f
            errorView.setTextColor(android.graphics.Color.RED)
            errorView.setPadding(50, 50, 50, 50)
            setContentView(errorView)
            return // עוצרים כאן כדי לא להמשיך לקרוס
        }
        // --- Crash Catcher End ---
        
        try {
            startService(Intent(this, KeepAliveService::class.java))
            
            b.apiContainer.visibility = View.GONE; b.loginContainer.visibility = View.GONE; b.mainContent.visibility = View.GONE
            setupUI(); checkPermissions(); checkApiAndInit()
        } catch (e: Exception) {
            Toast.makeText(this, "Runtime Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        b.btnSaveApi.setOnClickListener {
             val id = b.etApiId.text.toString().toIntOrNull()
            val hash = b.etApiHash.text.toString()
            if (id != null && hash.isNotEmpty()) {
                prefs.edit().putInt("api_id", id).putString("api_hash", hash).apply()
                checkApiAndInit()
            }
        }
        b.btnSendCode.setOnClickListener { 
            val p = b.etPhone.text.toString(); if(p.isEmpty()) return@setOnClickListener
            b.btnSendCode.text="Sending..."; b.btnSendCode.isEnabled=false
            TdLibManager.sendPhone(p) { e -> runOnUiThread { b.btnSendCode.isEnabled=true; b.btnSendCode.text="SEND CODE"; Toast.makeText(this,e,Toast.LENGTH_LONG).show() } }
        }
        b.btnVerify.setOnClickListener { val c=b.etCode.text.toString(); if(c.isNotEmpty()) TdLibManager.sendCode(c) { e->runOnUiThread{Toast.makeText(this,e,Toast.LENGTH_LONG).show()}} }
        b.btnVerifyPassword.setOnClickListener { val pa=b.etPassword.text.toString(); TdLibManager.sendPassword(pa) { e->runOnUiThread{Toast.makeText(this,e,Toast.LENGTH_LONG).show()}} }

        adapter = ChatAdapter(emptyList()) { msg ->
            var thumbPath: String? = null
            var miniThumbData: ByteArray? = null
            var fullId = 0
            var isVideo = false
            var caption = ""
            var thumbId = 0 

            when (msg.content) {
                is TdApi.MessagePhoto -> {
                    val c = msg.content as TdApi.MessagePhoto
                    miniThumbData = c.photo.minithumbnail?.data
                    val mediumPhoto = c.photo.sizes.find { it.type == "m" } ?: c.photo.sizes.firstOrNull()
                    if (mediumPhoto != null) { thumbPath = mediumPhoto.photo.local.path; thumbId = mediumPhoto.photo.id }
                    fullId = if (c.photo.sizes.isNotEmpty()) c.photo.sizes.last().photo.id else 0
                    caption = c.caption.text
                }
                is TdApi.MessageVideo -> {
                    val c = msg.content as TdApi.MessageVideo
                    miniThumbData = c.video.minithumbnail?.data
                    val thumb = c.video.thumbnail
                    if (thumb != null) { thumbPath = thumb.file.local.path; thumbId = thumb.file.id }
                    fullId = c.video.video.id
                    isVideo = true
                    caption = c.caption.text
                }
                is TdApi.MessageText -> caption = (msg.content as TdApi.MessageText).text.text
            }

            if (fullId != 0) TdLibManager.downloadFile(fullId)
            
            val intent = Intent(this, DetailsActivity::class.java)
            if (thumbPath != null) intent.putExtra("THUMB_PATH", thumbPath)
            if (miniThumbData != null) intent.putExtra("MINI_THUMB", miniThumbData)
            intent.putExtra("THUMB_ID", thumbId)
            intent.putExtra("FILE_ID", fullId)
            intent.putExtra("IS_VIDEO", isVideo)
            intent.putExtra("CAPTION", caption)
            startActivity(intent)
        }
        
        b.rvMessages.layoutManager = LinearLayoutManager(this)
        b.rvMessages.adapter = adapter
        
        b.btnClearCache.setOnClickListener { 
            val files = cacheDir.listFiles()
            var deletedCount = 0
            if (files != null) {
                for (file in files) { if (file.isFile && file.delete()) deletedCount++ }
            }
            Toast.makeText(this, if (deletedCount > 0) "🧹 Cleaned $deletedCount files!" else "✨ Clean", Toast.LENGTH_SHORT).show()
        }
        
        b.btnSettings.setOnClickListener { 
            try { startActivity(Intent(this, SettingsActivity::class.java)) } 
            catch (e: Exception) { Toast.makeText(this, "Settings Error", Toast.LENGTH_SHORT).show() }
        }
    }
    
    private fun checkApiAndInit() { 
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val i = prefs.getInt("api_id", 0)
        val h = prefs.getString("api_hash", "")
        if(i!=0 && !h.isNullOrEmpty()){ b.apiContainer.visibility=View.GONE; TdLibManager.init(this@MainActivity,i,h); observeAuth() } else { b.apiContainer.visibility=View.VISIBLE; b.mainContent.visibility=View.GONE } 
    }
    
    private fun observeAuth() { 
        lifecycleScope.launch { 
            TdLibManager.authState.collect { s -> 
                runOnUiThread { 
                    if(s is TdApi.AuthorizationStateWaitPhoneNumber){ b.apiContainer.visibility=View.GONE; b.loginContainer.visibility=View.VISIBLE; b.phoneLayout.visibility=View.VISIBLE; b.codeLayout.visibility=View.GONE; b.passwordLayout.visibility=View.GONE; b.btnSendCode.isEnabled=true; b.btnSendCode.text="SEND CODE" } 
                    else if(s is TdApi.AuthorizationStateWaitCode){ b.loginContainer.visibility=View.VISIBLE; b.phoneLayout.visibility=View.GONE; b.codeLayout.visibility=View.VISIBLE } 
                    else if(s is TdApi.AuthorizationStateWaitPassword){ b.loginContainer.visibility=View.VISIBLE; b.codeLayout.visibility=View.GONE; b.passwordLayout.visibility=View.VISIBLE } 
                    else if(s is TdApi.AuthorizationStateReady){ b.loginContainer.visibility=View.GONE; b.mainContent.visibility=View.VISIBLE } 
                } 
            } 
        }
        
        lifecycleScope.launch(Dispatchers.IO) { 
            TdLibManager.currentMessages.collect { m -> 
                withContext(Dispatchers.Main) { 
                    adapter.updateList(m) 
                } 
                m.forEach { msg ->
                    var fileIdToDownload = 0
                    if (msg.content is TdApi.MessagePhoto) {
                        fileIdToDownload = (msg.content as TdApi.MessagePhoto).photo.sizes.lastOrNull()?.photo?.id ?: 0
                    } else if (msg.content is TdApi.MessageVideo) {
                        fileIdToDownload = (msg.content as TdApi.MessageVideo).video.video.id
                    }
                    if (fileIdToDownload != 0) TdLibManager.downloadFile(fileIdToDownload)
                }
            } 
        } 
    }
    
    private fun checkPermissions() { 
        val perms = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.clear()
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        requestPermissionLauncher.launch(perms.toTypedArray()) 
    }

    private fun showLastCrashIfAny() {
        try {
            val f = java.io.File(filesDir, "crash_last.txt")
            if (!f.exists()) return
            val txt = f.readText(Charsets.UTF_8)

            AlertDialog.Builder(this)
                .setTitle("Crash report (last)")
                .setMessage(txt.take(12000)) // לא להציף
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash_last", txt))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear") { _, _ ->
                    f.delete()
                }
                .setNeutralButton("Export") { _, _ ->
                    exportCrashToDownloads(txt)
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (_: Exception) {}
    }


    private fun showSmartExceptionStatus() {
        try {
            Class.forName("com.arthenica.smartexception.java.Exceptions")
            Toast.makeText(this, "✅ smart-exception-java PRESENT (new APK)", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "❌ smart-exception-java MISSING (old APK or build issue)", Toast.LENGTH_LONG).show()
        }
    }


    private fun exportCrashToDownloads(txt: String) {
        try {
            val fileName = "Pasiflonet_crash_last.txt"
            val resolver = contentResolver

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "Export failed (no uri)", Toast.LENGTH_LONG).show()
                return
            }

            resolver.openOutputStream(uri)?.use { os ->
                os.write(txt.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            Toast.makeText(this, "Exported to Downloads: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun shareCrashText(txt: String) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Pasiflonet crash report")
                putExtra(Intent.EXTRA_TEXT, txt)
            }
            startActivity(Intent.createChooser(sendIntent, "Share crash report"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun exportDebugLogToDownloads() {
        try {
            val f = DebugLog.file(this)
            if (!f.exists()) {
                Toast.makeText(this, "No debug log yet", Toast.LENGTH_LONG).show()
                return
            }
            val txt = f.readText(Charsets.UTF_8)
            val fileName = "Pasiflonet_debug_log.txt"
            val resolver = contentResolver

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "Export failed (no uri)", Toast.LENGTH_LONG).show()
                return
            }

            resolver.openOutputStream(uri)?.use { os ->
                os.write(txt.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            Toast.makeText(this, "Exported debug log to Downloads", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}
