package dev.beszel.mobile.data

/**
 * Single owner of the API client and the active session. ViewModels share
 * the instance owned by [dev.beszel.mobile.AppViewModel].
 */
class HubRepository {
    private var api: BeszelApi? = null
    private var active: Session? = null

    val session: Session? get() = active
    val hasSession: Boolean get() = active != null

    fun attach(session: Session) {
        api = BeszelApi(session.hubUrl, session.token)
        active = session
    }

    fun updateToken(token: String): Session {
        val current = active ?: error("No active session")
        val refreshed = current.copy(token = token)
        api = BeszelApi(refreshed.hubUrl, refreshed.token)
        active = refreshed
        return refreshed
    }

    fun detach() {
        api = null
        active = null
    }

    suspend fun login(hubUrl: String, email: String, password: String): Session {
        val client = BeszelApi(hubUrl)
        val session = client.login(email, password)
        api = client
        active = session
        return session
    }

    suspend fun refreshAuth(): String = requireApi().refreshAuth()

    suspend fun dashboard(): DashboardData = requireApi().dashboard()

    suspend fun stats(systemId: String, range: ChartRange): List<StatPoint> = requireApi().stats(systemId, range)

    private fun requireApi(): BeszelApi = api ?: error("Repository has no active session")
}
