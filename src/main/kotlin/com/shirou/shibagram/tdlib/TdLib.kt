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
            val userDir = System.getProperty("user.dir")
            
            // Detect the app directory from the executable location
            // In jpackage, the EXE is at <appDir>/ShibaGram.exe and DLLs go next to the EXE
            // The app jars are at <appDir>/app/
            val exeDir = try {
                // Get directory of the running process (where ShibaGram.exe lives)
                val pid = ProcessHandle.current().pid()
                val processPath = ProcessHandle.current().info().command().orElse(null)
                if (processPath != null) File(processPath).parentFile?.absolutePath else null
            } catch (e: Exception) {
                null
            }
            
            // Also try to find app dir from class path
            val classPath = TdLib::class.java.protectionDomain?.codeSource?.location?.path ?: ""
            val projectRoot = if (classPath.contains("build")) {
                File(classPath).parentFile?.parentFile?.parentFile?.absolutePath ?: userDir
            } else {
                userDir
            }

            println("TDLib: user.dir is: $userDir")
            println("TDLib: exe dir is: $exeDir")
            println("TDLib: project root is: $projectRoot")
            
            // Build list of possible folders where the DLLs may be
            val searchFolders = listOfNotNull(
                exeDir,                          // next to ShibaGram.exe (packaged)
                "$userDir/libs",                 // dev: project/libs
                userDir,                         // dev: project root
                "$projectRoot/libs",             // dev: from classPath
                projectRoot                      // dev: project root
            ).map { File(it) }.distinctBy { it.absolutePath }

            println("TDLib: Search folders: ${searchFolders.map { "${it.absolutePath} (exists=${it.exists()})" }}")
            
            // Try to load dependencies first (OpenSSL and zlib)
            val deps = listOf("zlib1.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll")
            for (folder in searchFolders) {
                if (folder.exists() && folder.isDirectory) {
                    val found = deps.any { File(folder, it).exists() }
                    if (!found) continue
                    println("TDLib: Loading dependencies from: ${folder.absolutePath}")
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
            val possiblePaths = searchFolders
                .filter { it.exists() && it.isDirectory }
                .map { File(it, "tdjni.dll") }
            
            var loaded = false
            for (file in possiblePaths) {
                println("TDLib: Trying path: ${file.absolutePath} (exists: ${file.exists()})")
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        loaded = true
                        println("TDLib: Successfully loaded from ${file.absolutePath}")
                        break
                    } catch (e: UnsatisfiedLinkError) {
                        println("TDLib: Failed to load from ${file.absolutePath}: ${e.message}")
                    }
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
