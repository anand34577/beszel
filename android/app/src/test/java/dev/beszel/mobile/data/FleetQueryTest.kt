package dev.beszel.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun system(
    id: String,
    name: String,
    host: String = "$id.local",
    hostname: String = "",
    status: String = "up",
    cpu: Float = 10f,
): SystemRecord = SystemRecord(
    id = id,
    name = name,
    host = host,
    port = "",
    status = status,
    info = SystemInfo(hostname = hostname, cpu = cpu),
)

private fun alert(systemId: String, triggered: Boolean = true) = AlertRecord(
    id = "alert-$systemId",
    systemId = systemId,
    name = "CPU",
    triggered = triggered,
    value = 90.0,
    minutes = 5,
)

class FilterSystemsTest {

    private val systems = listOf(
        system("a", "nas-main", host = "nas.local", hostname = "nas", cpu = 20f),
        system("b", "edge-node", host = "edge.local", hostname = "edge", cpu = 95f),
        system("c", "pi-hole", hostname = "pi", status = "down", cpu = 0f),
    )
    private val alerts = listOf(alert("b"), alert("c"))

    @Test
    fun `query matches name host and hostname case-insensitively`() {
        val byName = filterSystems(systems, emptyList(), "NAS", FleetFilter.ALL, FleetSort.NAME)
        val byHost = filterSystems(systems, emptyList(), "edge.local", FleetFilter.ALL, FleetSort.NAME)
        val byHostname = filterSystems(systems, emptyList(), "pi-hole", FleetFilter.ALL, FleetSort.NAME)
        assertEquals(listOf("a"), byName.map { it.id })
        assertEquals(listOf("b"), byHost.map { it.id })
        assertEquals(listOf("c"), byHostname.map { it.id })
    }

    @Test
    fun `alerting filter keeps only systems with triggered alerts`() {
        val result = filterSystems(systems, alerts, "", FleetFilter.ALERTING, FleetSort.NAME)
        assertEquals(listOf("b", "c"), result.map { it.id })
    }

    @Test
    fun `down filter keeps only offline systems`() {
        val result = filterSystems(systems, alerts, "", FleetFilter.DOWN, FleetSort.NAME)
        assertEquals(listOf("c"), result.map { it.id })
    }

    @Test
    fun `status sort orders down first then alerting then up`() {
        val result = filterSystems(systems, alerts, "", FleetFilter.ALL, FleetSort.STATUS)
        assertEquals(listOf("c", "b", "a"), result.map { it.id })
    }

    @Test
    fun `cpu sort is descending by usage`() {
        val result = filterSystems(systems, emptyList(), "", FleetFilter.ALL, FleetSort.CPU)
        assertEquals(listOf(95f, 20f, 0f), result.map { it.info.cpu })
    }

    @Test
    fun `name sort is case-insensitive alphabetical`() {
        val unordered = listOf(system("x", "zeta"), system("y", "Alpha"))
        val result = filterSystems(unordered, emptyList(), "", FleetFilter.ALL, FleetSort.NAME)
        assertEquals(listOf("y", "x"), result.map { it.id })
    }
}

class ComputeFleetPulseTest {

    @Test
    fun `averages cpu across online reporting systems only`() {
        val systems = listOf(
            system("a", "a", status = "up", cpu = 10f),
            system("b", "b", status = "up", cpu = 30f),
            system("c", "c", status = "down", cpu = 90f),
            system("d", "d", status = "pending", cpu = 50f),
        )
        assertEquals(20f, computeFleetPulse(systems)!!, 0.001f)
    }

    @Test
    fun `ignores systems reporting zero cpu`() {
        val systems = listOf(
            system("a", "a", cpu = 0f),
            system("b", "b", cpu = 40f),
        )
        assertEquals(40f, computeFleetPulse(systems)!!, 0.001f)
    }

    @Test
    fun `returns null when nothing is reporting`() {
        assertNull(computeFleetPulse(emptyList()))
        assertNull(computeFleetPulse(listOf(system("a", "a", status = "down"))))
    }
}
