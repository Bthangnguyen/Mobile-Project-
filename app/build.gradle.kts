import java.util.Properties

plugins {
    // AGP 9 đã tích hợp sẵn Kotlin, không cần plugin org.jetbrains.kotlin.android.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val facebookAppId = System.getenv("FACEBOOK_APP_ID")
    ?: localProperties.getProperty("FACEBOOK_APP_ID")
    ?: "SOCIAL_AGENT_FB_APP_ID"

val facebookClientToken = System.getenv("FACEBOOK_CLIENT_TOKEN")
    ?: localProperties.getProperty("FACEBOOK_CLIENT_TOKEN")
    ?: "SOCIAL_AGENT_CLIENT_TOKEN"

val linkedinClientId = System.getenv("LINKEDIN_CLIENT_ID")
    ?: localProperties.getProperty("LINKEDIN_CLIENT_ID")
    ?: "SOCIAL_AGENT_LINKEDIN_CLIENT_ID"

android {
    namespace = "com.lttbdd.socialagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lttbdd.socialagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["facebookAppId"] = facebookAppId
        manifestPlaceholders["facebookClientToken"] = facebookClientToken
        buildConfigField("String", "FACEBOOK_APP_ID", "\"$facebookAppId\"")
        buildConfigField("String", "FACEBOOK_CLIENT_TOKEN", "\"$facebookClientToken\"")
        buildConfigField("String", "LINKEDIN_CLIENT_ID", "\"$linkedinClientId\"")

        resValue("string", "facebook_app_id", facebookAppId)
        resValue("string", "facebook_client_token", facebookClientToken)
        resValue("string", "fb_login_protocol_scheme", "fb$facebookAppId")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        // Sinh locales_config để người dùng đổi VI/EN trong Settings hệ thống (Android 13+).
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.facebook.login)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
