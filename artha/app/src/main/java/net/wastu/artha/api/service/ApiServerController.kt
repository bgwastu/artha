package net.wastu.artha.api.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.wastu.artha.api.tunnel.TunnelState

object ApiServerController {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Inactive)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _publicUrl = MutableStateFlow<String?>(null)
    val publicUrl: StateFlow<String?> = _publicUrl.asStateFlow()

    internal fun updateServiceRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _tunnelState.value = TunnelState.Inactive
            _publicUrl.value = null
        }
    }

    internal fun updateTunnelState(state: TunnelState) {
        _tunnelState.value = state
        if (state is TunnelState.Active) {
            _publicUrl.value = state.url
        } else if (state is TunnelState.Inactive || state is TunnelState.Failed) {
            _publicUrl.value = null
        }
    }

    fun start(context: Context) {
        val intent = Intent(context, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }
}
