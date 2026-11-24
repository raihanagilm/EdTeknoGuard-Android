import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.appdistribution")
}

// Auto-Versioning Dinamis:
// 1. versionCode berbasis waktu menit epoch (dijamin selalu meningkat setiap build baru)
//    Formula: (currentTimeMillis / 60000) - offset basis 2026 agar tetap aman dalam rentang 32-bit integer Android
val autoVersionCode: Int = ((System.currentTimeMillis() - 1735689600000L) / 60000L).toInt()

// 2. versionName berbasis tanggal rilis: 1.3.<YYMMDD>.<HHmm>
val autoVersionName: String = "1.3." + SimpleDateFormat("yyMMdd.HHmm").format(Date())

android {
    namespace = "com.edtekno.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = autoVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "role"

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { stream ->
            localProperties.load(stream)
        }
    }

    val customCredPath = localProperties.getProperty("serviceCredentialsFile")
        ?: localProperties.getProperty("serviceAccountFile")
        ?: localProperties.getProperty("GOOGLE_APPLICATION_CREDENTIALS")
    val serviceAccountPath = when {
        !customCredPath.isNullOrEmpty() -> customCredPath
        file("service-account.json").exists() -> file("service-account.json").absolutePath
        rootProject.file("service-account.json").exists() -> rootProject.file("service-account.json").absolutePath
        else -> ""
    }

    val firebaseTokenFromProps = localProperties.getProperty("FIREBASE_TOKEN")
        ?: (project.findProperty("FIREBASE_TOKEN") as? String)
    if (!firebaseTokenFromProps.isNullOrEmpty() && System.getenv("FIREBASE_TOKEN").isNullOrEmpty()) {
        try {
            val env = System.getenv()
            val cl = env.javaClass
            val field = cl.getDeclaredField("m")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val writableEnv = field.get(env) as MutableMap<String, String>
            writableEnv["FIREBASE_TOKEN"] = firebaseTokenFromProps
        } catch (_: Exception) {
            try {
                val pe = Class.forName("java.lang.ProcessEnvironment")
                val field = pe.getDeclaredField("theCaseInsensitiveEnvironment")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val map = field.get(null) as MutableMap<String, String>
                map["FIREBASE_TOKEN"] = firebaseTokenFromProps
            } catch (_: Exception) {}
        }
    }

    productFlavors {
        create("guard") {
            dimension = "role"
            applicationId = "com.edtekno.guard"
            resValue("string", "app_name", "TeknoGuard")
            buildConfigField("String", "BASE_URL", "\"https://portal.teknoindo.net/login\"")
            buildConfigField("String", "ROLE_TYPE", "\"KARYAWAN\"")
            firebaseAppDistribution {
                appId = "1:750397504359:android:b7b99ed9d6b86ab72ccdc2"
                artifactType = "APK"
                groups = "testers"
                releaseNotes = "Build pengujian TeknoGuard (NOC & Teknisi)"
                if (serviceAccountPath.isNotEmpty()) {
                    serviceCredentialsFile = serviceAccountPath
                }
            }
        }

        create("mytekno") {
            dimension = "role"
            applicationId = "com.edtekno.mytekno"
            resValue("string", "app_name", "MyTekno")
            buildConfigField("String", "BASE_URL", "\"https://portal.teknoindo.net/portal\"")
            buildConfigField("String", "ROLE_TYPE", "\"PELANGGAN\"")
            firebaseAppDistribution {
                appId = "1:750397504359:android:a4f7f0f241d8e32a2ccdc2"
                artifactType = "APK"
                groups = "testers"
                releaseNotes = "Build pengujian MyTekno (Portal Pelanggan)"
                if (serviceAccountPath.isNotEmpty()) {
                    serviceCredentialsFile = serviceAccountPath
                }
            }
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as BaseVariantOutputImpl
            val apkName = if (variant.flavorName == "guard") "TeknoGuard.apk" else "MyTekno.apk"
            output.outputFileName = apkName
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Memastikan task upload Firebase App Distribution selalu memicu kompilasi APK terbaru terlebih dahulu
tasks.matching { it.name.startsWith("appDistributionUpload") }.configureEach {
    val flavorAndBuildType = name.removePrefix("appDistributionUpload")
    if (flavorAndBuildType.isNotEmpty()) {
        dependsOn("assemble$flavorAndBuildType")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Dependencies OkHttp & Coroutines untuk Background Engine
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Firebase Cloud Messaging (FCM) Resmi
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
