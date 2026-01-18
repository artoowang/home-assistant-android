package io.homeassistant.companion.android.glasses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.assist.AssistMessage
import io.homeassistant.companion.android.common.assist.AssistRepository
import javax.inject.Inject
import timber.log.Timber

@HiltViewModel
class GlassesViewModel @Inject constructor(
    private val assistRepository: AssistRepository,
) : ViewModel() {

    // The current input mode, or null if the assist is not yet started.
    val inputMode = assistRepository.inputMode

    // The current list of messages in the conversation.
    val conversation: List<AssistMessage> = assistRepository.conversation

    // Stops the assist session.
    fun stopAssist() {
        Timber.d("ZZZ: stopAssist")
        assistRepository.release(viewModelScope)
    }
}
