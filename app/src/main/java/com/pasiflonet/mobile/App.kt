package com.pasiflonet.mobile

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))

                val txt = buildString {
                    append("Thread: ").append(t.name).append('\n')
                    append("Exception: ").append(e.javaClass.name)
                        .append(": ").append(e.message ?: "null")
                        .append('\n').append('\n')
                    append(sw.toString())
                }

                File(filesDir, "crash_last.txt").writeText(txt, Charsets.UTF_8)
                Log.e("App", "Saved crash_last.txt", e)
            } catch (_: Exception) {
                // ignore
            } finally {
                // let default handler do its thing too (dialogs / system logs)
                previous?.uncaughtException(t, e)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }
}
