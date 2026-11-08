plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.arcticfoxapps.peeriche"
    compileSdk = 35
    signingConfigs {
        create("release") {
            val signingPath = System.getenv("PEERICHE_KEYSTORE")
            if (!signingPath.isNullOrBlank()) {
                storeFile = file(signingPath)
                storePassword = System.getenv("PEERICHE_STORE_PASSWORD")
                keyAlias = "peeriche-release"
                keyPassword = System.getenv("PEERICHE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("PEERICHE_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    defaultConfig {
        buildConfigField("String", "BUILD_DATE", "\"2026-11-08\"")
        applicationId = "com.arcticfoxapps.peeriche"
        minSdk = 23
        targetSdk = 35
        versionCode = 24
        versionName = "0.9.1"
        testInstrumentationRunner = "com.arcticfoxapps.peeriche.AuthSmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
