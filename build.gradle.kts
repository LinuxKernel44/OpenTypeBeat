buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP's built-in Kotlin uses this version of the Kotlin compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.chaquo.python") version "17.2.0" apply false
}
