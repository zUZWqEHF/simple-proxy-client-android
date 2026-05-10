import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val singBoxVersionProvider = providers.gradleProperty("SING_BOX_VERSION").orElse("1.13.11")
val singBoxAutoDownloadProvider = providers.gradleProperty("SING_BOX_AUTO_DOWNLOAD").orElse("true")
val singBoxTargetAbisProvider = providers.gradleProperty("SING_BOX_TARGET_ABIS").orElse("all")
val singBoxBuildFromSourceProvider = providers.gradleProperty("SING_BOX_BUILD_FROM_SOURCE").orElse("true")
val singBoxBuildTagsProvider = providers.gradleProperty("SING_BOX_BUILD_TAGS")
    .orElse("with_gvisor,with_quic,with_utls,with_clash_api")

// Each pair: (GOOS, GOARCH) used to build sing-box for the matching Android ABI.
// arm64-v8a uses GOOS=android (pure-Go support), the others use GOOS=linux because
// android/arm, android/amd64, android/386 require cgo+NDK which we want to avoid.
// Linux ELF binaries run fine on Android (same kernel ABI).
data class GoTarget(val goos: String, val goarch: String, val goarm: String? = null)

val abiToGoTarget = mapOf(
    "arm64-v8a" to GoTarget("android", "arm64"),
    "armeabi-v7a" to GoTarget("linux", "arm", goarm = "7"),
    "x86_64" to GoTarget("linux", "amd64"),
    "x86" to GoTarget("linux", "386"),
)

// Used only when falling back to upstream pre-built downloads (SING_BOX_BUILD_FROM_SOURCE=false).
// WARNING: upstream binaries are NOT 16 KB page-aligned; this path is for dev-only quick iteration
// and MUST NOT be used for Play Store releases.
val abiToArch = mapOf(
    "arm64-v8a" to "android-arm64",
    "armeabi-v7a" to "android-arm",
    "x86_64" to "android-amd64",
    "x86" to "android-386",
)

