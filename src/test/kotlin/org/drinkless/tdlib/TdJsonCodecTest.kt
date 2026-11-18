package org.drinkless.tdlib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdJsonCodecTest {

    @Test
    fun encodesRequestWithTypeSnakeCaseFieldsAndExtra() {
        val request = TdApi.SearchChatMessages().apply {
            chatId = -1001234567890123L
            query = "naruto"
            limit = 50
            fromMessageId = 0L
            filter = TdApi.SearchMessagesFilterVideo()
        }
        val json = Json.parseToJsonElement(TdJsonCodec.encode(request, 42L)).jsonObject
        assertEquals("searchChatMessages", json["@type"]!!.jsonPrimitive.content)
        assertEquals("-1001234567890123", json["chat_id"]!!.jsonPrimitive.content)
        assertEquals("50", json["limit"]!!.jsonPrimitive.content)
        assertEquals("searchMessagesFilterVideo", json["filter"]!!.jsonObject["@type"]!!.jsonPrimitive.content)
        assertEquals("42", json["@extra"]!!.jsonPrimitive.content)
    }

    @Test
    fun decodesNestedObjectsInt64StringsAndBase64() {
        val json = """
            {"@type":"file","id":7,"size":"123456789012","expected_size":"123456789012",
             "local":{"@type":"localFile","path":"C:\\v.mp4","can_be_downloaded":true,"can_be_deleted":true,
                      "is_downloading_active":false,"is_downloading_completed":true,"download_offset":"0",
                      "downloaded_prefix_size":"123456789012","downloaded_size":"123456789012"},
             "remote":{"@type":"remoteFile","id":"abc","unique_id":"u","is_uploading_active":false,
                       "is_uploading_completed":true,"uploaded_size":"0"},
             "@client_id":1,"@extra":5}
        """.trimIndent()
        val file = assertIs<TdApi.File>(TdJsonCodec.decode(json))
        assertEquals(7, file.id)
        assertEquals(123456789012L, file.size)
        assertEquals("C:\\v.mp4", file.local.path)
        assertTrue(file.local.isDownloadingCompleted)
        assertEquals("abc", file.remote.id)

        val bytes = assertIs<TdApi.Minithumbnail>(TdJsonCodec.decode(
            """{"@type":"minithumbnail","width":2,"height":3,"data":"AQID"}"""
        ))
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.data)
    }

    @Test
    fun decodesArraysAndRoundTrips() {
        val chats = assertIs<TdApi.Chats>(TdJsonCodec.decode(
            """{"@type":"chats","total_count":2,"chat_ids":[-100123,"777"]}"""
        ))
        assertContentEquals(longArrayOf(-100123L, 777L), chats.chatIds)

        val again = assertIs<TdApi.Chats>(TdJsonCodec.decode(TdJsonCodec.encode(chats)))
        assertEquals(2, again.totalCount)
        assertContentEquals(chats.chatIds, again.chatIds)
    }

    @Test
    fun unknownTypeDecodesToNull() {
        assertNull(TdJsonCodec.decode("""{"@type":"somethingFromTheFuture","x":1}"""))
    }

    @Test
    fun toStringIsJson() {
        assertEquals("""{"@type":"ok"}""", TdApi.Ok().toString())
    }
}
