package dev.beszel.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ApiException(val statusCode: Int, override val message: String) : IOException(message)

class BeszelApi(
    hubUrl: String,
    private var token: String = "",
) {
    val baseUrl: String = normalizeHubUrl(hubUrl)

    suspend fun login(email: String, password: String): Session {
        val body = JSONObject().put("identity", email.trim()).put("password", password)
        val response = request("POST", "/api/collections/users/auth-with-password", body, authenticated = false)
        token = response.getString("token")
        val accountEmail = response.optJSONObject("record")?.optString("email").orEmpty().ifBlank { email.trim() }
        return Session(baseUrl, token, accountEmail)
    }

    suspend fun refreshAuth(): String {
        val response = request("POST", "/api/collections/users/auth-refresh")
        token = response.optString("token", token)
        return token
    }

    suspend fun dashboard(): DashboardData = coroutineScope {
        val systems = async { systems() }
        val alerts = async { alerts() }
        val history = async { alertHistory() }
        DashboardData(systems.await(), alerts.await(), history.await())
    }

    suspend fun systems(): List<SystemRecord> = recordList(
        collection = "systems",
        params = mapOf(
            "sort" to "+name",
            "fields" to "id,name,host,port,info,status",
            "perPage" to "200",
        ),
    ).map(::parseSystem)

    suspend fun alerts(): List<AlertRecord> = recordList(
        collection = "alerts",
        params = mapOf(
            "sort" to "-updated",
            "fields" to "id,name,system,value,min,triggered",
            "perPage" to "200",
        ),
    ).map(::parseAlert)

    suspend fun alertHistory(): List<AlertHistoryRecord> = recordList(
        collection = "alerts_history",
        params = mapOf(
            "sort" to "-created",
            "fields" to "id,name,system,val,created,resolved",
            "perPage" to "50",
        ),
        maxItems = 50,
    ).map(::parseAlertHistory)

    suspend fun stats(systemId: String, range: ChartRange): List<StatPoint> {
        val start = Instant.now().minusSeconds(range.hours * 3_600)
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC)
            .format(start)
        // Escape embedded quotes so a stray ' in an id can't alter the PocketBase filter.
        fun esc(value: String) = value.replace("'", "\\'")
        val filter = "system='${esc(systemId)}' && created > '$timestamp' && type='${esc(range.type)}'"
        return recordList(
            collection = "system_stats",
            params = mapOf(
                "filter" to filter,
                "sort" to "+created",
                "fields" to "created,stats",
                "perPage" to "200",
            ),
        ).map(::parseStat)
    }

    private suspend fun recordList(
        collection: String,
        params: Map<String, String>,
        maxItems: Int? = null,
    ): List<JSONObject> {
        val records = mutableListOf<JSONObject>()
        var page = 1
        var totalPages: Int
        do {
            val pageParams = params + ("page" to page.toString())
            val query = pageParams.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            val response = request("GET", "/api/collections/$collection/records?$query")
            totalPages = response.optInt("totalPages", 1)
            val items = response.optJSONArray("items") ?: break
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(records::add)
                if (maxItems != null && records.size >= maxItems) return records.take(maxItems)
            }
            page++
        } while (page <= totalPages)
        return records
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI.create(baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "Beszel-Android/0.1")
            if (authenticated && token.isNotBlank()) connection.setRequestProperty("Authorization", token)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = responseText.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            if (status !in 200..299) {
                val message = json.optString("message").ifBlank {
                    if (status == 401 || status == 403) "Your session is no longer valid" else "Hub returned HTTP $status"
                }
                throw ApiException(status, message)
            }
            json
        } finally {
            // Any non-ApiException (UnknownHostException, SSLHandshakeException, etc.)
            // propagates as-is so friendlyMessage() can classify by real type instead
            // of string-matching a rewritten message.
            connection.disconnect()
        }
    }

    companion object {
        fun normalizeHubUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Enter your Beszel hub URL" }
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI.create(withScheme) }.getOrElse { throw IllegalArgumentException("Enter a valid hub URL") }
            require(uri.scheme == "http" || uri.scheme == "https") { "The hub URL must use HTTP or HTTPS" }
            require(!uri.host.isNullOrBlank()) { "Enter a valid hub URL" }
            return withScheme
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }
}
