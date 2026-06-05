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
include(":gmessages")

// Composite build: the chat UI (Compose screens, navigation, focus
// primitives, MessageRepository interface) lives in the sibling
// dpad-messenger repo. Including it as a composite build means
// dumb-down-launcher's :gmessages module can implement MessageRepository
// directly without anyone publishing to Maven first.
//
// Layout assumption: ../dpad-messenger relative to this repo. Jack's
// machine: ~/repos/dpad-messenger sitting next to ~/repos/dumb-down-launcher.
includeBuild("../dpad-messenger") {
    dependencySubstitution {
        substitute(module("com.offline.dpadmessenger:library"))
            .using(project(":library"))
    }
}
