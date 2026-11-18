package com.shirou.shibagram.tdlib

import com.shirou.shibagram.util.Log
import com.shirou.shibagram.util.NativeLibs
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Loads the TDLib JNI library (tdjni.dll + its OpenSSL/zlib dependencies) and creates clients.
 */
object TdLib {
    @Volatile
    private var isInitialized = false

    private val dependencies = listOf("zlib1.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll")

    /**
     * Load the native library once. Throws [UnsatisfiedLinkError] if tdjni.dll can't be loaded.
     */
    @Synchronized
    fun init() {
        if (isInitialized) return
        Log.d("TDLib: native search dirs: ${NativeLibs.searchDirs.map { it.absolutePath }}")

        // Load the dependencies from the same folder as tdjni.dll so Windows doesn't pick up
        // a different OpenSSL from PATH.
        val tdjni = NativeLibs.find("tdjni.dll")
            ?: throw UnsatisfiedLinkError("Could not find tdjni.dll. Please ensure it and its dependencies are in the libs/ folder.")
        for (dep in dependencies) {
            val depFile = tdjni.resolveSibling(dep)
            if (depFile.isFile) {
                try {
                    System.load(depFile.absolutePath)
                } catch (e: UnsatisfiedLinkError) {
                    Log.w("TDLib: could not load $dep: ${e.message}")
                }
            }
        }
        System.load(tdjni.absolutePath)
        Log.i("TDLib: loaded ${tdjni.absolutePath}")

        // 1 = errors only (TDLib's default, 3+, is extremely verbose)
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(if (Log.debugEnabled) 2 else 1))
        } catch (e: Exception) {
            Log.w("TDLib: could not set log verbosity: ${e.message}")
        }
        isInitialized = true
    }

    /**
     * Create a new TDLib client.
     */
    @Synchronized
    fun create(
        updateHandler: Client.ResultHandler,
        updateExceptionHandler: Client.ExceptionHandler? = null,
        defaultExceptionHandler: Client.ExceptionHandler? = null
    ): Client {
        init()
        return Client.create(updateHandler, updateExceptionHandler, defaultExceptionHandler)
    }
}
