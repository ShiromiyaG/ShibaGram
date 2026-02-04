import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.0"
    kotlin("plugin.serialization") version "1.9.22"
}

group = "com.shirou.shibagram"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // TDLib (official) - using JSON interface via native library
    // Note: You need to download tdjni.dll from https://github.com/nicegram/nicegram-tdlib-releases/releases
    // or build it yourself and place it in the project root or system PATH
    
    // VLC for video playback
    implementation("uk.co.caprica:vlcj:4.8.2")
    
    // NanoHTTPD for local streaming server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // HTTP Client
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    
    // Image loading
    implementation("io.coil-kt.coil3:coil-compose:3.0.0-alpha03")
    implementation("io.coil-kt.coil3:coil-network-ktor:3.0.0-alpha03")
    
    // Database
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.45.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.45.0")
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
            packageVersion = "1.0.0"
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
}

// Set java.library.path to include project root for tdjni.dll
tasks.withType<JavaExec>().configureEach {
    systemProperty("java.library.path", listOf(
        projectDir.absolutePath,
        "${projectDir.absolutePath}/libs",
        System.getProperty("java.library.path") ?: ""
    ).joinToString(File.pathSeparator))
}
