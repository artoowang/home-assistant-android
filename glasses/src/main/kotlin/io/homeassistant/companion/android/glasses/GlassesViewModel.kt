package io.homeassistant.companion.android.glasses

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

    // The current input mode, or null if the assist is not yet started.
    val inputMode = assistRepository.inputMode

    // The current list of messages in the conversation.
    val conversation: List<AssistMessage> = assistRepository.conversation

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
                val pipeline = serverManager
                    .webSocketRepository(activeServerId)
                    .getAssistPipeline(pipelineId = null)
                Timber.d("ZZZ: startAssist: pipeline=$pipeline")
                assistRepository.runAssistPipeline(
                    viewModelScope,
                    text = null,  // Voice input.
                    pipeline = pipeline,
                )
            }
        }
    }

    // Stops the assist session.
    fun stopAssist() {
        Timber.d("ZZZ: stopAssist")
        assistRepository.release(viewModelScope)
    }
}
