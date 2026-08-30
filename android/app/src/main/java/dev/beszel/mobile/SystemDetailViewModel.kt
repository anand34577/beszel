package dev.beszel.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.beszel.mobile.data.ApiException
import dev.beszel.mobile.data.ChartRange
import dev.beszel.mobile.data.HubRepository
import dev.beszel.mobile.data.StatPoint
import dev.beszel.mobile.data.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemDetailUiState(
    val range: ChartRange = ChartRange.HOUR,
    val stats: List<StatPoint> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** Scoped to the system detail back stack entry; one instance per system. */
class SystemDetailViewModel(
    private val repository: HubRepository,
    private val systemId: String,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SystemDetailUiState())
    val state: StateFlow<SystemDetailUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(ChartRange.HOUR)
    }

    fun selectRange(range: ChartRange) {
        if (mutableState.value.range == range) return
        mutableState.update { it.copy(range = range) }
        load(range)
    }

    fun retry() = load(mutableState.value.range)

    private fun load(range: ChartRange) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val result = repository.stats(systemId, range)
                mutableState.update { it.copy(stats = result, isLoading = false) }
            } catch (error: ApiException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    onSessionExpired()
                } else {
                    mutableState.update { it.copy(isLoading = false, error = friendlyMessage(error)) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(isLoading = false, error = friendlyMessage(error)) }
            }
        }
    }

    class Factory(
        private val repository: HubRepository,
        private val systemId: String,
        private val onSessionExpired: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SystemDetailViewModel(repository, systemId, onSessionExpired) as T
    }
}
