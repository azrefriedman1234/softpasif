package com.pasiflonet.mobile.td

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.pasiflonet.mobile.utils.BlurRect
import com.pasiflonet.mobile.utils.MediaProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume

object TdLibManager {
    private var client: Client? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _currentMessages = MutableStateFlow<List<TdApi.Message>>(emptyList())
    val currentMessages: StateFlow<List<TdApi.Message>> = _currentMessages
    
    fun init(context: Context, apiId: Int, apiHash: String) {
        appContext = context.applicationContext
        if (client != null) return
        try { System.loadLibrary("tdjni") } catch (e: Exception) {}
        client = Client.create({ update -> scope.launch { handleUpdate(update, apiId, apiHash) } }, null, null)
    }

    private fun showToast(msg: String) {
        val ctx = appContext
        if (ctx == null) {
            Log.w("TdLibManager", "showToast skipped (appContext is null): $msg")
            return
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun handleUpdate(update: TdApi.Object, apiId: Int, apiHash: String) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                _authState.value = update.authorizationState
                if (update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) {
                    val ctx = appContext ?: return
                    val p = TdApi.SetTdlibParameters(false, File(ctx.filesDir,"tdlib").absolutePath, File(ctx.filesDir,"tdlib_files").absolutePath, null, true, true, true, true, apiId, apiHash, "en", "Android", "1.0", "1.0")
                    client?.send(p, null)
                } else if (update.authorizationState is TdApi.AuthorizationStateReady) {
                    client?.send(TdApi.LoadChats(null, 20), null)
                }
            }
            is TdApi.UpdateNewMessage -> {
                val current = _currentMessages.value.toMutableList()
                current.add(0, update.message)
                _currentMessages.value = current
            }
        }
    }

    fun sendPhone(phone: String, onError: (String) -> Unit) = client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { if(it is TdApi.Error) onError(it.message) }
    fun sendCode(code: String, onError: (String) -> Unit) = client?.send(TdApi.CheckAuthenticationCode(code)) { if(it is TdApi.Error) onError(it.message) }
    fun sendPassword(password: String, onError: (String) -> Unit) = client?.send(TdApi.CheckAuthenticationPassword(password)) { if(it is TdApi.Error) onError(it.message) }
    
    suspend fun getFilePath(fileId: Int): String? = suspendCancellableCoroutine { cont -> client?.send(TdApi.GetFile(fileId)) { o -> cont.resume(if(o is TdApi.File && o.local.isDownloadingCompleted) o.local.path else null) } }
    fun downloadFile(fileId: Int) = client?.send(TdApi.DownloadFile(fileId, 32, 0, 0, false), null)

    fun processAndSendInBackground(fileId: Int, thumbPath: String, isVideo: Boolean, caption: String, targetUsername: String, rects: List<BlurRect>, logoUri: Uri?, lX: Float, lY: Float, lScale: Float) {
        scope.launch {
            
            val ctx = appContext ?: run {
                Log.e("TdLibManager", "processAndSendInBackground: appContext is null")
                return@launch
            }
// ניסיון להשיג קובץ מקור
            var fullPath = getFilePath(fileId)
            
            // אם אין מקור, בודקים אם הטאבנייל קיים (לפחות נשלח משהו)
            if (fullPath == null) {
                if ((fullPath as? String).let { it == null || it.isBlank() || !java.io.File(it).exists() } && java.io.File(thumbPath).exists()) fullPath = thumbPath
                else {
                    showToast("❌ Error: Media file not found anywhere!")
                    return@launch
                }
            }

            val inputPath = fullPath
            val outExtension = if (isVideo) "mp4" else "jpg"
            val outPath = File(ctx.cacheDir, "sent_${System.currentTimeMillis()}.$outExtension").absolutePath
            
            showToast("⏳ Processing Media...")
            
            MediaProcessor.processContent(
                        context = ctx,
                        inputPath = inputPath!!,
                        outputPath = outPath,
                        isVideo = isVideo,
                        blurRects = rects,
                        hasLogo = (logoUri != null),
                        logoPath = logoUri?.toString(),
                        logoRelX = lX,
                        logoRelY = lY,
                        logoRelW = lScale
                    ) { success ->
                if (success) {
                    if (File(outPath).exists() && File(outPath).length() > 0) {
                        sendFinalMessage(targetUsername, caption, outPath, isVideo)
                    } else {
                        showToast("❌ Error: Processed file is empty/missing!")
                    }
                } else {
                    showToast("❌ Processing Failed.")
                }
            }
        }
    }

    fun sendFinalMessage(username: String, text: String, filePath: String?, isVideo: Boolean) {
        // NOTE: keep your existing username->chatId resolution logic in this method if you already have it.
        // If you already have `val chatId = ...` below, leave it as-is in your codebase.
        val chatId = 0

        val caption = TdApi.FormattedText(text, null)

        val content: TdApi.InputMessageContent =
            if (filePath.isNullOrBlank()) {
                TdApi.InputMessageText(caption, TdApi.LinkPreviewOptions(true, null, false, false, false), true)
            } else {
                // ✅ Send as Document to avoid Telegram compression (high quality for photos + videos)
                val f = TdApi.InputFileLocal(filePath)
                TdApi.InputMessageDocument(f, null, false, caption)
            }

        client?.send(TdApi.SendMessage(chatId.toLong(), null, null, TdApi.MessageSendOptions(), null, content)) { /* noop */ }
    }

}