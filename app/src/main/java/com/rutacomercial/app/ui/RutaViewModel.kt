package com.rutacomercial.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rutacomercial.app.data.DataSnapshot
import com.rutacomercial.app.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RutaViewModel(val repository: Repository) : ViewModel() {
    val data = repository.observeSnapshot().map { snapshot ->
        val removed = snapshot.audit.filter { it.entityType == "sector" && it.action == "REMOVE" }.map { it.entityId }.toSet()
        snapshot.copy(sectors = snapshot.sectors.filterNot { it.id in removed })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSnapshot())
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = mutableEvents.asSharedFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val now = flow { while(true) { emit(System.currentTimeMillis()); delay(1_000) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    fun message(text: String) { mutableEvents.tryEmit(text) }
    fun perform(success: String? = null, onSuccess: () -> Unit = {}, work: suspend () -> Unit) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            try {
                work()
                onSuccess()
                success?.let { mutableEvents.emit(it) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutableEvents.emit(e.message ?: "No se pudo completar la operación. Tus datos guardados se conservan.") }
            finally { mutableBusy.value = false }
        }
    }

    companion object {
        fun factory(repository: Repository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RutaViewModel(repository) as T
        }
    }
}
