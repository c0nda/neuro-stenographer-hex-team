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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // In-project repository hosting the vendored sherpa-onnx AAR (see
        // local-repo/README note in app/build.gradle.kts).
        maven {
            name = "localVendored"
            url = uri("./local-repo")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "NeuroStenographer"
include(":app")
