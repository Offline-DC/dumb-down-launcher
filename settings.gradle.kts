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

// Composite build: the Google Messages backend + its pairing/chat UI now live
// in the matrix-app repo, as the :gmessages module of dpad-messenger-backend
// (alongside the Signal and Matrix backends). The launcher's :app plugs into
// it — `MessengerActivity` is just a thin window around `GoogleMessagesApp()`.
// Including the backend as a composite build means we depend on it from source
// without anyone publishing to Maven first.
//
// The dpad-messenger-backend build itself composite-includes ../dpad-messenger
// (the shared UI library), so we get that transitively here — no separate
// includeBuild for dpad-messenger is needed. (The :gmessages module exposes
// `com.offline.dpadmessenger:library` as an api dependency, so :app sees
// DpadMessengerTheme and the chat screens on its classpath.)
//
// Layout assumption: the matrix-app repo (github.com/Offline-DC/matrix-app) is
// cloned NEXT to this repo, i.e. ~/repos/matrix-app alongside
// ~/repos/dumb-down-launcher, giving:
//   ~/repos/matrix-app/dpad-messenger-backend
//   ~/repos/matrix-app/dpad-messenger
includeBuild("../matrix-app/dpad-messenger-backend") {
    dependencySubstitution {
        substitute(module("com.offline.dpadmessenger.backend:gmessages"))
            .using(project(":gmessages"))
    }
}
