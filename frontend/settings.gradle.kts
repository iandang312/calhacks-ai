import java.io.File

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
    }
}

rootProject.name = "frontend"
include(":app")

gradle.beforeProject {
    if (project.name == "frontend") {
        project.layout.buildDirectory.set(
            File(System.getProperty("user.home"), ".cache/daisy-demo/root/build"),
        )
    }
}
