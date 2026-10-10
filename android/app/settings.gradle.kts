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
        // JitPack, limité au seul groupe NextLib (décodeur FFmpeg logiciel compatible Media3 1.5.1, GPL-3.0) :
        // aucune autre dépendance ne peut en provenir.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.anilbeesetti.nextlib") }
        }
    }
}

rootProject.name = "anime-android"
