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

// NOTE: project is still named "pocketllm" pending a trademark/name review.
// Renaming touches exactly three places: this line, app/build.gradle.kts
// (namespace + applicationId), and res/values/strings.xml (app_name).
rootProject.name = "pocketllm"
include(":app")
