import java.io.File
import java.util.*

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }
    
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation("androidx.documentfile:documentfile:1.0.1")
            // ZXing for QR code generation and scanning
            implementation("com.google.zxing:core:3.5.3")
            implementation("com.journeyapps:zxing-android-embedded:4.3.0")
        }
    }
}

// ===== Version Management =====
val versionPropsFile = File(rootDir, "version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(versionPropsFile.inputStream())
}

val storedYear = versionProps.getProperty("versionYear", "2026").toInt()
val storedMonth = versionProps.getProperty("versionMonth", "7").toInt()
val buildNum = versionProps.getProperty("buildNumber", "1").toInt()

// Configuration time: check if month has changed
val now = Calendar.getInstance()
val currentYear = now.get(Calendar.YEAR)
val currentMonth = now.get(Calendar.MONTH) + 1  // Calendar.MONTH is 0-indexed

// Determine version values based on month change
val shouldResetBuildNumber = currentYear != storedYear || currentMonth != storedMonth
val versionYear = if (shouldResetBuildNumber) currentYear else storedYear
val versionMonth = if (shouldResetBuildNumber) currentMonth else storedMonth
val buildNumber = if (shouldResetBuildNumber) 1 else buildNum

val versionNameString = "$versionYear.$versionMonth.${buildNumber.toString().padStart(5, '0')}"
val versionCodeInt = (versionYear % 100) * 10_000_000 + versionMonth * 100_000 + minOf(buildNumber, 99_999)

// ===== End Version Management =====

android {
    namespace = "com.peersync.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "com.peersync.app"
        minSdk = 30
        targetSdk = 35
        versionCode = versionCodeInt
        versionName = versionNameString
    }
    
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    
    buildFeatures {
        buildConfig = true
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }
}

// ===== Version Bumping =====
fun updateVersionProperties() {
    val now = Calendar.getInstance()
    val currentYear = now.get(Calendar.YEAR)
    val currentMonth = now.get(Calendar.MONTH) + 1
    
    val newProps = Properties()
    newProps.setProperty("versionYear", currentYear.toString())
    newProps.setProperty("versionMonth", currentMonth.toString())
    
    // Same month: increment buildNumber; new month: set to 2 (since this build consumed 00001)
    val nextBuildNumber = if (currentYear == versionYear && currentMonth == versionMonth) {
        buildNumber + 1
    } else {
        2
    }
    
    newProps.setProperty("buildNumber", nextBuildNumber.toString())
    versionPropsFile.outputStream().use { newProps.store(it, null) }
}

// Hook into the actual assemble tasks after they are created
afterEvaluate {
    tasks.findByName("assembleDebug")?.doLast {
        updateVersionProperties()
    }
    tasks.findByName("assembleRelease")?.doLast {
        updateVersionProperties()
    }
}
// ===== End Version Bumping =====
