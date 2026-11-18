package org.drinkless.tdlib

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * JNA binding to TDLib's JSON interface (td_json_client.h) from the prebuilt tdjson library.
 * Strings are UTF-8 in both directions; returned strings are copied by JNA immediately,
 * which matters because TDLib reuses the buffer on the next call.
 */
internal interface TdJsonLibrary : Library {
    fun td_create_client_id(): Int
    fun td_send(clientId: Int, request: String)
    fun td_receive(timeout: Double): String?
    fun td_execute(request: String): String?
}

object TdJsonNative {
    @Volatile
    private var library: TdJsonLibrary? = null

    internal val lib: TdJsonLibrary
        get() = library ?: throw UnsatisfiedLinkError("tdjson is not loaded: call TdJsonNative.load() first")

    /** Load tdjson from an absolute path (or a library name JNA can resolve). */
    @JvmStatic
    @Synchronized
    fun load(pathOrName: String) {
        if (library != null) return
        library = Native.load(
            pathOrName,
            TdJsonLibrary::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        )
    }
}
