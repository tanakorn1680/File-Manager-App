plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace   = "com.releasemanager"
    compileSdk  = 34

    defaultConfig {
        applicationId = "com.releasemanager"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        dataBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefresh)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // HTTP client for calling the GitHub REST API directly — no server in
    // between, so there's no Vercel-style request body size ceiling. OkHttp
    // streams the request body from disk rather than loading it fully into
    // memory first, which matters for uploading a ~1GB release asset.
    implementation(libs.okhttp)

    // Stores the GitHub Personal Access Token encrypted at rest via the
    // Android Keystore, rather than as plain text in regular
    // SharedPreferences — reduces (does not eliminate) exposure if the
    // device is lost, per what was agreed on: keep the token saved, but
    // encrypted rather than in the clear.
    implementation(libs.androidx.security.crypto)
}
