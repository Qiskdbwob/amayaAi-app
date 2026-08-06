package com.amaya.intelligence.impl.bridge.windows

import org.json.JSONArray
import org.json.JSONObject

internal fun bridgeMapToJson(map: Map<String, Any?>): JSONObject = JSONObject().apply {
    map.forEach { (key, value) -> put(key, bridgeAnyToJson(value)) }
}

private fun bridgeAnyToJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Double, is Float -> value
    is Number -> value
    is Map<*, *> -> JSONObject().apply {
        value.forEach { (key, item) -> put(key.toString(), bridgeAnyToJson(item)) }
    }
    is Iterable<*> -> JSONArray().apply { value.forEach { put(bridgeAnyToJson(it)) } }
    is Array<*> -> JSONArray().apply { value.forEach { put(bridgeAnyToJson(it)) } }
    else -> value.toString()
}
