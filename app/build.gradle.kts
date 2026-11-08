import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Optional values from local.properties or environment variables (CI). Never committed.
//   MAPS_API_KEY=...       Only for the interactive map (the Maps SDK reads its key at build time). Everything
//                          else uses the key entered in the app's Settings.
//   VOLTA_STORE_FILE=/path/to/release.jks, VOLTA_STORE_PASSWORD, VOLTA_KEY_ALIAS, VOLTA_KEY_PASSWORD
//                          Release signing; without them the release APK is unsigned.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String): String? = (localProps.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }
val mapSdkKey = secret("MAPS_API_KEY").orEmpty()

android {
    namespace = "dev.irshadm.volta"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.irshadm.volta.oss"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        manifestPlaceholders["MAPS_API_KEY"] = mapSdkKey
        buildConfigField("boolean", "HAS_MAP_KEY", mapSdkKey.isNotEmpty().toString())
    }

    signingConfigs {
        secret("VOLTA_STORE_FILE")?.let { store ->
            create("release") {
                storeFile = file(store)
                storePassword = secret("VOLTA_STORE_PASSWORD")
                keyAlias = secret("VOLTA_KEY_ALIAS")
                keyPassword = secret("VOLTA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:planner"))
    implementation(project(":core:ismart"))
    implementation(project(":core:maps"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:onboarding"))

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work)
    implementation(libs.androidx.fragment)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.mlkit.genai.prompt)
    // Android Auto (templates drawn by the car's head unit)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
