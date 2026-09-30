package com.codingagent.mobile.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingagent.mobile.agents.AgentRepository
import com.codingagent.mobile.domain.AgentKind
import com.codingagent.mobile.domain.ProviderConfig
import com.codingagent.mobile.domain.RuntimeState
import com.codingagent.mobile.runtime.AgentRuntimeService
import com.codingagent.mobile.runtime.RuntimeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeManager: RuntimeManager,
    private val agentRepository: AgentRepository
) : ViewModel() {

    val runtimeStatus = runtimeManager.status
    val selectedAgent = agentRepository.selectedAgent
    val cliStatus = agentRepository.cliStatus

    fun savedProvider(): ProviderConfig = agentRepository.getProvider()

    fun setupRuntime() {
        viewModelScope.launch {
            val state = runtimeManager.status.value.state
            when (state) {
                RuntimeState.NOT_INSTALLED, RuntimeState.ERROR -> {
                    runtimeManager.installRuntime()
                }
                RuntimeState.READY, RuntimeState.STOPPED -> {
                    context.startForegroundService(
                        Intent(context, AgentRuntimeService::class.java)
                    )
                    runtimeManager.start()
                }
                else -> Unit
            }
        }
    }

    fun saveProvider(type: String, baseUrl: String, model: String, apiKey: String?) {
        agentRepository.setProvider(
            ProviderConfig(
                type = type,
                baseUrl = baseUrl.trim(),
                apiKey = apiKey,
                defaultModel = model.trim()
            )
        )
    }

    fun selectAgent(kind: AgentKind) {
        agentRepository.selectAgent(kind)
    }

    fun diagnostics(): String = runtimeManager.diagnostics()

    fun installCli() {
        viewModelScope.launch {
            agentRepository.installCli()
        }
    }

    fun installQwenCli() {
        viewModelScope.launch {
            agentRepository.installQwenCli()
        }
    }
}
