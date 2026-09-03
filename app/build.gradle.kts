plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.obsp.hostlookup"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.obsp.hostlookup"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // JVM half of rustls-platform-verifier, required by mhost's HTTPS WHOIS client on Android.
    implementation(files("libs/rustls-platform-verifier-0.1.1.aar"))
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.5.0-alpha27")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("bash", "native/build-android.sh")
    inputs.files(fileTree("../native/src"), file("../native/Cargo.toml"), file("../native/build-android.sh"))
    outputs.dir("src/main/jniLibs")
}

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}
