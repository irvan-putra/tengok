plugins {
    id("com.android.application")
}

val appVersionName = "0.3.4"
val appVersionCode = 9

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
    testImplementation("junit:junit:4.13.2")
}
