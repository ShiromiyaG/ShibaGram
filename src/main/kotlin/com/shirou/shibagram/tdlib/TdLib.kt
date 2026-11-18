package com.shirou.shibagram.tdlib

import com.shirou.shibagram.util.Log
import com.shirou.shibagram.util.NativeLibs
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.drinkless.tdlib.TdJsonNative

/**
 * Loads TDLib and creates clients.
 *
 * Desktop uses the prebuilt tdjson library from prebuilt-tdlib (tdjson.dll / libtdjson.so,
 * self-contained: OpenSSL and zlib are linked in). Its TDLib commit must match TdApi.java.
 */
object TdLib {
    @Volatile
    private var isInitialized = false

    /** Load the native library once. Throws [UnsatisfiedLinkError] if it can't be loaded. */
    @Synchronized
    fun init() {
        if (isInitialized) return
        Log.d("TDLib: native search dirs: ${NativeLibs.searchDirs.map { it.absolutePath }}")

        val fileName = NativeLibs.mapLibraryName("tdjson")
        val bundled = NativeLibs.find(fileName)
        // Fall back to a system-wide install (e.g. a distro package) when nothing is bundled
        TdJsonNative.load(bundled?.absolutePath ?: "tdjson")
        Log.i("TDLib: loaded ${bundled?.absolutePath ?: "tdjson (system)"}")

        // 1 = errors only (TDLib's default, 3+, is extremely verbose)
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(if (Log.debugEnabled) 2 else 1))
        } catch (e: Exception) {
            Log.w("TDLib: could not set log verbosity: ${e.message}")
        }
        isInitialized = true
    }

    /** Create a new TDLib client. */
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
