plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.foldmail.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.foldmail.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    // Fixed debug key so the SHA-1 registered in Google Cloud stays the same for every CI build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "foldmail"
            keyAlias = "foldmail"
            keyPassword = "foldmail"
        }
    }
    buildTypes {
        release {
            // Optimised build (R8 + shrink) is much smoother than debug; signed with the fixed key so
            // it installs over the previous builds and keeps the registered SHA-1.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/*.md")
            pickFirsts += listOf("META-INF/mailcap", "META-INF/mimetypes.default", "META-INF/javamail.*", "META-INF/mailcap.default")
        }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.core)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
