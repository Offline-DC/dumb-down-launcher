// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// `apply false` here loads each plugin onto the buildscript classpath at the
// version pinned in libs.versions.toml WITHOUT applying it to the root
// project. Subprojects then apply via `alias(libs.plugins.*)` without a
// version — if the version is repeated, Gradle errors with "the plugin is
// already on the classpath with an unknown version, so compatibility cannot
// be checked", which is what happened when :gmessages was first added.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}