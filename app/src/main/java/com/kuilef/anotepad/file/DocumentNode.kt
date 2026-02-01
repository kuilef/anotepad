package com.kuilef.anotepad.file

import android.net.Uri

data class DocumentNode(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean
)
