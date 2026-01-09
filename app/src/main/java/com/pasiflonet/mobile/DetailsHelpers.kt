package com.pasiflonet.mobile

import androidx.work.Data
import org.json.JSONArray
import org.json.JSONObject
import com.pasiflonet.mobile.worker.BackgroundSendWorker as W

// NOTE: we intentionally avoid referencing BlurRect type here to prevent package/import issues.
// Kotlin List is covariant, so List<BlurRect> can be passed as List<Any>.
internal fun encodeRects(rects: List<Any>): String {
    val arr = JSONArray()
    for (r in rects) {
        val o = JSONObject()

        fun f(name: String): Float {
            return try {
                val method = r.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val v = method?.invoke(r)
                when (v) {
                    is Float -> v
                    is Double -> v.toFloat()
                    is Int -> v.toFloat()
                    is Long -> v.toFloat()
                    else -> 0f
                }
            } catch (_: Throwable) {
                0f
            }
        }

        o.put("l", f("getLeft"))
        o.put("t", f("getTop"))
        o.put("r", f("getRight"))
        o.put("b", f("getBottom"))
        arr.put(o)
    }
    return arr.toString()
}

internal fun buildInput(
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
        .putString(W.KEY_TARGET, target)
        .putString(W.KEY_CAPTION, caption)
        .putBoolean(W.KEY_IS_VIDEO, isVideo)
        .putLong(W.KEY_FILE_ID, fileId)
        .putString(W.KEY_FALLBACK_PATH, fallbackPath)
        .putString(W.KEY_RECTS_JSON, rectsJson)
        .putString(W.KEY_LOGO_URI, logoUriStr)
        .putFloat(W.KEY_LX, lx)
        .putFloat(W.KEY_LY, ly)
        .putFloat(W.KEY_LW, lw)
        .build()
}
