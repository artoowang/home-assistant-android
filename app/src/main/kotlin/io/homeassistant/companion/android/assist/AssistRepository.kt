package io.homeassistant.companion.android.assist

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.assist.AssistRepository.InputMode
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.assist.AssistViewModelBase.AssistInputMode
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineError
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineEventType
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineIntentProgress
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineResponse
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineRunStart
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineSttEnd
import io.homeassistant.companion.android.common.data.websocket.impl.entities.AssistPipelineTtsEnd
import io.homeassistant.companion.android.common.util.AudioRecorder
import io.homeassistant.companion.android.common.util.AudioUrlPlayer
import io.homeassistant.companion.android.util.UrlUtil
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

// The following are copied from AssistViewModelBase.kt.
// This is to make the core logic a singleton. We can't remove
// AssistViewModelBase.kt since it is still used elsewhere.

sealed interface AssistEvent {
    sealed class Message(val message: String) : AssistEvent {
        class Input(message: String) : Message(message)
        class Output(message: String) : Message(message)
        class Error(message: String) : Message(message)
    }
    class MessageChunk(val chunk: String) : AssistEvent
    data object ContinueConversation : AssistEvent
}

// This class represents the core logic and states of the Voice Assist.
// It is going to be shared between multiple view models (e.g., one for mobile, another for
// glasses).
interface AssistRepository {

    enum class InputMode {
        // For when the user is expected to type their request.
        TEXT,
        // Used when only text input is supported, for example, if the device has no microphone or the speech-to-text
        // service is unavailable. In this mode, there won't be button to switch to voice input.
        TEXT_ONLY,
        // The voice input mode is ready but not currently listening.
        VOICE_INACTIVE,
        // The microphone is actively listening for the user's voice command.
        VOICE_ACTIVE,
        // The assist feature is unavailable, for instance, if the app is not registered with a Home Assistant server.
        BLOCKED,
    }

    // Input mode state for Composable to react. null means the repository is not ready yet.
    val inputMode: State<InputMode?>

    // The ID of the selected Home Assistant server.
    var selectedServerId: Int
    // True if the required permissions are granted.
    var hasPermission: Boolean

    // True if the system has microphone support.
    val hasMicrophone: Boolean

    // Returns if the Home Assistant server is registered through the onboarding process.
    suspend fun isRegistered(): Boolean

    // Sets the desired input mode. See AssistantRepository.InputMode.
    // TODO: We should try to move AssistViewModel away from using this as much as possible: instead of calling this
    // directly, it should call some desired function from AssistRepository (e.g., startRecording, stopRecording), and
    // the input mode is purely driven internally.
    fun setMode(mode: InputMode?)

    /**
     * @param text input to run an intent pipeline with, or `null` to run a STT pipeline (check if
     * STT is supported _before_ calling this function)
     * @param pipeline information about the pipeline, or `null` to use the server's default
     * @param onEvent callback for events that should be use to update the UI
     */
    fun runAssistPipeline(
        scope: CoroutineScope,
        text: String?,
        pipeline: AssistPipelineResponse?,
        onEvent: (AssistEvent) -> Unit,
    )

    // Starts audio recorder.
    fun startRecording(): Boolean

    // TODO: What is this for?
    fun setupRecorderQueue(scope: CoroutineScope)

    // Stops audio recorder.
    fun stopRecording(scope: CoroutineScope, sendRecorded: Boolean = true)

    // Stops audio playback.
    fun stopPlayback()

    fun clearPipelineData()
}

