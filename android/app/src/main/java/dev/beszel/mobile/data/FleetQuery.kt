package dev.beszel.mobile.data

/** Fleet list view state, kept as pure functions for testing. */
enum class FleetFilter { ALL, ALERTING, DOWN }

enum class FleetSort { NAME, STATUS, CPU }

fun filterSystems(
    systems: List<SystemRecord>,
    alerts: List<AlertRecord>,
    query: String,
    filter: FleetFilter,
    sort: FleetSort,
): List<SystemRecord> {
    val alertingIds = alerts.filter(AlertRecord::triggered).map(AlertRecord::systemId).toSet()
    val trimmed = query.trim()
    val matched = systems.filter { system ->
        val matchesQuery = trimmed.isEmpty() || with(system) {
            name.contains(trimmed, ignoreCase = true) ||
                host.contains(trimmed, ignoreCase = true) ||
                info.hostname.contains(trimmed, ignoreCase = true)
        }
        val matchesFilter = when (filter) {
            FleetFilter.ALL -> true
            FleetFilter.ALERTING -> system.id in alertingIds
            FleetFilter.DOWN -> system.status == "down"
        }
        matchesQuery && matchesFilter
    }
    return when (sort) {
        FleetSort.NAME -> matched.sortedBy { it.name.lowercase() }
        FleetSort.CPU -> matched.sortedByDescending { it.info.cpu }
        FleetSort.STATUS -> matched.sortedWith(
            compareBy(
                { statusRank(it, alertingIds) },
                { it.name.lowercase() },
            ),
        )
    }
}

private fun statusRank(system: SystemRecord, alertingIds: Set<String>): Int = when {
    system.status == "down" -> 0
    system.id in alertingIds -> 1
    system.status == "up" -> 2
    else -> 3
}

/**
 * Fleet-wide mean CPU across online systems, sampled once per poll to feed
 * the fleet pulse sparkline. Null when no system is reporting yet.
 */
fun computeFleetPulse(systems: List<SystemRecord>): Float? {
    val values = systems.filter { it.isUp && it.info.cpu > 0f }.map { it.info.cpu }
    if (values.isEmpty()) return null
    return values.average().toFloat()
}
