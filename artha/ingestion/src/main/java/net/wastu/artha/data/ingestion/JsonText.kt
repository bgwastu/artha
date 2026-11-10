package net.wastu.artha.data.ingestion

import org.json.JSONObject

/**
 * Text for [key], or an empty string when it is missing or null. Plain `optString` returns the word "null" for
 * a JSON null, and providers send null fields freely, so that word ended up as titles and merchant names.
 */
fun JSONObject.optText(key: String): String =
    if (isNull(key)) "" else optString(key).takeUnless { it.trim().equals("null", ignoreCase = true) }.orEmpty()
