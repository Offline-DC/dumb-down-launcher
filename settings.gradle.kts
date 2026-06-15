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

rootProject.name = "DumbDownLauncher"
include(":app")

includeBuild("../matrix-app/dpad-messenger-backend") {
    dependencySubstitution {
        substitute(module("com.offline.dpadmessenger.backend:gmessages"))
            .using(project(":gmessages"))
    }
}
