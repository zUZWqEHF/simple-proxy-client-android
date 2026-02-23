import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val singBoxVersionProvider = providers.gradleProperty("SING_BOX_VERSION").orElse("1.12.22")
val singBoxAutoDownloadProvider = providers.gradleProperty("SING_BOX_AUTO_DOWNLOAD").orElse("true")
val singBoxTargetAbisProvider = providers.gradleProperty("SING_BOX_TARGET_ABIS").orElse("all")

val abiToArch = mapOf(
    "arm64-v8a" to "android-arm64",
    "armeabi-v7a" to "android-arm",
    "x86_64" to "android-amd64",
    "x86" to "android-386",
)

fun parseRequestedAbis(raw: String): Set<String> {
    if (raw.equals("all", ignoreCase = true)) return abiToArch.keys
    return raw.split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

val downloadSingBoxBinaries by tasks.registering {
    group = "build setup"
    description = "Download sing-box Android binaries and stage them into jniLibs/<abi>/libsing-box.so"

    doLast {
        if (!singBoxAutoDownloadProvider.get().toBoolean()) {
            println("SING_BOX_AUTO_DOWNLOAD=false, skip sing-box binary download")
            return@doLast
        }

        val version = singBoxVersionProvider.get()
        val jniRoot = project.layout.projectDirectory.dir("src/main/jniLibs").asFile
        val downloadsDir = project.layout.buildDirectory.dir("singbox/downloads").get().asFile
        downloadsDir.mkdirs()

        val requestedAbis = parseRequestedAbis(singBoxTargetAbisProvider.get())
        val unknownAbis = requestedAbis - abiToArch.keys
        if (unknownAbis.isNotEmpty()) {
            throw GradleException("Unknown ABI(s) in SING_BOX_TARGET_ABIS: ${unknownAbis.joinToString(",")}")
        }

        for ((abi, arch) in abiToArch) {
            if (abi !in requestedAbis) {
                println("Skip sing-box $abi due to SING_BOX_TARGET_ABIS=${singBoxTargetAbisProvider.get()}")
                continue
            }
            val abiDir = jniRoot.resolve(abi)
            abiDir.mkdirs()

            val marker = abiDir.resolve(".version")
            val targetBin = abiDir.resolve("libsing-box.so")
            if (targetBin.exists() && marker.exists() && marker.readText().trim() == version) {
                println("sing-box $abi already prepared at version $version")
                continue
            }

            val fileName = "sing-box-$version-$arch.tar.gz"
            val url = "https://github.com/SagerNet/sing-box/releases/download/v$version/$fileName"
            val archive = downloadsDir.resolve(fileName)

            println("Downloading sing-box $abi from $url")
            URL(url).openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }

            val extractDir = downloadsDir.resolve("extract-$abi")
            if (extractDir.exists()) extractDir.deleteRecursively()
            extractDir.mkdirs()

            copy {
                from(tarTree(resources.gzip(archive)))
                into(extractDir)
            }

            val extractedBin = fileTree(extractDir) {
                include("**/sing-box")
            }.files.firstOrNull() ?: throw GradleException("No sing-box binary found in archive: $fileName")

            extractedBin.copyTo(targetBin, overwrite = true)
            targetBin.setExecutable(true)
            marker.writeText(version)
            println("Prepared sing-box for $abi: ${targetBin.absolutePath}")
        }
    }
}

android {
    namespace = "com.simple.proxyconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simple.proxyconnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.2.2"

        val requestedAbis = parseRequestedAbis(singBoxTargetAbisProvider.get())
        ndk {
            abiFilters += requestedAbis
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadSingBoxBinaries)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // QR code scanning
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
