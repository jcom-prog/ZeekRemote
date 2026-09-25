package com.openzeekr.app.net

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Exact port of stock 3.0.7 `com.baselinelibrary.sign.SignUtil.sign`.
 *
 * Android Base64.DEFAULT appends a newline for these short MD5/HMAC values. The MD5 newline is
 * part of the signed bytes; the final HMAC newline is removed by SignInterceptor.trim(). Using
 * java.util.Base64 plus the explicit MD5 newline keeps this function deterministic in JVM tests.
 */
internal fun officialHfSign(
    signSecret: String,
    url: String,
    method: String,
    body: String,
    nonce: String,
    sigVersion: String,
    timestamp: String,
    accept: String,
): String {
    val headerLines = sortedMapOf(
        "X-api-signature-nonce" to nonce,
        "X-api-signature-version" to sigVersion,
    )
    val headersPart = buildString {
        if (accept.isNotEmpty()) append(accept.trim()).append('\n')
        for ((name, value) in headerLines) {
            append(name.lowercase()).append(':').append(value.trim()).append('\n')
        }
    }

    val params = sortedMapOf<String, String>()
    val query = url.substringAfter('?', "")
    if (query.isNotEmpty()) for (pair in query.split('&')) {
        val separator = pair.indexOf('=')
        if (separator >= 0) params[pair.substring(0, separator)] = pair.substring(separator + 1)
    }
    val paramsPart = params.entries.joinToString("&") { (name, value) ->
        val encoded = value.replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
            .replace(",", "%2C")
        "$name=$encoded"
    }

    val md5 = MessageDigest.getInstance("MD5").digest(body.toByteArray(Charsets.UTF_8))
    val md5Part = Base64.getEncoder().encodeToString(md5) + "\n"
    val afterScheme = url.substringAfter("://")
    val slash = afterScheme.indexOf('/')
    val path = if (slash < 0) "" else afterScheme.substring(slash).substringBefore('?')
    val stringToSign = headersPart + "\n" + paramsPart + "\n" + md5Part + timestamp +
        "\n" + method + "\n" + path

    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(signSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
}
