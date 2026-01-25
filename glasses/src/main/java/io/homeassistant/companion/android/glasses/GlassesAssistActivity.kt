package io.homeassistant.companion.android.glasses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import androidx.xr.glimmer.GlimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.assist.AssistRepository
import kotlin.getValue
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class GlassesAssistActivity : ComponentActivity() {
    private val viewModel: GlassesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("ZZZ: onCreate: savedInstanceState=$savedInstanceState, viewModel=$viewModel")

        viewModel.maybeStartAssistAndRecording()

        // Starts a coroutine that monitors the assist state. When it becomes null, it means the assist session has been
        // closed, so we finish the activity. The assist state signal is State<AssistState?>, which automatically
        // de-duplicate repeated signals, so we won't get repeated it == null signals.
        lifecycleScope.launch {
            snapshotFlow { viewModel.assistState.value }
                .filter { it == null }
                .collect {
                    Timber.d("ZZZ: Assist State is null, closing activity.")
                    finish()
                }
        }

        setContent {
            GlimmerTheme {
                val assistState = viewModel.assistState.value
                if (assistState != null) {
                    VoiceAssistScreen(
                        micState = buildMicState(
                            assistState = assistState,
                            lastRecordedLevel = viewModel.lastRecordedLevel
                        ),
                        conversation = viewModel.conversation,
                        toggleMicrophone = { viewModel.toggleMicrophone() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("ZZZ: onResume")
    }

    override fun onPause() {
        super.onPause()
        Timber.d("ZZZ: onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("ZZZ: onDestroy")
    }

    // Builds the MicState for UI.
    // TODO: This is to practice separating ViewModel states from UI / Composable states.
    private fun buildMicState(
        assistState: AssistRepository.AssistState,
        lastRecordedLevel: Float?,
    ): MicState? {
        return when (assistState) {
            // Mic is recording and shows last recorded level when assist state is VOICE_ACTIVE.
            AssistRepository.AssistState.VOICE_ACTIVE -> {
                MicState(
                    recording = true,
                    // If there is no last recorded level available, it means the first mic sample has not yet arrived, or
                    // something is wrong. Assume 0.
                    lastRecordedLevel = lastRecordedLevel ?: 0.0f,
                )
            }
            // Mic is not recording and shows 0 level when assist state is VOICE_INACTIVE.
            AssistRepository.AssistState.VOICE_INACTIVE -> MicState(
                recording = false,
                lastRecordedLevel = 0.0f,
            )
            // Mic is disabled otherwise (e.g., VOICE_TEXT).
            else -> null
        }
    }
}
