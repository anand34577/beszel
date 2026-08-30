package dev.beszel.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Session(
    val hubUrl: String,
    val token: String,
    val email: String,
)

data class SystemInfo(
    val hostname: String = "",
    val cpu: Float = 0f,
    val cpuCores: Int = 0,
    val cpuThreads: Int = 0,
    val cpuModel: String = "",
    val memoryPercent: Float = 0f,
    val diskPercent: Float = 0f,
    val uptimeSeconds: Long = 0,
    val bandwidthMegabytes: Double = 0.0,
    val loadAverage: List<Float> = emptyList(),
    val os: String = "",
    val kernel: String = "",
    val agentVersion: String = "",
    val temperature: Float? = null,
    val gpuPercent: Float? = null,
    val batteryPercent: Float? = null,
)

data class SystemRecord(
    val id: String,
    val name: String,
    val host: String,
    val port: String,
    val status: String,
    val info: SystemInfo,
) {
    val isUp get() = status == "up"
}

data class AlertRecord(
    val id: String,
    val systemId: String,
    val name: String,
    val triggered: Boolean,
    val value: Double,
    val minutes: Int,
)

data class AlertHistoryRecord(
    val id: String,
    val systemId: String,
    val name: String,
    val value: Double,
    val created: String,
    val resolved: String?,
)

data class StatPoint(
    val timestamp: Long,
    val cpu: Float,
    val memory: Float,
    val disk: Float,
    val networkUpBytes: Double,
    val networkDownBytes: Double,
    val load: Float,
)

enum class ChartRange(val label: String, val hours: Long, val type: String) {
    HOUR("1h", 1, "1m"),
    TWELVE_HOURS("12h", 12, "10m"),
    DAY("24h", 24, "20m"),
    WEEK("7d", 24 * 7, "120m"),
}

data class DashboardData(
    val systems: List<SystemRecord>,
    val alerts: List<AlertRecord>,
    val history: List<AlertHistoryRecord>,
)

data class AlertPresentation(
    val title: String,
    val description: String,
    val unit: String,
    val inverted: Boolean = false,
)

fun alertPresentation(alert: AlertRecord): AlertPresentation {
    val unit = when (alert.name) {
        "CPU", "Memory", "Disk", "GPU", "Battery" -> "%"
        "Bandwidth" -> " MB/s"
        "Temperature" -> "°C"
        else -> ""
    }
    val title = when (alert.name) {
        "CPU" -> "CPU usage"
        "Memory" -> "Memory usage"
        "Disk" -> "Disk usage"
        "GPU" -> "GPU usage"
        "LoadAvg1" -> "Load average 1m"
        "LoadAvg5" -> "Load average 5m"
        "LoadAvg15" -> "Load average 15m"
        else -> alert.name
    }
    val inverted = alert.name == "Battery"
    val description = when (alert.name) {
        "Status" -> "Connection is down"
        else -> "${if (inverted) "Below" else "Above"} ${formatNumber(alert.value)}$unit for ${alert.minutes} min"
    }
    return AlertPresentation(title, description, unit, inverted)
}

fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.roundToInt().toString() else "%.1f".format(value)

fun formatUptime(seconds: Long): String = when {
    seconds < 3_600 -> "${(seconds / 60).coerceAtLeast(1)} min"
    seconds < 172_800 -> "${seconds / 3_600} hr"
    else -> "${seconds / 86_400} days"
}

/** Shared network-error copy so the fleet and detail screens don't drift. */
fun friendlyMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        error is java.net.UnknownHostException || error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException -> "Could not reach this Beszel hub"
        error is javax.net.ssl.SSLHandshakeException || error is javax.net.ssl.SSLPeerUnverifiedException ->
            "The hub's HTTPS certificate could not be verified"
        // Fallback for message text the platform doesn't expose as a distinct type.
        raw.contains("Failed to connect", ignoreCase = true) ||
            raw.contains("Unable to resolve host", ignoreCase = true) -> "Could not reach this Beszel hub"
        raw.contains("trust anchor", ignoreCase = true) || raw.contains("certificate", ignoreCase = true) ->
            "The hub's HTTPS certificate could not be verified"
        raw.isBlank() -> "Something went wrong"
        else -> raw
    }
}

