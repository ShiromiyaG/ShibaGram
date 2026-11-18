package org.drinkless.tdlib

import kotlinx.serialization.json.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts TdApi objects to and from TDLib's JSON format by reflection.
 *
 * TdApi.java is generated from td_api.tl with mechanical naming rules, which this reverses:
 * class `SearchChatMessages` <-> "@type": "searchChatMessages", field `chatId` <-> "chat_id".
 * (Checked against the whole td_api.tl of the bundled TDLib commit: no name breaks the rule.)
 * int64 values may arrive as strings; bytes are base64.
 */
object TdJsonCodec {
    private const val PACKAGE_PREFIX = "org.drinkless.tdlib.TdApi$"

    private class FieldInfo(val field: Field, val jsonName: String)

    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val missingClasses = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<Class<*>, List<FieldInfo>>()
    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    private fun snakeCase(name: String): String = buildString(name.length + 8) {
        for (c in name) {
            if (c.isUpperCase()) append('_').append(c.lowercaseChar()) else append(c)
        }
    }

    private fun fieldsOf(cls: Class<*>): List<FieldInfo> = fieldCache.getOrPut(cls) {
        cls.fields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { FieldInfo(it, snakeCase(it.name)) }
    }

    private fun typeName(cls: Class<*>): String = cls.simpleName.replaceFirstChar { it.lowercaseChar() }

    private fun classFor(type: String): Class<*>? {
        classCache[type]?.let { return it }
        if (type in missingClasses) return null
        return try {
            Class.forName(PACKAGE_PREFIX + type.replaceFirstChar { it.uppercaseChar() }).also { classCache[type] = it }
        } catch (e: ClassNotFoundException) {
            missingClasses += type
            null
        }
    }

    // ---- Encoding ------------------------------------------------------------------------------

    /** JSON text for [obj]; [extra] becomes "@extra" (used to match responses to requests). */
    @JvmStatic
    @JvmOverloads
    fun encode(obj: TdApi.Object, extra: Long? = null): String = toJson(obj, extra).toString()

    private fun toJson(obj: TdApi.Object, extra: Long? = null): JsonObject = buildJsonObject {
        put("@type", typeName(obj.javaClass))
        for (f in fieldsOf(obj.javaClass)) {
            val value = f.field.get(obj) ?: continue
            put(f.jsonName, valueToJson(value))
        }
        if (extra != null) put("@extra", extra)
    }

    private fun valueToJson(value: Any): JsonElement = when (value) {
        is TdApi.Object -> toJson(value)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value) // TDLib accepts int64 as number or string
        is Double -> JsonPrimitive(value)
        is ByteArray -> JsonPrimitive(base64Encoder.encodeToString(value))
        is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
        is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
        is Array<*> -> JsonArray(value.map { it?.let(::valueToJson) ?: JsonNull })
        else -> throw IllegalArgumentException("Unsupported TdApi field type: ${value.javaClass}")
    }

    // ---- Decoding ------------------------------------------------------------------------------

    /** Parses TDLib's JSON text; returns null for unknown types. */
    @JvmStatic
    fun decode(json: String): TdApi.Object? = decode(Json.parseToJsonElement(json).jsonObject)

    internal fun decode(json: JsonObject): TdApi.Object? {
        val type = (json["@type"] as? JsonPrimitive)?.content ?: return null
        val cls = classFor(type) ?: return null
        val instance = cls.getDeclaredConstructor().newInstance() as TdApi.Object
        for (f in fieldsOf(cls)) {
            val element = json[f.jsonName] ?: continue
            if (element is JsonNull) continue
            f.field.set(instance, readValue(element, f.field.type))
        }
        return instance
    }

    private fun readValue(element: JsonElement, type: Class<*>): Any? = when {
        type == Int::class.javaPrimitiveType -> element.jsonPrimitive.content.toInt()
        type == Long::class.javaPrimitiveType -> element.jsonPrimitive.content.toLong()
        type == Double::class.javaPrimitiveType -> element.jsonPrimitive.content.toDouble()
        type == Boolean::class.javaPrimitiveType -> element.jsonPrimitive.boolean
        type == String::class.java -> element.jsonPrimitive.content
        type == ByteArray::class.java -> base64Decoder.decode(element.jsonPrimitive.content)
        type == IntArray::class.java -> element.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray()
        type == LongArray::class.java -> element.jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray()
        type == DoubleArray::class.java -> element.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
        type == BooleanArray::class.java -> element.jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray()
        type.isArray -> {
            val items = element.jsonArray
            val component = type.componentType
            val array = java.lang.reflect.Array.newInstance(component, items.size)
            items.forEachIndexed { i, item ->
                if (item !is JsonNull) java.lang.reflect.Array.set(array, i, readValue(item, component))
            }
            array
        }
        TdApi.Object::class.java.isAssignableFrom(type) -> decode(element.jsonObject)
        else -> throw IllegalArgumentException("Unsupported TdApi field type: $type")
    }
}
