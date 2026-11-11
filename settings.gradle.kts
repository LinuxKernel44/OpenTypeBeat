pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
        maven("https://jitpack.io")
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "OpenTypeBeat"
include(":app")
