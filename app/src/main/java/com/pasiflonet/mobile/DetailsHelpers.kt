package com.pasiflonet.mobile

import androidx.work.Data
import org.json.JSONArray
import org.json.JSONObject
import com.pasiflonet.mobile.worker.BackgroundSendWorker as W

internal fun encodeRects(rects: List<BlurRect>): String {
    val arr = JSONArray()
    for (r in rects) {
        val o = JSONObject()
        o.put("l", r.left)
        o.put("t", r.top)
        o.put("r", r.right)
        o.put("b", r.bottom)
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
