package com.shirou.shibagram.tdlib

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TDLib native library wrapper using the official object-based JNI interface.
 * Uses tdjni.dll with TdApi classes for serialization.
 */
object TdLib {
    private var client: Client? = null
    private var isInitialized = false
    
    /**
     * Initialize TDLib native library.
     */
    fun init() {
        if (isInitialized) return
        
        try {
            // Get the class location to find the project root
            val classPath = TdLib::class.java.protectionDomain?.codeSource?.location?.path ?: ""
            val projectRoot = if (classPath.contains("build")) {
                File(classPath).parentFile?.parentFile?.parentFile?.absolutePath ?: System.getProperty("user.dir")
            } else {
                System.getProperty("user.dir")
            }
            
            println("TDLib: Project root detected as: $projectRoot")
            println("TDLib: user.dir is: ${System.getProperty("user.dir")}")
            
            // Build list of possible paths for the libs folder (dependencies like libcrypto, libssl, zlib)
            val libsFolders = listOf(
                "$projectRoot/libs",
                "${System.getProperty("user.dir")}/libs",
                "F:/apkmod/echogram_decomp/ShibaGramTDLIB/libs"
            )
            
            // Try to load dependencies first (OpenSSL and zlib)
            for (libsFolder in libsFolders) {
                val folder = File(libsFolder)
                if (folder.exists() && folder.isDirectory) {
                    println("TDLib: Found libs folder at: ${folder.absolutePath}")
                    
                    // Load dependencies in order
                    val deps = listOf("zlib1.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll")
                    for (dep in deps) {
                        val depFile = File(folder, dep)
                        if (depFile.exists()) {
                            try {
                                System.load(depFile.absolutePath)
                                println("TDLib: Loaded dependency: ${depFile.absolutePath}")
                            } catch (e: UnsatisfiedLinkError) {
                                println("TDLib: Could not load dependency $dep: ${e.message}")
                            }
                        }
                    }
                    break
                }
            }
            
            // Build list of possible paths for tdjni.dll
            val possiblePaths = mutableListOf<String>()
            
            // Add paths relative to project root
            possiblePaths.add("$projectRoot/libs/tdjni.dll")
            possiblePaths.add("$projectRoot/tdjni.dll")
            
            // Add paths relative to user.dir
            val userDir = System.getProperty("user.dir")
            possiblePaths.add("$userDir/libs/tdjni.dll")
            possiblePaths.add("$userDir/tdjni.dll")
            
            // Also try absolute paths
            possiblePaths.add("F:/apkmod/echogram_decomp/ShibaGramTDLIB/libs/tdjni.dll")
            
            var loaded = false
            // Try explicit paths only (avoid system path picking TDLight)
            for (path in possiblePaths) {
                try {
                    val file = File(path)
                    println("TDLib: Trying path: ${file.absolutePath} (exists: ${file.exists()})")
                    if (file.exists()) {
                        System.load(file.absolutePath)
                        loaded = true
                        println("TDLib: Successfully loaded from ${file.absolutePath}")
                        break
                    }
                } catch (e: UnsatisfiedLinkError) {
                    println("TDLib: Failed to load from $path: ${e.message}")
                }
            }
            
            if (!loaded) {
                throw UnsatisfiedLinkError(
                    "Could not load tdjni library. " +
                    "Please ensure tdjni.dll and dependencies are in the libs/ folder."
                )
            }
            
            // Set log verbosity level to reduce excessive logging
            // Level 0 = fatal errors only
            // Level 1 = errors
            // Level 2 = warnings + errors
            // Level 3 = info (default - very verbose)
            // Level 4+ = debug (extremely verbose)
            try {
                Client.execute(TdApi.SetLogVerbosityLevel(1))
                println("TDLib: Log verbosity set to 1 (errors only)")
            } catch (e: Exception) {
                println("TDLib: Warning - could not set log verbosity: ${e.message}")
            }
            
            isInitialized = true
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize TDLib: ${e.message}", e)
        }
    }
    
    /**
     * Create a new TDLib client.
     * @param updateHandler Handler for incoming updates
     * @param exceptionHandler Handler for exceptions in the update handler
     */
    fun create(
        updateHandler: Client.ResultHandler,
        updateExceptionHandler: Client.ExceptionHandler? = null,
        defaultExceptionHandler: Client.ExceptionHandler? = null
    ): Client {
        init()
        client = Client.create(updateHandler, updateExceptionHandler, defaultExceptionHandler)
        println("TDLib: Created client")
        return client!!
    }
    
    /**
     * Send a request and wait for response synchronously.
     */
    fun sendSync(function: TdApi.Function<*>, timeoutSeconds: Long = 30): TdApi.Object? {
        val client = this.client ?: throw IllegalStateException("Client not created")
        val latch = CountDownLatch(1)
        val result = AtomicReference<TdApi.Object?>(null)
        
        client.send(function, { obj ->
            result.set(obj)
            latch.countDown()
        }, null)
        
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return result.get()
    }
    
    /**
     * Send a request asynchronously.
     */
    fun send(function: TdApi.Function<*>, handler: Client.ResultHandler?) {
        val client = this.client ?: throw IllegalStateException("Client not created")
        client.send(function, handler)
    }
    
    /**
     * Execute a synchronous TDLib request.
     */
    fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T? {
        return try {
            Client.execute(function)
        } catch (e: Client.ExecutionException) {
            println("TDLib: Execute error: ${e.message}")
            null
        }
    }
    
    /**
     * Get the current client.
     */
    fun getClient(): Client? = client
    
    /**
     * Check if client is initialized.
     */
    fun isClientCreated(): Boolean = client != null
}
