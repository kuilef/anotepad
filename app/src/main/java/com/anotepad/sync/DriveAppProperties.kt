package com.anotepad.sync

import java.nio.charset.StandardCharsets

const val DRIVE_APP_PROPERTY_MAX_BYTES = 124

fun sanitizeDriveAppProperties(
    appProperties: Map<String, String>,
    onDropped: ((key: String, byteCount: Int) -> Unit)? = null
): Map<String, String> {
    if (appProperties.isEmpty()) return emptyMap()
    val sanitized = linkedMapOf<String, String>()
    for ((key, value) in appProperties) {
        val byteCount = key.toByteArray(StandardCharsets.UTF_8).size +
            value.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount <= DRIVE_APP_PROPERTY_MAX_BYTES) {
            sanitized[key] = value
        } else {
            onDropped?.invoke(key, byteCount)
        }
    }
    return sanitized
}
