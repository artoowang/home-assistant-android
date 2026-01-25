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
import io.homeassistant.companion.android.common.assist.AssistMessage
import io.homeassistant.companion.android.assist.ui.AssistUiPipeline
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.assist.AssistRepository
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

    // Read-only state of the list of the messages.
    val conversation: List<AssistMessage> = assistRepository.conversation

    private val _pipelines = mutableStateListOf<AssistUiPipeline>()
    val pipelines: List<AssistUiPipeline> = _pipelines

    var currentPipeline by mutableStateOf<AssistUiPipeline?>(null)
        private set

    val assistState by assistRepository.assistState

    val supportVoice by assistRepository.supportVoice

    var userCanManagePipelines by mutableStateOf(false)
        private set

    val lastRecordedLevel: Float? by assistRepository.lastRecordedLevel

    // True if the required permissions are granted.
    var hasPermission: Boolean = false

    // Returns if the Home Assistant server is registered with the onboarding.
    suspend fun isRegistered(): Boolean = assistRepository.isRegistered()

    fun onCreate(hasPermission: Boolean, serverId: Int?, pipelineId: String?, startListening: Boolean?) {
        if (assistRepository.assistState.value != null) {
            // Assist session has already started. No-op.
            return
        }

        // Set up the repository synchronously (instead of the in coroutine), so we can make sure they are done before
        // this method returns.
        assistRepository.init()
        this.hasPermission = hasPermission
        serverId?.let {
            filteredServerId = serverId
            assistRepository.selectedServerId = serverId
        }
        startListening?.let { recorderAutoStart = it }

        viewModelScope.launch {
            if (!serverManager.isRegistered()) {
                assistRepository.terminate(application.getString(commonR.string.not_registered))
                return@launch
            }

            val supported = checkSupport()
            if (supported == null) { // Couldn't get config
                assistRepository.terminate(application.getString(commonR.string.assist_connnect))
            } else if (!supported) { // Core too old or doesn't include assist pipeline
                assistRepository.terminate(application.getString(
                    commonR.string.no_assist_support,
                    "2023.5",
                    application.getString(commonR.string.no_assist_support_assist_pipeline),
                ))
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
        Timber.d("ZZZ: onNewIntent: $intent")
        if (
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) ||
            intent.action in
            listOf(Intent.ACTION_ASSIST, "android.intent.action.VOICE_ASSIST", Intent.ACTION_VOICE_COMMAND)
        ) {
            if (!lockedMatches && assistState != AssistRepository.AssistState.TERMINATED) {
                assistRepository.clearConversation()
            }
            if (assistState == AssistRepository.AssistState.VOICE_ACTIVE ||
                    assistState == AssistRepository.AssistState.VOICE_INACTIVE) {
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
        assert(
            assistRepository.assistState.value == AssistRepository.AssistState.TEXT ||
            assistRepository.assistState.value == AssistRepository.AssistState.VOICE_INACTIVE
        ) {
            "UI error: changing pipeline should only be allowed when waiting for user input."
        }

        if (serverId == assistRepository.selectedServerId && id == selectedPipeline?.id) return@launch
        assistRepository.stopPlayback()
        assistRepository.selectedServerId = serverId
        setPipeline(id)
    }

    private suspend fun setPipeline(id: String?) {
        selectedPipeline =
            allPipelines[assistRepository.selectedServerId]?.firstOrNull { it.id == id }
                ?: serverManager.webSocketRepository(assistRepository.selectedServerId).getAssistPipeline(id)
        selectedPipeline?.let { pipeline ->
            currentPipeline = AssistUiPipeline(
                serverId = assistRepository.selectedServerId,
                serverName = serverManager.getServer(assistRepository.selectedServerId)?.friendlyName ?: "",
                id = pipeline.id,
                name = pipeline.name,
            )
            serverManager.integrationRepository(assistRepository.selectedServerId).setLastUsedPipeline(
                pipeline.id,
                pipeline.sttEngine != null)

            assistRepository.clearConversation()
            if (recorderAutoStart && (hasPermission || requestSilently)) {
                assistRepository.setPipeline(
                    pipeline,
                    inputModality = AssistRepository.InputModality.VOICE,
                )
                onMicrophoneInput()
            } else { // already requested permission once and was denied
                assistRepository.setPipeline(
                    pipeline,
                    inputModality = AssistRepository.InputModality.TEXT,
                )
            }
        } ?: run {
            if (!id.isNullOrBlank()) {
                setPipeline(null) // Try falling back to default pipeline
            } else {
                Timber.w("Server ${assistRepository.selectedServerId} does not have any pipelines")
                assistRepository.terminate(application.getString(commonR.string.assist_error))
            }
        }
    }

    // Called to switch between voice and text mode.
    fun onChangeInput() {
        when (assistState) {
            null, AssistRepository.AssistState.TERMINATED, AssistRepository.AssistState.PIPELINE_PENDING,
            AssistRepository.AssistState.INTENT_PROCESSING -> {
                /* Do nothing */
            }

            AssistRepository.AssistState.TEXT -> {
                assert(assistRepository.supportVoice.value) {
                    "UI should not allow change input when voice is not supported"
                }
                assistRepository.switchToVoice()
                if (hasPermission || requestSilently) {
                    onMicrophoneInput()
                }
            }

            AssistRepository.AssistState.VOICE_ACTIVE, AssistRepository.AssistState.VOICE_INACTIVE -> {
                assistRepository.switchToText()
            }
        }
    }

    fun onTextInput(input: String) = runAssistPipeline(input)

    /**
     * Start/stop microphone input for Assist, depending on the current state.
     */
    fun onMicrophoneInput() {
        Timber.d("ZZZ: onMicrophoneInput")
        if (!hasPermission) {
            requestPermission?.let { it() }
            return
        }

        when (assistState) {
            AssistRepository.AssistState.VOICE_ACTIVE -> {
                assistRepository.finishRecordingAndProcessIntent(viewModelScope)
            }

            AssistRepository.AssistState.VOICE_INACTIVE -> {
                runAssistPipeline()
            }

            AssistRepository.AssistState.PIPELINE_PENDING, AssistRepository.AssistState.INTENT_PROCESSING -> {
                // Do nothing when we are waiting for remote response.
                Timber.d("ZZZ: onMicrophoneInput is disabled during PIPELINE_PENDING and INTENT_PROCESSING")
                return
            }

            null, AssistRepository.AssistState.TEXT,
            AssistRepository.AssistState.TERMINATED -> assert(false) {
                "Should not trigger onMicrophoneInput() when assist state is $assistState"
            }
        }
    }

    private fun runAssistPipeline(text: String = "") {
        Timber.i("ZZZ: runAssistPipeline: $text")
        assistRepository.runAssistPipeline(
            viewModelScope,
            text,
        )
    }

    fun setPermissionInfo(granted: Boolean, callback: () -> Unit) {
        hasPermission = granted
        requestPermission = callback
    }

    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted
        val pipelineReady = currentPipeline != null
        if (granted) {
            assistRepository.switchToVoice()
            if (pipelineReady) {
                onMicrophoneInput()
            }
        } else if (requestSilently && pipelineReady) { // Don't notify the user if they haven't explicitly requested
            assistRepository.switchToText()
        } else if (!requestSilently) {
            assistRepository.addErrorMessage(application.getString(commonR.string.assist_permission))
        }
        if (pipelineReady) requestSilently = false
    }

    fun onPause() {
        requestPermission = null
    }

    fun onDestroy() {
        requestPermission = null
        assistRepository.release()
    }
}
