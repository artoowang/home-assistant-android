plugins {
    alias(libs.plugins.android.library)
    // This is needed to run AndroidCommonConventionPlugin.kt to configure
    // the project similar to other modules. E.g., Java version.
    alias(libs.plugins.homeassistant.android.common)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.homeassistant.companion.android.glasses"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation("androidx.xr.glimmer:glimmer:1.0.0-alpha03")
    implementation("androidx.xr.projected:projected:1.0.0-alpha03")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
