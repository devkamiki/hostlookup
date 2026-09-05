plugins {
    id("com.android.library")
}

android {
    namespace = "org.rustls.platformverifier"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Never enable upstream's mock certificate trust store in the app.
        buildConfigField("boolean", "TEST", "false")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