@Singleton
class AssistRepositoryImpl @Inject constructor(
    private val serverManager: ServerManager,
    private val audioRecorder: AudioRecorder,
    private val audioUrlPlayer: AudioUrlPlayer,
    private val application: Application,
) : AssistRepository {

    private val _inputMode = mutableStateOf<InputMode?>(null)
    override val inputMode: State<InputMode?> = _inputMode

    override var selectedServerId = ServerManager.SERVER_ID_ACTIVE

    // Audio recorder states.
    private var recorderJob: Job? = null
    private var recorderQueue: MutableList<ByteArray>? = null

    override val hasMicrophone by lazy {
        application.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
    override var hasPermission = false

    // Pipeline data.
    private var binaryHandlerId: Int? = null
    private var conversationId: String? = null

    private var continueConversation = AtomicBoolean(false)

    override suspend fun isRegistered(): Boolean = serverManager.isRegistered()

    override fun setMode(mode: InputMode?) {
        Timber.d("Input mode changed: ${inputMode.value} -> $mode")
        _inputMode.value = mode
    }

    override fun clearPipelineData() {
        binaryHandlerId = null
        conversationId = null
    }

    override fun runAssistPipeline(
        scope: CoroutineScope,
        text: String?,
        pipeline: AssistPipelineResponse?,
        onEvent: (AssistEvent) -> Unit,
    ) {
        val isVoice = text == null
        var job: Job? = null
        job = scope.launch {
            val flow = if (isVoice) {
                serverManager.webSocketRepository(selectedServerId).runAssistPipelineForVoice(
                    sampleRate = AudioRecorder.SAMPLE_RATE,
                    outputTts = pipeline?.ttsEngine?.isNotBlank() == true,
                    pipelineId = pipeline?.id,
                    conversationId = conversationId,
                )
            } else {
                serverManager.integrationRepository(selectedServerId).getAssistResponse(
                    text = text,
                    pipelineId = pipeline?.id,
                    conversationId = conversationId,
                )
            }

            flow?.collect {
                when (it.type) {
                    AssistPipelineEventType.RUN_START -> {
                        if (!isVoice) return@collect
                        val data = (it.data as? AssistPipelineRunStart)?.runnerData
                        binaryHandlerId = data?.get("stt_binary_handler_id") as? Int
                    }
                    AssistPipelineEventType.STT_START -> {
                        scope.launch {
                            binaryHandlerId?.let { id ->
                                // Manually loop here to avoid the queue being reset too soon
                                recorderQueue?.forEach { data ->
                                    serverManager.webSocketRepository(selectedServerId).sendVoiceData(id, data)
                                }
                            }
                            recorderQueue = null
                        }
                    }
                    AssistPipelineEventType.STT_END -> {
                        stopRecording(scope)
                        (it.data as? AssistPipelineSttEnd)?.sttOutput?.let { response ->
                            onEvent(AssistEvent.Message.Input(response["text"] as String))
                        }
                    }
                    AssistPipelineEventType.INTENT_PROGRESS -> {
                        (it.data as? AssistPipelineIntentProgress)?.chatLogDelta?.content?.let { delta ->
                            onEvent(AssistEvent.MessageChunk(delta))
                        }
                    }
                    AssistPipelineEventType.INTENT_END -> {
                        val data = (it.data as? AssistPipelineIntentEnd)?.intentOutput ?: return@collect
                        conversationId = data.conversationId
                        continueConversation.set(data.continueConversation)
                        data.response.speech?.plain?.get("speech")?.let { speech ->
                            onEvent(AssistEvent.Message.Output(speech))
                        }
                    }
                    AssistPipelineEventType.TTS_END -> {
                        if (!isVoice) return@collect
                        scope.launch {
                            val audioPath = (it.data as? AssistPipelineTtsEnd)?.ttsOutput?.url
                            if (!audioPath.isNullOrBlank()) {
                                playAudio(audioPath)
                            }
                            // We send the continueConversation flag here after getting it from AssistPipelineEventType.INTENT_END so that
                            // we let the mediaplayer finishing playing the audio before recording a new entry from the user.
                            if (continueConversation.getAndSet(false)) {
                                onEvent(AssistEvent.ContinueConversation)
                            }
                        }
                    }
                    AssistPipelineEventType.RUN_END -> {
                        stopRecording(scope)
                        job?.cancel()
                    }
                    AssistPipelineEventType.ERROR -> {
                        val errorMessage = (it.data as? AssistPipelineError)?.message ?: return@collect
                        onEvent(AssistEvent.Message.Error(errorMessage))
                        stopRecording(scope)
                        job?.cancel()
                    }
                    else -> { /* Do nothing */ }
                }
            } ?: run {
                onEvent(AssistEvent.Message.Output(application.getString(R.string.assist_error)))
            }
        }
    }

    override fun startRecording(): Boolean {
        return try {
            audioRecorder.startRecording()
        } catch (e: Exception) {
            Timber.e(e, "Exception while starting recording")
            false
        }
    }

    override fun setupRecorderQueue(scope: CoroutineScope) {
        check(recorderQueue == null) { "recorderQueue should be null before setting up a new one" }
        recorderQueue = mutableListOf()
        recorderJob = scope.launch {
            audioRecorder.audioBytes.collect {
                recorderQueue?.add(it) ?: sendVoiceData(scope, it)
            }
        }
    }

    private fun sendVoiceData(scope: CoroutineScope, data: ByteArray) {
        binaryHandlerId?.let {
            scope.launch {
                // Launch to prevent blocking the output flow if the network is slow
                serverManager.webSocketRepository(selectedServerId).sendVoiceData(it, data)
            }
        }
    }

    private suspend fun playAudio(path: String): Boolean {
        val urlState = serverManager.connectionStateProvider(selectedServerId).urlFlow().first()
        val baseUrl = if (urlState is UrlState.HasUrl) {
            urlState.url
        } else {
            null
        }
        return UrlUtil.handle(baseUrl, path)?.let {
            audioUrlPlayer.playAudio(it.toString())
        } ?: false
    }

    override fun stopRecording(scope: CoroutineScope, sendRecorded: Boolean) {
        audioRecorder.stopRecording()
        recorderJob?.cancel()
        recorderJob = null
        if (binaryHandlerId != null) {
            scope.launch {
                if (sendRecorded) {
                    recorderQueue?.forEach {
                        sendVoiceData(scope, it)
                    }
                    sendVoiceData(scope, byteArrayOf()) // Empty message to indicate end of recording
                }
                recorderQueue = null
                binaryHandlerId = null
            }
        } else {
            recorderQueue = null
        }

        if (_inputMode.value == InputMode.VOICE_ACTIVE) {
            setMode(InputMode.VOICE_INACTIVE)
        } else {
            Timber.e("stopRecording is called when inputMode is ${_inputMode.value}, not VOICE_ACTIVE")
        }
    }

    override fun stopPlayback() = audioUrlPlayer.stop()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistRepositoryModule {
    @Binds
    abstract fun bindAssistRepository(impl: AssistRepositoryImpl): AssistRepository
}
