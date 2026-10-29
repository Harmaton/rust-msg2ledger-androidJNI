plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun firebaseConfig(name: String, environmentName: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(environmentName))
        .orElse("")
        .get()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.harmaton.msg2ledger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.harmaton.msg2ledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\"${firebaseConfig("firebaseApiKey", "FIREBASE_API_KEY")}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_APP_ID",
            "\"${firebaseConfig("firebaseAppId", "FIREBASE_APP_ID")}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_ID",
            "\"${firebaseConfig("firebaseProjectId", "FIREBASE_PROJECT_ID")}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_SENDER_ID",
            "\"${firebaseConfig("firebaseSenderId", "FIREBASE_SENDER_ID")}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_WEB_CLIENT_ID",
            "\"${firebaseConfig("firebaseWebClientId", "FIREBASE_WEB_CLIENT_ID")}\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
