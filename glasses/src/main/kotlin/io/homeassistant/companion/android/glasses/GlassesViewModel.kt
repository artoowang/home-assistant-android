package io.homeassistant.companion.android.glasses

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.assist.AssistMessage
import io.homeassistant.companion.android.common.assist.AssistRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineResponse
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class GlassesViewModel @Inject constructor(
    private val serverManager: ServerManager,
    private val assistRepository: AssistRepository,
) : ViewModel() {

    // The current input mode, or null if the assist is not yet started.
    val inputMode = assistRepository.inputMode

    // The audio level of the last recorded voice input, normalized to a value between 0.0f and 1.0f. null if the mic is
    // not currently recording.
    val lastRecordedLevel: Float? by assistRepository.lastRecordedLevel

    // The current list of messages in the conversation.
    val conversation: List<AssistMessage> = assistRepository.conversation

    // The selected pipeline during startAssistAndRecording().
    private var selectedPipeline: AssistPipelineResponse? = null

    // Starts the assist session and starts to record.
    fun startAssistAndRecording() {
        Timber.d("ZZZ: startAssistAndRecording")

        // TODO: The following is a drastic simplification of what is happening in AssistViewModel. We can potentially
        // consolidate the following with AssistViewModel into AssistRepository.

        assistRepository.init()
        // Use the currently active server.
        val activeServerId = ServerManager.SERVER_ID_ACTIVE
        assistRepository.selectedServerId = activeServerId
        assistRepository.clearConversation()
        assistRepository.clearPipelineData()
        assistRepository.switchToVoice()

        assert(assistRepository.inputMode.value == AssistRepository.InputMode.VOICE_INACTIVE) {
            "Input mode should be VOICE_INACTIVE after the setup."
        }

        if ( try {
            assistRepository.startRecording(viewModelScope)
        } catch (e: Exception) {
            Timber.e(e, "Exception while starting recording")
            false
        }) {
            // Now starts a coroutine to get the pipeline info, and run the pipeline once we have the info.
            viewModelScope.launch {
                selectedPipeline = serverManager
                    .webSocketRepository(activeServerId)
                    .getAssistPipeline(pipelineId = null)
                Timber.d("ZZZ: startAssist: pipeline=$selectedPipeline")
                assistRepository.runAssistPipeline(
                    viewModelScope,
                    text = null,  // Voice input.
                    pipeline = selectedPipeline,
                )
            }
        }
    }

    // Toggles the microphone on or off based on the current input mode.
    fun toggleMicrophone() {
        Timber.d("ZZZ: toggleMicrophone")

        when (inputMode.value) {
            // When voice assist is already active, we want to turn off the microphone.
            // TODO: This needs some work, it does not seem to do what I expected.
            AssistRepository.InputMode.VOICE_ACTIVE -> {
                assistRepository.stopRecording(viewModelScope)
                return
            }

            // When voice assist is not active, or if we are currently using text input (but voice assist is supported),
            // we want to turn on the microphone.
            AssistRepository.InputMode.VOICE_INACTIVE, AssistRepository.InputMode.TEXT -> {
                assistRepository.stopPlayback()
                if (
                    try {
                        assistRepository.startRecording(viewModelScope)
                    } catch (e: Exception) {
                        Timber.e(e, "Exception while starting recording")
                        false
                    }
                ) {
                    assistRepository.runAssistPipeline(
                        viewModelScope,
                        text = null,  // Voice input.
                        pipeline = selectedPipeline,
                    )
                }
            }

            // Otherwise, the microphone is not supported, and UI should not allow this to happen.
            else -> assert(false) { "Should not trigger toggleMicrophone() when input mode is $inputMode" }
        }
    }

    // Stops the assist session.
    fun stopAssist() {
        Timber.d("ZZZ: stopAssist")
        assistRepository.release(viewModelScope)
        selectedPipeline = null
    }
}
