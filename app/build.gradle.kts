plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-kapt")
}

android {
    namespace = "ch.nutrisnap.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ch.nutrisnap.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Groq API key injected from GitHub Actions secret GROQ_API_KEY
        buildConfigField("String", "GROQ_API_KEY",
            "\"${System.getenv("GROQ_API_KEY") ?: ""}\"")
        buildConfigField("String", "USDA_API_KEY",
            "\"${System.getenv("USDA_API_KEY") ?: ""}\"")
        buildConfigField("String", "NUTRITIONIX_APP_ID",
            "\"${System.getenv("NUTRITIONIX_APP_ID") ?: ""}\"")
        buildConfigField("String", "NUTRITIONIX_API_KEY",
            "\"${System.getenv("NUTRITIONIX_API_KEY") ?: ""}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true; buildConfig = true }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kapt { correctErrorTypes = true }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // ML Kit Barcode
    implementation(libs.mlkit.barcode)
    // Biometric
    implementation(libs.androidx.biometric)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
