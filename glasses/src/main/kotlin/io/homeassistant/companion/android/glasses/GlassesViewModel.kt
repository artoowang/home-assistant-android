package io.homeassistant.companion.android.glasses

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.assist.AssistMessage
import io.homeassistant.companion.android.common.assist.AssistRepository
import javax.inject.Inject

@HiltViewModel
class GlassesViewModel @Inject constructor(
    private val assistRepository: AssistRepository,
) : ViewModel() {

    // The current input mode, or null if the assist is not yet started.
    val inputMode = assistRepository.inputMode

    // The current list of messages in the conversation.
    val conversation: List<AssistMessage> = assistRepository.conversation
}
