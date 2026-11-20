plugins {
    id("com.android.application")
}

val appVersionName = "0.3.2"
val appVersionCode = 7

base {
    archivesName.set("tengok-v$appVersionName")
}

android {
    namespace = "com.vanipy.tengok"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vanipy.tengok"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("TENGOK_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("TENGOK_STORE_PASSWORD")
                keyAlias = System.getenv("TENGOK_KEY_ALIAS")
                keyPassword = System.getenv("TENGOK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("TENGOK_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Media3 Transformer drives the phone's own hardware codecs for the
    // audio/video conversions (Apache-2.0). 1.9.4 is the newest stable whose
    // AAR metadata still allows compileSdk 35 (1.10+ requires 36); checked
    // against Google's Maven repo.
    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    testImplementation("junit:junit:4.13.2")
}
