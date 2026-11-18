import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.0"
}

group = "com.shirou.shibagram"
version = "1.0.0"

// --------------- Load .env file ---------------
fun loadEnv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) return emptyMap()
    return envFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .associate {
            val (key, value) = it.split("=", limit = 2)
            key.trim() to value.trim()
        }
}

val env = loadEnv()
val telegramApiId: String = env["TELEGRAM_API_ID"] ?: System.getenv("TELEGRAM_API_ID") ?: "0"
val telegramApiHash: String = env["TELEGRAM_API_HASH"] ?: System.getenv("TELEGRAM_API_HASH") ?: "0"

// --------------- Generate BuildConfig.kt ---------------
val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildConfig/kotlin/main")
    // Declared inputs: without them Gradle treated the task as up-to-date and kept stale values
    // after .env or the version changed.
    inputs.property("apiId", telegramApiId)
    inputs.property("apiHash", telegramApiHash)
    inputs.property("version", project.version.toString())
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("com/shirou/shibagram")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText(
            """
            |package com.shirou.shibagram
            |
            |object BuildConfig {
            |    const val TELEGRAM_API_ID: Int = $telegramApiId
            |    const val TELEGRAM_API_HASH: String = "$telegramApiHash"
            |    const val VERSION: String = "${project.version}"
            |}
            """.trimMargin()
        )
    }
}

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sources/buildConfig/kotlin/main"))
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildConfig)
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    
    // TDLib: TdApi.java (src/main/java) + a tdjson-backed Client (src/main/kotlin/org/drinkless/tdlib).
    // Natives from prebuilt-tdlib: libs/tdjson.dll, libs/linux-x64|linux-arm64/libtdjson.so.
    // Their TDLib commit must match TdApi.java (see README).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // TdApi.java uses @IntDef/@Nullable (already a runtime dependency of Compose)
    compileOnly("androidx.annotation:annotation-jvm:1.8.0")
    
    // VLC for video playback
    implementation("uk.co.caprica:vlcj:4.8.2")
    
    // JNA (used by vlcj and MPV window embedding)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    
    // NanoHTTPD for local streaming server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // Database
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.45.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.45.0")
    
    // Preferences
    implementation("com.russhwolf:multiplatform-settings-no-arg:1.1.1")
    
    // QR Code generation
    implementation("io.github.g0dkar:qrcode-kotlin-jvm:4.1.0")
    
    // Testing
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.shirou.shibagram.MainKt"
        
        nativeDistributions {
            // Each format is built on its own OS (Msi/Exe on Windows, Deb on Linux)
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "ShibaGram"
            packageVersion = project.version.toString()
            description = "Telegram Media Client for Desktop"
            vendor = "Shirou"
            includeAllModules = true
            modules("java.sql")
            
            windows {
                menuGroup = "ShibaGram"
                upgradeUuid = "61DAB35E-17CB-43B0-81D5-B30D1AA6E2C3"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}

// Copy the bundled native libraries for the current OS into the app image after createDistributable
val nativeLibsDir: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch").lowercase()
    }
    if (os.startsWith("windows")) "libs" else "libs/linux-$arch"
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    // jpackage marks the launcher read-only, which made every rebuild fail to delete the old image
    doFirst {
        layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
            .walkTopDown().filter { it.isFile }.forEach { it.setWritable(true) }
    }
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/ShibaGram").get().asFile
        // Linux app images keep the launcher in bin/, which is where NativeLibs looks first
        val targetDir = if (appDir.resolve("bin").isDirectory) appDir.resolve("bin") else appDir
        if (appDir.exists()) {
            project.copy {
                from(project.file(nativeLibsDir)) {
                    include("*.dll", "*.so")
                }
                from(project.file("libs")) { include("LICENSE*") }
                into(targetDir)
            }
            println("Native libraries copied to: ${targetDir.absolutePath}")
            // Clear read-only flags now too, so the next build can replace the image
            appDir.walkTopDown().filter { it.isFile }.forEach { it.setWritable(true) }
        }
    }
}

// Generate a portable app-image folder when running packageExe
tasks.matching { it.name == "packageExe" }.configureEach {
    dependsOn("createDistributable")
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/ShibaGram").get().asFile
        val portableDir = layout.buildDirectory.dir("compose/portable-exe/ShibaGram").get().asFile
        if (appDir.exists()) {
            portableDir.parentFile.mkdirs()
            project.copy {
                from(appDir)
                into(portableDir)
            }
            println("Portable app image generated at: ${portableDir.absolutePath}")
            println("Portable EXE: ${portableDir.resolve("ShibaGram.exe").absolutePath}")
        } else {
            println("Portable app image not found at: ${appDir.absolutePath}")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.RequiresOptIn")
        allWarningsAsErrors.set(false)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        if (project.hasProperty("release")) {
            freeCompilerArgs.add("-Xno-param-assertions")
            freeCompilerArgs.add("-Xno-call-assertions")
        }
    }
}

// Set java.library.path to include project root for tdjni.dll
tasks.withType<JavaExec>().configureEach {
    systemProperty("java.library.path", listOf(
        projectDir.absolutePath,
        "${projectDir.absolutePath}/libs",
        System.getProperty("java.library.path") ?: ""
    ).joinToString(File.pathSeparator))
}

// Manual end-to-end streaming check against the real Telegram session (see StreamingSmokeTest.kt)
tasks.register<JavaExec>("streamingSmokeTest") {
    group = "verification"
    description = "Streams an MKV from Telegram into mpv headless and reports start/seek times"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.shirou.shibagram.tools.StreamingSmokeTestKt")
    args(
        (project.findProperty("mpv") as String?) ?: "mpv.com",
        (project.findProperty("channelFilter") as String?) ?: ""
    )
}
