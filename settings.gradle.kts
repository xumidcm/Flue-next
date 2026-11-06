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
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeModule("com.github.Dimezis", "BlurView") }
        }
    }
}

rootProject.name = "WatchLauncher"
include(":app")
