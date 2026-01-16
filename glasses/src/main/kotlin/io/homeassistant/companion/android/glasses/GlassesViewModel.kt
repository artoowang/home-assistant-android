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

    val conversation: List<AssistMessage> = assistRepository.conversation
}
