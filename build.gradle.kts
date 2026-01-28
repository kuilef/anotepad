buildscript {
    val agp_version by extra("9.0.0")
}
plugins {
    id("com.android.application") version "9.0.0" apply false
    //id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
