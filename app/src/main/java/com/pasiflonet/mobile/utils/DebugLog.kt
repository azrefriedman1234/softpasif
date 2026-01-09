package com.pasiflonet.mobile.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val TAG = "DebugLog"
    private const val FILE_NAME = "pasif_debug_log.txt"

    fun file(context: Context): File = File(context.cacheDir, FILE_NAME)

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    @Synchronized
    fun append(context: Context, msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts | $msg\n"
        Log.d(TAG, msg)
        runCatching {
            file(context).appendText(line, Charsets.UTF_8)
        }
    }

    @Synchronized
    fun appendErr(context: Context, msg: String, t: Throwable) {
        append(context, "$msg | ${t.javaClass.simpleName}: ${t.message}")
        runCatching { file(context).appendText(Log.getStackTraceString(t) + "\n", Charsets.UTF_8) }
    }
}
