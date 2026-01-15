package io.homeassistant.companion.android.assist

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.assist.ui.AssistMessage
import io.homeassistant.companion.android.assist.ui.AssistUiPipeline
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineResponse
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class AssistViewModel @Inject constructor(
    val serverManager: ServerManager,
    private val assistRepository: AssistRepository,
    private val application: Application,
) : ViewModel() {

    companion object {
        const val PIPELINE_PREFERRED = "preferred"
        const val PIPELINE_LAST_USED = "last_used"
    }

    private var filteredServerId: Int? = null
    private val allPipelines = mutableMapOf<Int, List<AssistPipelineResponse>>()
    private var selectedPipeline: AssistPipelineResponse? = null

    private var recorderAutoStart = true
    private var requestPermission: (() -> Unit)? = null
    private var requestSilently = true

    private val startMessage =
        AssistMessage(application.getString(commonR.string.assist_how_can_i_assist), isInput = false)
    private val _conversation = mutableStateListOf(startMessage)
    val conversation: List<AssistMessage> = _conversation

    private val _pipelines = mutableStateListOf<AssistUiPipeline>()
    val pipelines: List<AssistUiPipeline> = _pipelines

    var currentPipeline by mutableStateOf<AssistUiPipeline?>(null)
        private set

    val inputMode by assistRepository.inputMode

    var userCanManagePipelines by mutableStateOf(false)
        private set

    // Returns if the Home Assistant server is registered.
    // TODO: What does that mean?
    suspend fun isRegistered(): Boolean = assistRepository.isRegistered()

    fun onCreate(hasPermission: Boolean, serverId: Int?, pipelineId: String?, startListening: Boolean?) {
        viewModelScope.launch {
            assistRepository.setMode(null)
            assistRepository.hasPermission = hasPermission
            serverId?.let {
                filteredServerId = serverId
                assistRepository.selectedServerId = serverId
            }
            startListening?.let { recorderAutoStart = it }

            if (!serverManager.isRegistered()) {
                assistRepository.setMode(AssistRepository.InputMode.BLOCKED)
                _conversation.clear()
                _conversation.add(
                    AssistMessage(application.getString(commonR.string.not_registered), isInput = false),
                )
                return@launch
            }

            val supported = checkSupport()
            if (supported != true) assistRepository.stopRecording(viewModelScope)
            if (supported == null) { // Couldn't get config
                assistRepository.setMode(AssistRepository.InputMode.BLOCKED)
                _conversation.clear()
                _conversation.add(
                    AssistMessage(application.getString(commonR.string.assist_connnect), isInput = false),
                )
            } else if (!supported) { // Core too old or doesn't include assist pipeline
                assistRepository.setMode(AssistRepository.InputMode.BLOCKED)
                _conversation.clear()
                _conversation.add(
                    AssistMessage(
                        application.getString(
                            commonR.string.no_assist_support,
                            "2023.5",
                            application.getString(commonR.string.no_assist_support_assist_pipeline),
                        ),
                        isInput = false,
                    ),
                )
            } else {
                setPipeline(
                    when {
                        pipelineId == PIPELINE_LAST_USED -> serverManager.integrationRepository(
                            assistRepository.selectedServerId,
                        ).getLastUsedPipelineId()
                        pipelineId == PIPELINE_PREFERRED -> null
                        pipelineId?.isNotBlank() == true -> pipelineId
                        else -> null
                    },
                )
            }

            if (serverManager.isRegistered()) {
                loadPipelines()
            }
            userCanManagePipelines = serverManager.getServer()?.user?.isAdmin == true
        }
    }

    /**
     * Update the state of the Assist dialog for a new 'assistant triggered' action
     * @param intent the updated intent
     * @param lockedMatches whether the locked state changed and contents should be cleared
     */
    fun onNewIntent(intent: Intent, lockedMatches: Boolean) {
        if (
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) ||
            intent.action in
            listOf(Intent.ACTION_ASSIST, "android.intent.action.VOICE_ASSIST", Intent.ACTION_VOICE_COMMAND)
        ) {
            if (!lockedMatches && inputMode != AssistRepository.InputMode.BLOCKED) {
                _conversation.clear()
                _conversation.add(startMessage)
            }
            if (inputMode == AssistRepository.InputMode.VOICE_ACTIVE ||
                    inputMode == AssistRepository.InputMode.VOICE_INACTIVE) {
                onMicrophoneInput()
            }
        }
    }

    private suspend fun checkSupport(): Boolean? {
        if (!serverManager.isRegistered()) return false
        if (!serverManager.integrationRepository(
                assistRepository.selectedServerId,
            ).isHomeAssistantVersionAtLeast(2023, 5, 0)
        ) {
            return false
        }
        return serverManager.webSocketRepository(assistRepository.selectedServerId).getConfig()?.components?.contains("assist_pipeline")
    }

    private suspend fun loadPipelines() {
        val serverIds = filteredServerId?.let { listOf(it) } ?: serverManager.defaultServers.map { it.id }
        serverIds.forEach { serverId ->
            viewModelScope.launch {
                val server = serverManager.getServer(serverId)
                val serverPipelines = serverManager.webSocketRepository(serverId).getAssistPipelines()
                allPipelines[serverId] = serverPipelines?.pipelines ?: emptyList()
                _pipelines.addAll(
                    serverPipelines?.pipelines.orEmpty().map {
                        AssistUiPipeline(
                            serverId = serverId,
                            serverName = server?.friendlyName ?: "",
                            id = it.id,
                            name = it.name,
                        )
                    },
                )
            }
        }
    }

    fun changePipeline(serverId: Int, id: String) = viewModelScope.launch {
        if (serverId == assistRepository.selectedServerId && id == selectedPipeline?.id) return@launch

        assistRepository.stopRecording(viewModelScope, sendRecorded = false)
        assistRepository.stopPlayback()

        assistRepository.selectedServerId = serverId
        setPipeline(id)
    }

    private suspend fun setPipeline(id: String?) {
        selectedPipeline =
            allPipelines[assistRepository.selectedServerId]?.firstOrNull { it.id == id }
                ?: serverManager.webSocketRepository(assistRepository.selectedServerId).getAssistPipeline(id)
        selectedPipeline?.let {
            currentPipeline = AssistUiPipeline(
                serverId = assistRepository.selectedServerId,
                serverName = serverManager.getServer(assistRepository.selectedServerId)?.friendlyName ?: "",
                id = it.id,
                name = it.name,
            )
            serverManager.integrationRepository(assistRepository.selectedServerId).setLastUsedPipeline(it.id, it.sttEngine != null)

            _conversation.clear()
            _conversation.add(startMessage)
            assistRepository.clearPipelineData()
            if (assistRepository.hasMicrophone && it.sttEngine != null) {
                if (recorderAutoStart && (assistRepository.hasPermission || requestSilently)) {
                    assistRepository.setMode(AssistRepository.InputMode.VOICE_INACTIVE)
                    onMicrophoneInput()
                } else { // already requested permission once and was denied
                    assistRepository.setMode(AssistRepository.InputMode.TEXT)
                }
            } else {
                assistRepository.setMode(AssistRepository.InputMode.TEXT_ONLY)
            }
        } ?: run {
            if (!id.isNullOrBlank()) {
                setPipeline(null) // Try falling back to default pipeline
            } else {
                Timber.w("Server ${assistRepository.selectedServerId} does not have any pipelines")
                assistRepository.setMode(AssistRepository.InputMode.BLOCKED)
                _conversation.clear()
                _conversation.add(
                    AssistMessage(application.getString(commonR.string.assist_error), isInput = false),
                )
            }
        }
    }

    // Called to switch between voice and text mode.
    fun onChangeInput() {
        when (inputMode) {
            null, AssistRepository.InputMode.BLOCKED, AssistRepository.InputMode.TEXT_ONLY -> { /* Do nothing */ }
            AssistRepository.InputMode.TEXT -> {
                assistRepository.setMode(AssistRepository.InputMode.VOICE_INACTIVE)
                if (assistRepository.hasPermission || requestSilently) {
                    onMicrophoneInput()
                }
            }
            AssistRepository.InputMode.VOICE_INACTIVE -> {
                assistRepository.setMode(AssistRepository.InputMode.TEXT)
            }
            AssistRepository.InputMode.VOICE_ACTIVE -> {
                assistRepository.stopRecording(viewModelScope, sendRecorded = false)
                assistRepository.setMode(AssistRepository.InputMode.TEXT)
            }
        }
    }

    fun onTextInput(input: String) = runAssistPipeline(input)

    /**
     * Start/stop microphone input for Assist, depending on the current state.
     */
    fun onMicrophoneInput() {
        if (!assistRepository.hasPermission) {
            requestPermission?.let { it() }
            return
        }

        if (inputMode == AssistRepository.InputMode.VOICE_ACTIVE) {
            assistRepository.stopRecording(viewModelScope)
            return
        }

        assistRepository.stopPlayback()

        val recordingStarted = try {
            assistRepository.startRecording(viewModelScope)
        } catch (e: Exception) {
            Timber.e(e, "Exception while starting recording")
            false
        }

        if (recordingStarted) {
            runAssistPipeline(null)
        } else {
            _conversation.add(
                AssistMessage(application.getString(commonR.string.assist_error), isInput = false, isError = true),
            )
        }
    }

    private fun runAssistPipeline(text: String?) {
        Timber.i("ZZZ: runAssistPipeline: $text")

        val isVoice = text == null
        assistRepository.stopPlayback()

        val userMessage = AssistMessage(text ?: "…", isInput = true)
        _conversation.add(userMessage)
        val haMessage = AssistMessage("…", isInput = false)
        if (!isVoice) _conversation.add(haMessage)
        var message = if (isVoice) userMessage else haMessage

        assistRepository.runAssistPipeline(
            viewModelScope,
            text,
            selectedPipeline,
        ) { event ->
            when (event) {
                is AssistEvent.Message -> {
                    _conversation.indexOf(message).takeIf { pos -> pos >= 0 }?.let { index ->
                        val isInput = event is AssistEvent.Message.Input
                        val isError = event is AssistEvent.Message.Error
                        _conversation[index] = message.copy(
                            message = event.message.trim(),
                            isInput = isInput,
                            isError = isError,
                        )
                        if (isInput) {
                            _conversation.add(haMessage)
                            message = haMessage
                        }
                        if (isError && inputMode == AssistRepository.InputMode.VOICE_ACTIVE) {
                            assistRepository.stopRecording(viewModelScope)
                        }
                    }
                }
                is AssistEvent.MessageChunk -> {
                    val lastMessage = _conversation.last()
                    if (lastMessage == haMessage) {
                        // Remove '...' message and add the chunk received
                        _conversation.removeAt(_conversation.lastIndex)
                        _conversation.add(lastMessage.copy(message = event.chunk))
                    } else {
                        // Replace last message with the updated message with the new chunk append
                        _conversation[_conversation.lastIndex] =
                            lastMessage.copy(message = lastMessage.message + event.chunk)
                    }
                }
                is AssistEvent.ContinueConversation -> onMicrophoneInput()
            }
        }
    }

    fun setPermissionInfo(hasPermission: Boolean, callback: () -> Unit) {
        assistRepository.hasPermission = hasPermission
        requestPermission = callback
    }

    fun onPermissionResult(granted: Boolean) {
        assistRepository.hasPermission = granted
        val pipelineReady = currentPipeline != null
        if (granted) {
            assistRepository.setMode(AssistRepository.InputMode.VOICE_INACTIVE)
            if (pipelineReady) {
                onMicrophoneInput()
            }
        } else if (requestSilently && pipelineReady) { // Don't notify the user if they haven't explicitly requested
            assistRepository.setMode(AssistRepository.InputMode.TEXT)
        } else if (!requestSilently) {
            _conversation.add(AssistMessage(application.getString(commonR.string.assist_permission), isInput = false))
        }
        if (pipelineReady) requestSilently = false
    }

    fun onPause() {
        requestPermission = null
        assistRepository.stopRecording(viewModelScope)
    }

    fun onDestroy() {
        requestPermission = null
        assistRepository.stopRecording(viewModelScope)
        assistRepository.stopPlayback()
    }
}
