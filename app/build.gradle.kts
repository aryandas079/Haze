plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.haze"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.haze"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // Strip C++ debug symbols and optimize for size
                cppFlags("-std=c++17 -O3 -DNDEBUG -fvisibility=hidden")
                arguments("-DCMAKE_BUILD_TYPE=Release")
            }
        }
        ndk {
            // Keep ONLY real phone architectures (drops x86 & x86_64 emulator bloat)
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // Uses debug key so you can install it directly
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            // Tells Android not to compress .so files twice, saving disk space after install
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        noCompress += listOf("dat", "pak", "bin", "v8_context_snapshot")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.guava:guava:33.2.1-android")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val prepareNativeChromiumLibs by tasks.registering {
    description = "Reassembles and decompresses Chromium native libraries from GitHub-safe chunks in app/native_blobs"
    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a")
        for (abi in abis) {
            val outSo = file("src/main/jniLibs/$abi/libcontent_shell_content_view.so")
            if (outSo.exists() && outSo.length() > 10_000_000L) continue

            val blobDir = file("native_blobs/$abi")
            val parts = blobDir.listFiles { _, name ->
                name.startsWith("libcontent_shell_content_view.so.gz.part")
            }?.sortedBy { it.name } ?: emptyList()

            if (parts.isNotEmpty()) {
                outSo.parentFile.mkdirs()
                val partStreams = java.util.Collections.enumeration(parts.map { it.inputStream() })
                java.util.zip.GZIPInputStream(java.io.SequenceInputStream(partStreams)).use { gis ->
                    outSo.outputStream().use { fos ->
                        gis.copyTo(fos, bufferSize = 64 * 1024)
                    }
                }
                println("Unpacked ${outSo.relativeTo(projectDir)} (${outSo.length() / (1024 * 1024)} MB)")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareNativeChromiumLibs)
}