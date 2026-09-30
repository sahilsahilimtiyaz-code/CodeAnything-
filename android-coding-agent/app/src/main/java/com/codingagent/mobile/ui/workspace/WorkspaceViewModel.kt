package com.codingagent.mobile.ui.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingagent.mobile.agents.AgentRepository
import com.codingagent.mobile.domain.FileEntry
import com.codingagent.mobile.runtime.RuntimeManager
import com.codingagent.mobile.runtime.resolveExternalRoot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeManager: RuntimeManager,
    private val agentRepository: AgentRepository
) : ViewModel() {

    private val _currentPath = MutableStateFlow(runtimeManager.getWorkspacePath())
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()

    private val _isSaf = MutableStateFlow(false)
    val isSaf: StateFlow<Boolean> = _isSaf.asStateFlow()

    private var safRoot: DocumentFile? = null
    private var safCurrentDoc: DocumentFile? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _entries.value = withContext(Dispatchers.IO) {
                val saf = safCurrentDoc
                if (saf != null) listSafDoc(saf) else listLocal(_currentPath.value)
            }
        }
    }

    fun openDirectory(path: String) {
        if (path.startsWith("content://")) {
            // Navigate within the SAF tree via live DocumentFile references
            // (child URIs are not tree URIs and cannot be re-parsed).
            val current = safCurrentDoc
            val next = when {
                current == null -> null
                current.uri.toString() == path -> current
                else -> current.listFiles().firstOrNull { it.uri.toString() == path }
            }
            if (next != null && next.isDirectory) {
                safCurrentDoc = next
                _currentPath.value = path
                refresh()
            }
            return
        }
        _currentPath.value = path
        refresh()
    }

    fun openFile(path: String) {
        // Future: open file viewer / editor
    }

    fun goUp() {
        val saf = safCurrentDoc
        if (saf != null) {
            if (saf.uri == safRoot?.uri) {
                useAppWorkspace()
            } else {
                // SAF has no cheap parent lookup; return to the tree root.
                safCurrentDoc = safRoot
                _currentPath.value = safRoot?.uri?.toString() ?: _currentPath.value
                refresh()
            }
            return
        }
        val parent = File(_currentPath.value).parentFile ?: return
        val root = runtimeManager.getWorkspacePath()
        if (parent.absolutePath.startsWith(root)) {
            _currentPath.value = parent.absolutePath
            refresh()
        }
    }

    /** Called from the SAF picker: persist access and browse the tree (browse-only). */
    fun openSafTree(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Read-only grant is enough for browsing.
        }
        val root = DocumentFile.fromTreeUri(context, uri)
        safRoot = root
        safCurrentDoc = root
        _isSaf.value = root != null
        _currentPath.value = uri.toString()
        // Primary-volume trees also join the agent sandbox (guest bind);
        // anything else stays browse-only.
        val realPath = resolveExternalRoot(uri.toString())
        if (realPath != null && File(realPath).isDirectory) {
            agentRepository.setExternalRoot(root?.name ?: "external", realPath)
        }
        refresh()
    }

    fun useAppWorkspace() {
        safRoot = null
        safCurrentDoc = null
        _isSaf.value = false
        _currentPath.value = runtimeManager.getWorkspacePath()
        agentRepository.clearExternalRoots()
        refresh()
    }

    private fun listLocal(path: String): List<FileEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map {
                FileEntry(
                    name = it.name,
                    path = it.absolutePath,
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else null
                )
            } ?: emptyList()
    }

    private fun listSafDoc(dir: DocumentFile): List<FileEntry> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .sortedWith(compareBy({ !(it.isDirectory) }, { (it.name ?: "").lowercase() }))
            .map {
                FileEntry(
                    name = it.name ?: "(unnamed)",
                    path = it.uri.toString(),
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length().takeIf { l -> l >= 0 } else null
                )
            }
    }
}
