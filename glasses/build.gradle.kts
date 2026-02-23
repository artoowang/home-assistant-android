plugins {
    alias(libs.plugins.android.library)

    // This is needed to run AndroidCommonConventionPlugin.kt to configure
    // the project similar to other modules. E.g., Java version.
    alias(libs.plugins.homeassistant.android.common)

    // This is needed to run AndroidComposeConventionPlugin.kt to configure
    // compose related settings similar to other modules.
    alias(libs.plugins.homeassistant.android.compose)
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
    implementation(project(":common"))
    implementation(libs.activity.compose)
    implementation(libs.androidx.concurrent.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.appcompat)
    implementation(libs.community.material.typeface)
    implementation(libs.core.ktx)
    implementation(libs.iconics.compose)
    implementation(libs.material)
    implementation("androidx.xr.glimmer:glimmer:1.0.0-alpha04")
    implementation("androidx.xr.projected:projected:1.0.0-alpha03")
//    implementation(libs.androidx.camera.lifecycle)
//    implementation(libs.androidx.camera.camera2)
    implementation("androidx.camera:camera-core:1.5.2")
    implementation("androidx.camera:camera-camera2:1.5.2")
    implementation("androidx.camera:camera-lifecycle:1.5.2")
    implementation("androidx.camera:camera-view:1.5.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
