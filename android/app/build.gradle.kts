plugins {
    id("com.android.application")
}

android {
    namespace = "com.upitracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.upitracker"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // personal sideload build: signed with the local debug key
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
