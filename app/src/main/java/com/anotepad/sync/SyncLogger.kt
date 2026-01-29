package com.anotepad.sync

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncLogger(context: Context) {
    private val logFile = File(context.filesDir, "sync.log")
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(message: String) {
        val line = "${formatter.format(Date())} $message\n"
        runCatching { logFile.appendText(line) }
    }
}
