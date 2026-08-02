// Top-level build file. Plugins are declared here (without applying them) so the
// versions are resolved once and shared by every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