fun parseRequestedAbis(raw: String): Set<String> {
    if (raw.equals("all", ignoreCase = true)) return abiToGoTarget.keys
    return raw.split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

/**
 * Mark sing-box binaries with a version marker that includes the build mode + alignment
 * tag, so switching between download/build-from-source forces a rebuild.
 */
fun versionMarker(version: String, fromSource: Boolean): String =
    if (fromSource) "$version-src-pgsz16k" else "$version-upstream-4k"

val downloadSingBoxBinaries by tasks.registering {
    group = "build setup"
    description = "Stage sing-box binaries into jniLibs/<abi>/libsing-box.so " +
            "(builds from source with 16 KB page alignment by default; can fall back to upstream downloads)"

    doLast {
        if (!singBoxAutoDownloadProvider.get().toBoolean()) {
            println("SING_BOX_AUTO_DOWNLOAD=false, skip sing-box binary preparation")
            return@doLast
        }

        val version = singBoxVersionProvider.get()
        val fromSource = singBoxBuildFromSourceProvider.get().toBoolean()
        val jniRoot = project.layout.projectDirectory.dir("src/main/jniLibs").asFile
        val workRoot = project.layout.buildDirectory.dir("singbox").get().asFile
        workRoot.mkdirs()

        val requestedAbis = parseRequestedAbis(singBoxTargetAbisProvider.get())
        val unknownAbis = requestedAbis - abiToGoTarget.keys
        if (unknownAbis.isNotEmpty()) {
            throw GradleException("Unknown ABI(s) in SING_BOX_TARGET_ABIS: ${unknownAbis.joinToString(",")}")
        }

        val marker = versionMarker(version, fromSource)

        // Decide once whether all requested ABIs are already up-to-date.
        val allUpToDate = requestedAbis.all { abi ->
            val abiDir = jniRoot.resolve(abi)
            val markerFile = abiDir.resolve(".version")
            val bin = abiDir.resolve("libsing-box.so")
            bin.exists() && markerFile.exists() && markerFile.readText().trim() == marker
        }
        if (allUpToDate) {
            println("sing-box already prepared at $marker for: ${requestedAbis.joinToString(",")}")
            return@doLast
        }

        if (fromSource) {
            buildSingBoxFromSource(version, marker, requestedAbis, jniRoot, workRoot)
        } else {
            downloadSingBoxFromUpstream(version, marker, requestedAbis, jniRoot, workRoot)
        }
    }
}

fun ensureSingBoxSource(version: String, workRoot: File): File {
    val srcDir = File(workRoot, "src-v$version")
    val readyMarker = File(srcDir, ".ready")
    if (readyMarker.exists()) return srcDir

    if (srcDir.exists()) srcDir.deleteRecursively()
    srcDir.mkdirs()

    // Use codeload.github.com tarball (small, no full git history needed).
    val srcTarball = File(workRoot, "sing-box-v$version-src.tar.gz")
    if (!srcTarball.exists()) {
        val url = "https://codeload.github.com/SagerNet/sing-box/tar.gz/refs/tags/v$version"
        println("Fetching sing-box source: $url")
        URL(url).openStream().use { input ->
            srcTarball.outputStream().use { output -> input.copyTo(output) }
        }
    }

    println("Extracting sing-box source into $srcDir")
    val extractTmp = File(workRoot, "src-v$version-extract")
    if (extractTmp.exists()) extractTmp.deleteRecursively()
    extractTmp.mkdirs()
    copy {
        from(tarTree(resources.gzip(srcTarball)))
        into(extractTmp)
    }
    // The tarball expands into a directory named "sing-box-<version>".
    val expanded = extractTmp.listFiles()?.firstOrNull { it.isDirectory }
        ?: throw GradleException("Failed to locate extracted sing-box source dir under $extractTmp")
    expanded.copyRecursively(srcDir, overwrite = true)
    extractTmp.deleteRecursively()
    readyMarker.writeText(version)
    return srcDir
}

fun resolveGoBinary(): String {
    val candidates = sequenceOf(
        System.getenv("GO"),
        "go",
        "/opt/homebrew/bin/go",
        "/usr/local/go/bin/go",
        "/usr/bin/go",
    ).filterNotNull().distinct()
    for (c in candidates) {
        val p = ProcessBuilder(c, "version").redirectErrorStream(true).start()
        if (p.waitFor() == 0) {
            val firstLine = p.inputStream.bufferedReader().readLine() ?: ""
            println("Using Go: $c ($firstLine)")
            return c
        }
    }
    throw GradleException(
        "Go toolchain not found. Install Go (>=1.23) and either put it on PATH or set the GO env var. " +
                "Alternatively pass -PSING_BOX_BUILD_FROM_SOURCE=false to use upstream pre-built binaries " +
                "(WARNING: not 16 KB aligned, do not use for Play releases)."
    )
}

fun buildSingBoxFromSource(
    version: String,
    marker: String,
    requestedAbis: Set<String>,
    jniRoot: File,
    workRoot: File,
) {
    val srcDir = ensureSingBoxSource(version, workRoot)
    val goBin = resolveGoBinary()
    val tags = singBoxBuildTagsProvider.get()
    val ldflags = "-s -w -R 16384"

    for (abi in requestedAbis) {
        val target = abiToGoTarget[abi] ?: continue
        val abiDir = jniRoot.resolve(abi)
        abiDir.mkdirs()
        val markerFile = abiDir.resolve(".version")
        val targetBin = abiDir.resolve("libsing-box.so")
        if (targetBin.exists() && markerFile.exists() && markerFile.readText().trim() == marker) {
            println("sing-box $abi already at $marker, skipping")
            continue
        }

        val tmpOut = File(workRoot, "out-${target.goos}-${target.goarch}-libsing-box.so")
        if (tmpOut.exists()) tmpOut.delete()

        println("Building sing-box for $abi (${target.goos}/${target.goarch}) …")
        val builder = ProcessBuilder(
            goBin, "build",
            "-trimpath",
            "-tags", tags,
            "-ldflags", ldflags,
            "-o", tmpOut.absolutePath,
            "./cmd/sing-box",
        )
            .directory(srcDir)
            .redirectErrorStream(true)

        val env = builder.environment()
        env["GOOS"] = target.goos
        env["GOARCH"] = target.goarch
        env["CGO_ENABLED"] = "0"
        target.goarm?.let { env["GOARM"] = it }
        // Use a per-project Go cache to avoid clobbering host caches.
        env.putIfAbsent("GOPATH", File(workRoot, "gopath").absolutePath)
        env.putIfAbsent("GOCACHE", File(workRoot, "gocache").absolutePath)

        val proc = builder.start()
        val output = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        if (rc != 0) {
            throw GradleException("go build failed for $abi (rc=$rc):\n$output")
        }
        if (!tmpOut.exists()) {
            throw GradleException("go build produced no output for $abi:\n$output")
        }
        tmpOut.copyTo(targetBin, overwrite = true)
        targetBin.setExecutable(true)
        markerFile.writeText(marker)
        println("Built sing-box $abi → ${targetBin.absolutePath} (${targetBin.length()} bytes)")
    }
}

fun downloadSingBoxFromUpstream(
    version: String,
    marker: String,
    requestedAbis: Set<String>,
    jniRoot: File,
    workRoot: File,
) {
    val downloadsDir = File(workRoot, "downloads").apply { mkdirs() }
    for (abi in requestedAbis) {
        val arch = abiToArch[abi] ?: continue
        val abiDir = jniRoot.resolve(abi)
        abiDir.mkdirs()
        val markerFile = abiDir.resolve(".version")
        val targetBin = abiDir.resolve("libsing-box.so")
        if (targetBin.exists() && markerFile.exists() && markerFile.readText().trim() == marker) {
            println("sing-box $abi already at $marker, skipping")
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
        markerFile.writeText(marker)
        println("Prepared sing-box for $abi: ${targetBin.absolutePath} (upstream, NOT 16 KB aligned)")
    }
}

android {
    namespace = "com.simple.proxyconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simple.proxyconnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.2.4"

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

// Optional local-only build hook (e.g. release signing). The file is
// gitignored and absent in fresh checkouts; .kts variant takes priority.
listOf("signing.gradle.kts", "signing.gradle")
    .firstNotNullOfOrNull { name -> file(name).takeIf { it.exists() } }
    ?.let { apply(from = it) }

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
