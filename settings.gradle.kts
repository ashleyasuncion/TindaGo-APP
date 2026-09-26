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
    // 1.0.0 is required for Gradle 9: pre-1.0.0 references the removed
    // JvmVendorSpec.IBM_SEMERU field, which throws
    // "JvmVendorSpec does not have member field 'IBM_SEMERU'" when a Java toolchain is resolved.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TindaGo"
include(":app")
