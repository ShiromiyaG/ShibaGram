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
    
    // TDLib: official JNI interface (org.drinkless.tdlib, sources in src/main/java).
    // The native tdjni.dll (+ OpenSSL/zlib) lives in libs/ and must match TdApi.java.
    
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
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
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

// Copy native DLLs from libs/ into the app image after createDistributable
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/ShibaGram").get().asFile
        if (appDir.exists()) {
            project.copy {
                from(project.file("libs")) {
                    include("*.dll")
                }
                into(appDir)
            }
            println("Native DLLs copied to: ${appDir.absolutePath}")
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
