package com.anotepad.sync.engine

class LocalStorageUnavailableException(
    message: String = "Local folder is inaccessible or permission was lost"
) : IllegalStateException(message)
