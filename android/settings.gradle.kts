pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox Maven repository. The Maps SDK is NOT on Maven Central; without
        // this block `:app` fails with "Could not resolve com.mapbox.maps:android".
        // It must live in dependencyResolutionManagement, not pluginManagement.
        maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }
    }
}

rootProject.name = "MedLenX Lab"
include(":app")