fun formatBytesPerSecond(bytes: Double): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB/s".format(bytes / 1_000_000_000)
    bytes >= 1_000_000 -> "%.1f MB/s".format(bytes / 1_000_000)
    bytes >= 1_000 -> "%.0f KB/s".format(bytes / 1_000)
    else -> "%.0f B/s".format(bytes)
}

internal fun parseSystem(json: JSONObject): SystemRecord {
    val info = json.optJSONObject("info") ?: JSONObject()
    val loadArray = info.optJSONArray("la")
    val battery = info.optJSONArray("bat")
    return SystemRecord(
        id = json.optString("id"),
        name = json.optString("name", "Unnamed system"),
        host = json.optString("host"),
        port = json.optString("port"),
        status = json.optString("status", "pending"),
        info = SystemInfo(
            hostname = info.optString("h"),
            cpu = info.float("cpu"),
            cpuCores = info.optInt("c"),
            cpuThreads = info.optInt("t"),
            cpuModel = info.optString("m"),
            memoryPercent = info.float("mp"),
            diskPercent = info.float("dp"),
            uptimeSeconds = info.optLong("u"),
            bandwidthMegabytes = info.double("b"),
            loadAverage = loadArray?.toFloatList().orEmpty(),
            os = info.optString("o"),
            kernel = info.optString("k"),
            agentVersion = info.optString("v"),
            temperature = info.nullableFloat("dt"),
            gpuPercent = info.nullableFloat("g"),
            batteryPercent = battery?.takeIf { it.length() > 0 }?.optDouble(0)?.toFloat(),
        ),
    )
}

internal fun parseAlert(json: JSONObject) = AlertRecord(
    id = json.optString("id"),
    systemId = json.optString("system"),
    name = json.optString("name"),
    triggered = json.optBoolean("triggered"),
    value = json.double("value"),
    minutes = json.optInt("min"),
)

internal fun parseAlertHistory(json: JSONObject) = AlertHistoryRecord(
    id = json.optString("id"),
    systemId = json.optString("system"),
    name = json.optString("name"),
    value = json.double("val"),
    created = json.optString("created"),
    resolved = json.optString("resolved").takeIf { it.isNotBlank() && it != "null" },
)

internal fun parseStat(json: JSONObject): StatPoint {
    val stats = json.optJSONObject("stats") ?: JSONObject()
    val bandwidth = stats.optJSONArray("b")
    return StatPoint(
        timestamp = parseTimestamp(json.optString("created")),
        cpu = stats.float("cpu"),
        memory = stats.float("mp"),
        disk = stats.float("dp"),
        networkUpBytes = bandwidth?.optDouble(0)?.takeUnless(Double::isNaN) ?: stats.double("ns") * 1024 * 1024,
        networkDownBytes = bandwidth?.optDouble(1)?.takeUnless(Double::isNaN) ?: stats.double("nr") * 1024 * 1024,
        load = stats.optJSONArray("la")?.optDouble(0)?.takeUnless(Double::isNaN)?.toFloat() ?: 0f,
    )
}

private fun JSONObject.double(key: String): Double = optDouble(key).takeUnless(Double::isNaN) ?: 0.0
private fun JSONObject.float(key: String): Float = double(key).toFloat()
private fun JSONObject.nullableFloat(key: String): Float? = optDouble(key).takeUnless(Double::isNaN)?.toFloat()

private fun JSONArray.toFloatList(): List<Float> = buildList {
    for (index in 0 until length()) {
        optDouble(index).takeUnless(Double::isNaN)?.let { add(it.toFloat()) }
    }
}

/** Parses a PocketBase timestamp (ISO-8601 or its bare "yyyy-MM-dd HH:mm:ss" form). */
fun parseTimestamp(value: String): Long {
    if (value.isBlank()) return System.currentTimeMillis()
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching {
            val clean = value.substringBefore('.').replace('T', ' ')
            LocalDateTime.parse(clean, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        .getOrDefault(System.currentTimeMillis())
}
