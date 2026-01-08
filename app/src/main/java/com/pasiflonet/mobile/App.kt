package com.pasiflonet.mobile

import android.app.Application
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.io.File
import kotlin.system.exitProcess

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val txt =
                    "Thread: ${t.name}
" +
                    "Exception: ${e.javaClass.name}: ${e.message}

" +
                    sw.toString()

                val f = File(filesDir, "crash_last.txt")
                f.writeText(txt, Charsets.UTF_8)
                Log.e("App", "Saved crash_last.txt", e)
            } catch (ignored: Exception) {
            } finally {
                previous?.uncaughtException(t, e)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }

        // האתחול עבר ל-MainActivity.checkApiAndInit()
    }
}
