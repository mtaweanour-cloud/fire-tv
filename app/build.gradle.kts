plugins {
    id("com.android.application")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.firetv.airplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.firetv.airplay"
        minSdk = 25
        targetSdk = 28
        versionCode = runNumber
        versionName = "1.$runNumber"
        buildConfigField("String", "GITHUB_REPO", "\"mtaweanour-cloud/fire-tv\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "firetvairplay"
            keyAlias = "airplay"
            keyPassword = "firetvairplay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/native-image/**",
                "module-info.class",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":airplay-server"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
