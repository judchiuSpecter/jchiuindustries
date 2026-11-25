rootProject.name = "photohub"

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
        // Openize.HEIC (pure-Java HEIC/HEIF decoder) is published here, not to Maven Central.
        maven("https://releases.aspose.com/java/repo/")
    }
}

include(":shared")
include(":android")
include(":desktop")
