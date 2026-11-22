plugins {
    // Root plugins; applied by submodules as needed
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinComposeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
}
