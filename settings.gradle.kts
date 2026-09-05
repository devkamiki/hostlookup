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
    }
}

rootProject.name = "HostLookup"
include(":app")

include(":rustls-platform-verifier-android")
project(":rustls-platform-verifier-android").projectDir = file("third_party/rustls-platform-verifier-android")
