package com.shirou.shibagram.util

import java.io.File

/**
 * Locates bundled native libraries (tdjson, libmpv).
 *
 * Search order: next to the executable (packaged app), then libs/ and the project root (dev runs).
 * Each folder is also searched in its platform subfolder, e.g. libs/linux-x64/, so one checkout
 * can carry the Windows DLLs (in libs/ itself) and the Linux .so files side by side.
 */
object NativeLibs {
    val osName: String = System.getProperty("os.name").lowercase()
    val isWindows = osName.startsWith("windows")
    val isLinux = osName.startsWith("linux")

    /** e.g. "linux-x64", "linux-arm64", "windows-x64". */
    val platformDir: String = run {
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> System.getProperty("os.arch").lowercase()
        }
        val os = when {
            isWindows -> "windows"
            isLinux -> "linux"
            osName.startsWith("mac") -> "macos"
            else -> osName
        }
        "$os-$arch"
    }

    /** Platform file name of a shared library: "tdjson" -> tdjson.dll / libtdjson.so / libtdjson.dylib. */
    fun mapLibraryName(name: String): String = System.mapLibraryName(name)

    val searchDirs: List<File> by lazy {
        val userDir = System.getProperty("user.dir")
        val exeDir = runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let { File(it).parentFile }
        }.getOrNull()
        // build/classes/kotlin/main -> project root (dev runs)
        val codeDir = runCatching {
            File(NativeLibs::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val projectRoot = codeDir?.takeIf { it.path.contains("build") }?.parentFile?.parentFile?.parentFile?.parentFile

        listOfNotNull(exeDir, File(userDir, "libs"), File(userDir), projectRoot?.let { File(it, "libs") }, projectRoot)
            .flatMap { listOf(File(it, platformDir), it) }
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    /** First existing file named [fileName] in [searchDirs], or null. */
    fun find(fileName: String): File? = searchDirs.map { File(it, fileName) }.firstOrNull { it.isFile }
}
