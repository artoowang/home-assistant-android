package io.homeassistant.companion.android.glasses

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.assist.AssistMessage
import io.homeassistant.companion.android.common.assist.AssistRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class GlassesViewModel @Inject constructor(
    private val serverManager: ServerManager,
    private val assistRepository: AssistRepository,
) : ViewModel() {

    // The current assist state, or null if the assist is not yet started.
    val assistState = assistRepository.assistState

    // The audio level of the last recorded voice input, normalized to a value between 0.0f and 1.0f. null if the mic is
    // not currently recording.
    val lastRecordedLevel: Float? by assistRepository.lastRecordedLevel

    // The current list of messages in the conversation.
    val conversation: List<AssistMessage> = assistRepository.conversation

    // Starts the assist session and starts to record.
    fun startAssistAndRecording() {
        Timber.d("ZZZ: startAssistAndRecording, viewModel=$this")

        // TODO: The following is a drastic simplification of what is happening in AssistViewModel. We can potentially
        // consolidate the following with AssistViewModel into AssistRepository.

        assistRepository.init()
        // Use the currently active server.
        val activeServerId = ServerManager.SERVER_ID_ACTIVE
        assistRepository.selectedServerId = activeServerId
        assistRepository.clearConversation()

        // Now starts a coroutine to get the pipeline info, and run the pipeline once we have the info.
        viewModelScope.launch {
            val pipeline = serverManager
                .webSocketRepository(activeServerId)
                .getAssistPipeline(pipelineId = null)
            Timber.d("ZZZ: startAssistAndRecording: pipeline=$pipeline")
            if (pipeline != null) {
                assistRepository.setPipeline(
                    pipeline,
                    inputModality = AssistRepository.InputModality.VOICE,
                )
                // TODO: Handle failure
                assistRepository.startRecording(viewModelScope)
                assistRepository.runAssistPipeline(
                    viewModelScope,
                    text = null,  // Voice input.
                )
            } else {
                // TODO: we should handle the case when pipeline is null.
                assert(false)
            }
        }
    }

    // Toggles the microphone on or off based on the current state.
    fun toggleMicrophone() {
        Timber.d("ZZZ: toggleMicrophone")

        when (assistState.value) {
            // When voice assist is already active, we want to turn off the microphone.
            AssistRepository.AssistState.VOICE_ACTIVE -> {
                assistRepository.finishRecordingAndProcessIntent(viewModelScope)
                return
            }

            // When voice assist is not active, or if we are currently using text input (but voice assist is supported),
            // we want to turn on the microphone.
            AssistRepository.AssistState.VOICE_INACTIVE, AssistRepository.AssistState.TEXT -> {
                assistRepository.stopPlayback()
                // TODO: Handle failure
                assistRepository.startRecording(viewModelScope)
                assistRepository.runAssistPipeline(
                    viewModelScope,
                    text = null,  // Voice input.
                )
            }

            AssistRepository.AssistState.PIPELINE_PENDING, AssistRepository.AssistState.INTENT_PROCESSING -> {
                // Do nothing when we are waiting for remote response.
                Timber.d("ZZZ: toggleMicrophone is disabled during PIPELINE_PENDING and INTENT_PROCESSING")
            }

            // Otherwise, the microphone is not used, and UI should not allow this to happen.
            null, AssistRepository.AssistState.TERMINATED -> assert(false) {
                "Should not trigger toggleMicrophone() when assist state is ${assistState.value}"
            }
        }
    }

    // This is invoked when the Activity it is associated with is destroyed.
    override fun onCleared() {
        super.onCleared()
        Timber.d("ZZZ: onCleared")

        assistRepository.release()
    }
}
