package com.codingagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingagent.mobile.agents.AgentRepository
import com.codingagent.mobile.domain.ApprovalRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentRepository: AgentRepository
) : ViewModel() {

    val messages = agentRepository.messages
    val isBusy = agentRepository.isBusy
    val pendingApprovals = agentRepository.pendingApprovals
    val selectedAgent = agentRepository.selectedAgent
    val lastError = agentRepository.lastError
    val opencodeProviders = agentRepository.opencodeProviders
    val opencodeModel = agentRepository.opencodeModel
    val opencodeAgent = agentRepository.opencodeAgent
    val opencodeReady = agentRepository.opencodeReady

    val modelLabel: String
        get() = agentRepository.currentModel()

    fun clearError() {
        agentRepository.clearError()
    }

    fun refreshOpencodeModels() {
        viewModelScope.launch {
            agentRepository.refreshOpencodeModels()
        }
    }

    fun setOpencodeModel(providerID: String, modelID: String) {
        agentRepository.setOpencodeModel(providerID, modelID)
    }

    fun selectOpencodeAgent(agent: String) {
        agentRepository.selectOpencodeAgent(agent)
    }

    fun selectAgent(kind: com.codingagent.mobile.domain.AgentKind) {
        agentRepository.selectAgent(kind)
    }

    fun send(text: String) {
        viewModelScope.launch {
            agentRepository.sendMessage(text)
        }
    }

    fun abort() {
        viewModelScope.launch {
            agentRepository.abort()
        }
    }

    fun allowApproval(request: ApprovalRequest) {
        viewModelScope.launch {
            agentRepository.approve(request)
        }
    }

    fun denyApproval(request: ApprovalRequest) {
        viewModelScope.launch {
            agentRepository.deny(request)
        }
    }

    fun clear() {
        agentRepository.clearChat()
    }
}
