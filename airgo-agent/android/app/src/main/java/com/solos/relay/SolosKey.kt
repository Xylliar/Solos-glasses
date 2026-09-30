package com.solos.relay

import com.solosglasses.solosairgosdk.access.SDKAuthenticationException
import com.solosglasses.solosairgosdk.core.wifi.AuthenticationFailedException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Why the Solos SDK refuses to work, in words.
 *
 * Camera, sensors and gesture config are licensed calls: the SDK exchanges the manifest
 * API key for a token first, and on failure throws SDKAuthenticationException("Server
 * error") - which hides the server's actual reason. This makes the same request the SDK
 * does (POST, "<key>.<appId>" in X-API-KEY) so the app can show that reason on screen.
 *
 * Diagnostic only: the endpoint is the SDK's internal one and may change between releases.
 */
object SolosKey {
    private const val TOKEN_URL = "https://n8n.solosglasses.com/webhook/v1/token"

    /** Last result of [check]; null = not checked yet. */
    @Volatile var status: String? = null
        private set
    @Volatile var ok: Boolean = false
        private set

    fun check(apiKey: String, appId: String): String {
        val result = runCatching {
            if (apiKey.isBlank() || apiKey == "MISSING_API_KEY") {
                ok = false
                return@runCatching "Solos key MISSING - set SOLOS_API_KEY in local.properties and rebuild"
            }
            val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(TOKEN_URL)
                // Same shape the SDK sends: the key is only valid for one application id.
                .header("X-API-KEY", "$apiKey.$appId")
                .post(ByteArray(0).toRequestBody())
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val token = json?.optJSONObject("result")?.optString("accessToken").orEmpty()
                ok = resp.isSuccessful && token.isNotBlank()
                if (ok) "Solos key OK"
                else "Solos key REJECTED for app id $appId (HTTP ${resp.code} " +
                    "${json?.optString("errorCode").orEmpty()}): " +
                    (json?.optString("errorMessage")?.takeIf { it.isNotBlank() } ?: body.take(120)) +
                    " - keys are bound to one app id; set SOLOS_APP_ID to the one it was issued for"
            }
        }.getOrElse { "Solos key check failed (no internet?): ${it.message}" }
        status = result
        return result
    }

    /** Turn the SDK's opaque auth error into something a person can act on. */
    fun explain(e: Throwable): String = when {
        e is SDKAuthenticationException || e.cause is SDKAuthenticationException ->
            "Solos SDK licence rejected (${status ?: "key not checked yet"})"
        e is AuthenticationFailedException ->
            "Auth fail: the glasses' file-server password was rejected " +
                "(tried SOLOS_FILE_PASSWORD) - the Solos app may have changed it"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}
