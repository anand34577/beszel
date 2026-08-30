package dev.beszel.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.beszel.mobile.data.AlertHistoryRecord
import dev.beszel.mobile.data.AlertRecord
import dev.beszel.mobile.data.ApiException
import dev.beszel.mobile.data.HubRepository
import dev.beszel.mobile.data.Session
import dev.beszel.mobile.data.SessionStore
import dev.beszel.mobile.data.SystemRecord
import dev.beszel.mobile.data.ThemeMode
import dev.beszel.mobile.data.computeFleetPulse
import dev.beszel.mobile.data.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUiState(
    val isStarting: Boolean = true,
    val isLoggingIn: Boolean = false,
    val session: Session? = null,
    val systems: List<SystemRecord> = emptyList(),
    val alerts: List<AlertRecord> = emptyList(),
    val alertHistory: List<AlertHistoryRecord> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastUpdated: Long? = null,
    val message: String? = null,
    /** Persists across polls, unlike [message]; drives the fleet screen's error state. */
    val fleetError: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Fleet-wide CPU samples, one per poll, oldest first. Feeds the pulse header. */
    val pulse: List<Float> = emptyList(),
) {
    val activeAlerts get() = alerts.filter(AlertRecord::triggered)
    val isLoadingFleet get() = session != null && systems.isEmpty() && isStarting
}

private const val PULSE_WINDOW = 48
private const val POLL_INTERVAL_MS = 15_000L
private const val POLL_INTERVAL_MAX_MS = 120_000L

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    val repository = HubRepository()

    private val mutableState = MutableStateFlow(
        AppUiState(themeMode = store.themeMode, dynamicColor = store.dynamicColor),
    )
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    private var pollingJob: Job? = null
    private val pulseBuffer = ArrayDeque<Float>()
    // Separate from isRefreshing (which only toggles the spinner for user-initiated
    // refreshes): guards against a manual refresh racing an in-flight background poll.
    private var isFetching = false

    init {
        restoreSession()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            // Keystore-backed decrypt is a binder call; keep it off the main thread.
            val session = withContext(Dispatchers.IO) { store.loadSession() }
            if (session == null) {
                mutableState.update { it.copy(isStarting = false) }
                return@launch
            }
            repository.attach(session)
            mutableState.update { it.copy(session = session) }
            try {
                val refreshedToken = repository.refreshAuth()
                val refreshed = repository.updateToken(refreshedToken)
                store.saveSession(refreshed)
                mutableState.update { it.copy(session = refreshed) }
                loadDashboard(showSpinner = false)
                startPolling()
            } catch (error: ApiException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    clearSession()
                } else {
                    // Transient hub error (e.g. 502/503): keep the session and keep
                    // retrying instead of leaving auto-refresh dead for the app's life.
                    mutableState.update { it.copy(isStarting = false, message = friendlyMessage(error)) }
                    startPolling()
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(isStarting = false, message = friendlyMessage(error)) }
                startPolling()
            }
        }
    }

    fun login(hubUrl: String, email: String, password: String) {
        if (mutableState.value.isLoggingIn) return
        mutableState.update { it.copy(isLoggingIn = true, message = null) }
        viewModelScope.launch {
            try {
                val session = repository.login(hubUrl, email, password)
                store.saveSession(session)
                mutableState.update { it.copy(session = session, isLoggingIn = false, isStarting = false) }
                loadDashboard(showSpinner = true)
                startPolling()
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isLoggingIn = false, isStarting = false, message = friendlyMessage(error))
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadDashboard(showSpinner = true) }
    }

    /** @return true if the fetch succeeded, so callers (the poll loop) can back off on failure. */
    private suspend fun loadDashboard(showSpinner: Boolean): Boolean {
        if (!repository.hasSession) return true
        if (isFetching) return true
        isFetching = true
        mutableState.update { it.copy(isRefreshing = showSpinner, message = null) }
        try {
            val dashboard = repository.dashboard()
            val pulse = computeFleetPulse(dashboard.systems)
            if (pulse != null) {
                pulseBuffer.addLast(pulse)
                while (pulseBuffer.size > PULSE_WINDOW) pulseBuffer.removeFirst()
            }
            mutableState.update {
                it.copy(
                    isStarting = false,
                    isRefreshing = false,
                    systems = dashboard.systems,
                    alerts = dashboard.alerts,
                    alertHistory = dashboard.history,
                    lastUpdated = System.currentTimeMillis(),
                    pulse = pulseBuffer.toList(),
                    fleetError = null,
                )
            }
            return true
        } catch (error: ApiException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                clearSession("Please sign in again")
                return true
            } else {
                // Silent-fail background polls (showSpinner = false): don't spam the
                // snackbar every 15s while the hub is down. The persistent fleetError
                // still surfaces a proper error state on the fleet screen.
                mutableState.update {
                    it.copy(
                        isStarting = false,
                        isRefreshing = false,
                        message = if (showSpinner) friendlyMessage(error) else it.message,
                        fleetError = friendlyMessage(error),
                    )
                }
                return false
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update {
                it.copy(
                    isStarting = false,
                    isRefreshing = false,
                    message = if (showSpinner) friendlyMessage(error) else it.message,
                    fleetError = friendlyMessage(error),
                )
            }
            return false
        } finally {
            isFetching = false
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        store.themeMode = mode
        mutableState.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        store.dynamicColor = enabled
        mutableState.update { it.copy(dynamicColor = enabled) }
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun logout() = clearSession()

    private fun clearSession(message: String? = null) {
        pollingJob?.cancel()
        repository.detach()
        pulseBuffer.clear()
        store.clearSession()
        mutableState.update {
            AppUiState(
                isStarting = false,
                message = message,
                themeMode = it.themeMode,
                dynamicColor = it.dynamicColor,
            )
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var interval = POLL_INTERVAL_MS
            while (isActive) {
                delay(interval)
                val success = loadDashboard(showSpinner = false)
                // Back off while the hub is unreachable so a dead hub isn't hammered
                // every 15s; reset to the normal cadence as soon as it recovers.
                interval = if (success) POLL_INTERVAL_MS else (interval * 2).coerceAtMost(POLL_INTERVAL_MAX_MS)
            }
        }
    }
}
