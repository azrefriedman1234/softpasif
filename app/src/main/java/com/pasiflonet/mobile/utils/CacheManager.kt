package com.pasiflonet.mobile.utils

import android.content.Context
import java.io.File

object CacheManager {
    fun getCacheSize(context: Context): String {
        return try {
            val cacheDir = context.cacheDir
            val size = getDirSize(cacheDir)
            formatSize(size)
        } catch (e: Exception) {
            "0 MB"
        }
    }

    fun clearAppCache(context: Context) {
        try {
            deleteDir(context.cacheDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        var size: Long = 0
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file != null && file.isDirectory) {
                size += getDirSize(file)
            } else if (file != null && file.isFile) {
                size += file.length()
            }
        }
        return size
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        val children = dir.list() ?: return false
        for (i in children.indices) {
            val success = deleteDir(File(dir, children[i]))
            if (!success) {
                return false
            }
        }
        return dir.delete()
    }

    private fun formatSize(size: Long): String {
        val mb = size.toDouble() / (1024 * 1024)
        return String.format("%.2f MB", mb)
    }
}
