plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.openwisprflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.openwisprflow"
        // The accessibility input connection (FLAG_INPUT_METHOD_EDITOR) needs API 33.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // A real org.json for JVM unit tests; android.jar only has stubs.
    testImplementation("org.json:json:20240303")
}
