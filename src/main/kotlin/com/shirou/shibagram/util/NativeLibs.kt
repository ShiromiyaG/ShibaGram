package com.shirou.shibagram.util

import java.io.File

/**
 * Folders where bundled native DLLs (tdjni, libmpv, OpenSSL, zlib) may live, in priority order:
 * next to ShibaGram.exe in the packaged app, then libs/ and the project root when run from Gradle.
 * Shared by TDLib and mpv so both resolve DLLs the same way regardless of the working directory.
 */
object NativeLibs {
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
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    /** First existing file named [name] in [searchDirs], or null. */
    fun find(name: String): File? = searchDirs.map { File(it, name) }.firstOrNull { it.isFile }
}
