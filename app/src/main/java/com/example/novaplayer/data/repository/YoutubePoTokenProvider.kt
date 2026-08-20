package com.example.novaplayer.data.repository

import android.content.Context
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class YoutubePoTokens(
    val visitorData: String,
    val playerPoToken: String,
    val streamingPoToken: String
)

/**
 * Generates YouTube's short-lived Proof-of-Origin tokens locally.
 *
 * YouTube's BotGuard VM is delivered by the Create endpoint and is executed in
 * the system WebView. The token itself is never written to the application log.
 */
class YoutubePoTokenProvider(
    private val context: Context,
    private val client: OkHttpClient,
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private var session: BotGuardSession? = null
    private var sessionVisitorData: String? = null
    private var sessionRequestVisitorData: String? = null
    private var streamingPoToken: String? = null

    suspend fun getTokens(videoId: String, visitorData: String): YoutubePoTokens = mutex.withLock {
        require(visitorData.isNotBlank()) { "YouTube visitor data is empty" }
        val tokenVisitorData = decodeVisitorData(visitorData)

        // A BotGuard session is bound to the visitor data used for its first
        // streaming token. Recreating it for every anonymous player response
        // produces a different visitor value and invalidates the pairing.
        if (session == null || session!!.isExpired()) {
            session?.close()
            session = withTimeout(20_000L) {
                BotGuardSession.create(context, client, logger)
            }
            sessionVisitorData = tokenVisitorData
            sessionRequestVisitorData = visitorData
            streamingPoToken = session!!.mint(tokenVisitorData)
            logger("YouTube BotGuard session ready")
        }

        val activeSession = session ?: error("BotGuard session was not initialized")
        val streamingToken = streamingPoToken ?: activeSession.mint(tokenVisitorData).also {
            streamingPoToken = it
        }
        val playerToken = withTimeout(10_000L) {
            activeSession.mint(videoId)
        }
        YoutubePoTokens(
            sessionRequestVisitorData ?: visitorData,
            playerToken,
            streamingToken
        )
    }

    private fun decodeVisitorData(value: String): String =
        runCatching {
            java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }.getOrDefault(value)

    private class BotGuardSession private constructor(
        context: Context,
        private val client: OkHttpClient,
        private val logger: (String) -> Unit,
        private val ready: CompletableDeferred<Unit>
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val tokenWaiters = ConcurrentHashMap<String, CompletableDeferred<String>>()
        private var webView: WebView? = null
        @Volatile
        private var expirationTimeMs = 0L

        init {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "BotGuard WebView must be created on the main thread"
            }
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = USER_AGENT
                settings.blockNetworkLoads = true
                addJavascriptInterface(this@BotGuardSession, JS_INTERFACE)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.message().contains("Uncaught")) {
                            failInitialization(RuntimeException("BotGuard WebView: ${message.message()}"))
                        }
                        return true
                    }
                }
            }
        }

        fun start(html: String) {
            val page = html.replaceFirst(
                "</script>",
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
            )
            webView?.loadDataWithBaseURL(
                "https://www.youtube.com",
                page,
                "text/html",
                "utf-8",
                null
            )
        }

        suspend fun mint(identifier: String): String {
            ready.await()
            val result = CompletableDeferred<String>()
            tokenWaiters[identifier] = result
            try {
                val identifierLiteral = JSONObject.quote(identifier)
                val identifierBytes = identifier.toByteArray(StandardCharsets.UTF_8)
                    .joinToString(",") { (it.toInt() and 0xff).toString() }
                withContext(Dispatchers.Main) {
                    webView?.evaluateJavascript(
                        """try {
                            identifier = $identifierLiteral;
                            u8Identifier = new Uint8Array([$identifierBytes]);
                            poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier);
                            poTokenU8String = "";
                            for (i = 0; i < poTokenU8.length; i++) {
                                if (i !== 0) poTokenU8String += ",";
                                poTokenU8String += poTokenU8[i];
                            }
                            $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String);
                        } catch (error) {
                            $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\\n" + error.stack);
                        }""",
                        null
                    ) ?: error("BotGuard WebView is unavailable")
                }
                return result.await()
            } finally {
                tokenWaiters.remove(identifier)
            }
        }

        fun isExpired(): Boolean = expirationTimeMs > 0L &&
            System.currentTimeMillis() >= expirationTimeMs

        @JavascriptInterface
        fun downloadAndRunBotguard() {
            scope.launch {
                try {
                    val response = postBotguard(
                        "https://www.youtube.com/api/jnn/v1/Create",
                        JSONArray().put(REQUEST_KEY).toString()
                    )
                    val challenge = parseChallengeData(response)
                    withContext(Dispatchers.Main) {
                        webView?.evaluateJavascript(
                            """try {
                                data = $challenge;
                                runBotGuard(data).then(function(result) {
                                    webPoSignalOutput = result.webPoSignalOutput;
                                    $JS_INTERFACE.onRunBotguardResult(result.botguardResponse);
                                }, function(error) {
                                    $JS_INTERFACE.onJsInitializationError(error + "\\n" + error.stack);
                                });
                            } catch (error) {
                                $JS_INTERFACE.onJsInitializationError(error + "\\n" + error.stack);
                            }""",
                            null
                        ) ?: failInitialization(RuntimeException("BotGuard WebView is unavailable"))
                    }
                } catch (error: Throwable) {
                    failInitialization(error)
                }
            }
        }

        @JavascriptInterface
        fun onRunBotguardResult(botguardResponse: String) {
            scope.launch {
                try {
                    val response = postBotguard(
                        "https://www.youtube.com/api/jnn/v1/GenerateIT",
                        JSONArray().put(REQUEST_KEY).put(botguardResponse).toString()
                    )
                    val tokenData = JSONArray(response)
                    val integrityToken = tokenData.getString(0)
                    val lifetimeSeconds = tokenData.getLong(1)
                    expirationTimeMs = System.currentTimeMillis() +
                        (lifetimeSeconds - 600L).coerceAtLeast(60L) * 1000L
                    val integrityLiteral = base64ToJsUint8Array(integrityToken)
                    withContext(Dispatchers.Main) {
                        webView?.evaluateJavascript(
                            "this.integrityToken = $integrityLiteral",
                            null
                        )
                        ready.complete(Unit)
                    }
                } catch (error: Throwable) {
                    failInitialization(error)
                }
            }
        }

        @JavascriptInterface
        fun onJsInitializationError(error: String) {
            failInitialization(RuntimeException(error))
        }

        @JavascriptInterface
        fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
            tokenWaiters.remove(identifier)?.complete(u8ToBase64(poTokenU8))
        }

        @JavascriptInterface
        fun onObtainPoTokenError(identifier: String, error: String) {
            tokenWaiters.remove(identifier)?.completeExceptionally(RuntimeException(error))
        }

        fun close() {
            scope.cancel()
            val destroy = Runnable {
                webView?.let {
                    it.removeJavascriptInterface(JS_INTERFACE)
                    it.loadUrl("about:blank")
                    it.destroy()
                }
                webView = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) destroy.run()
            else android.os.Handler(Looper.getMainLooper()).post(destroy)
        }

        private fun failInitialization(error: Throwable) {
            if (!ready.isCompleted) ready.completeExceptionally(error)
            tokenWaiters.values.forEach { it.completeExceptionally(error) }
            tokenWaiters.clear()
        }

        private fun postBotguard(url: String, body: String): String {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", GOOGLE_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("BotGuard request failed: HTTP ${response.code}")
                }
                response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("BotGuard returned an empty response")
            }
        }

        private fun parseChallengeData(raw: String): String {
            val scrambled = JSONArray(raw)
            val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
                JSONArray(descramble(scrambled.getString(1)))
            } else {
                scrambled.getJSONArray(0)
            }
            val safeScript = challengeData.optJSONArray(1)?.firstString()
            val trustedUrl = challengeData.optJSONArray(2)?.firstString()
            return JSONObject()
                .put("messageId", challengeData.getString(0))
                .put(
                    "interpreterJavascript",
                    JSONObject()
                        .put(
                            "privateDoNotAccessOrElseSafeScriptWrappedValue",
                            safeScript ?: JSONObject.NULL
                        )
                        .put(
                            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
                            trustedUrl ?: JSONObject.NULL
                        )
                )
                .put("interpreterHash", challengeData.getString(3))
                .put("program", challengeData.getString(4))
                .put("globalName", challengeData.getString(5))
                .put("clientExperimentsStateBlob", challengeData.getString(7))
                .toString()
        }

        companion object {
            private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
            private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
            private const val JS_INTERFACE = "NovaPoToken"
            private const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
            private val JSON_MEDIA_TYPE = "application/json+protobuf".toMediaType()

            suspend fun create(
                context: Context,
                client: OkHttpClient,
                logger: (String) -> Unit
            ): BotGuardSession {
                val html = withContext(Dispatchers.IO) {
                    context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                }
                val ready = CompletableDeferred<Unit>()
                val session = withContext(Dispatchers.Main) {
                    BotGuardSession(context, client, logger, ready).also { it.start(html) }
                }
                ready.await()
                return session
            }

            private fun JSONArray.firstString(): String? {
                for (index in 0 until length()) {
                    if (opt(index) is String) return getString(index)
                }
                return null
            }

            private fun descramble(value: String): String {
                val decoded = decodeBase64(value)
                return decoded.map { (it.toInt() and 0xff) + 97 }
                    .map { it.toByte() }
                    .toByteArray()
                    .toString(StandardCharsets.UTF_8)
            }

            private fun decodeBase64(value: String): ByteArray {
                val normalized = value.replace('-', '+').replace('_', '/')
                    .replace('.', '=')
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }
                return Base64.decode(normalized, Base64.DEFAULT)
            }

            private fun base64ToJsUint8Array(value: String): String {
                val bytes = decodeBase64(value)
                return "new Uint8Array([" +
                    bytes.joinToString(",") { (it.toInt() and 0xff).toString() } + "])"
            }

            private fun u8ToBase64(value: String): String {
                val bytes = value.split(',')
                    .filter { it.isNotBlank() }
                    .map { it.trim().toInt().toByte() }
                    .toByteArray()
                return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
            }
        }
    }
}
