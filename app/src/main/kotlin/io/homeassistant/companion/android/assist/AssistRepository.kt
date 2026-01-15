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
import kotlinx.coroutines.selects.select
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

    // Initializes the repository. This should be called when a voice assist session first started.
    fun init()

    // Clears pipeline related data.
    fun clearPipelineData()

    // Changes assist to voice mode.
    fun switchToVoice()

    // Changes assist to text mode. Set `textOnly` to true to indicate voice assist mode is not supported.
    fun switchToText(scope: CoroutineScope, textOnly: Boolean = false)

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
    fun startRecording(scope: CoroutineScope): Boolean

    // Stops audio recorder.
    fun stopRecording(scope: CoroutineScope, sendRecorded: Boolean = true)

    // Stops audio playback.
    fun stopPlayback()

    // Marks the assist is blocked and cannot function.
    fun markBlocked()
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

    override fun init() {
        assert(!audioRecorder.isRecording()) { "Audio recorder is already recording at init." }
        assert(recorderQueue == null) { "recorderQueue should be null at init." }
        assert(recorderJob == null) { "recorderJob should be null at init." }

        setMode(null)
        selectedServerId = ServerManager.SERVER_ID_ACTIVE
        hasPermission = false
        clearPipelineData();
        continueConversation.set(false)
    }

    override fun clearPipelineData() {
        binaryHandlerId = null
        conversationId = null
    }

    override fun switchToVoice() {
        assert(_inputMode.value != InputMode.BLOCKED) { "Cannot switch to voice assit since assist is blocked." }
        if (_inputMode.value == InputMode.VOICE_ACTIVE || _inputMode.value == InputMode.VOICE_INACTIVE) {
            Timber.w("Assist is already in voice mode: ${inputMode.value}")
            return
        }
        setMode(InputMode.VOICE_INACTIVE)
    }

    override fun switchToText(scope: CoroutineScope, textOnly: Boolean) {
        assert(_inputMode.value != InputMode.BLOCKED) { "Cannot switch to voice assit since assist is blocked." }
        if (_inputMode.value == InputMode.TEXT || _inputMode.value == InputMode.TEXT_ONLY) {
            Timber.w("Assist is already in text mode: ${inputMode.value}")
            return
        }

        if (_inputMode.value == InputMode.VOICE_ACTIVE) {
            // Stop the current recording (and discard the recorded data).
            stopRecording(scope, sendRecorded = false)
        }

        if (textOnly) {
            setMode(InputMode.TEXT_ONLY)
        } else {
            setMode(InputMode.TEXT)
        }
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

    override fun startRecording(scope: CoroutineScope): Boolean {
        assert(!audioRecorder.isRecording()) { "audioRecorder should not be recording before start recording" }
        assert(recorderQueue == null) { "recorderQueue should be null before start recording" }
        assert(recorderJob == null) { "recorderJob should be null before start recording" }

        val recordingStarted = try {
            audioRecorder.startRecording()
        } catch (e: Exception) {
            Timber.e(e, "Exception while starting recording")
            false
        }
        if (!recordingStarted) {
            return false;
        }

        recorderQueue = mutableListOf()
        recorderJob = scope.launch {
            audioRecorder.audioBytes.collect {
                recorderQueue?.add(it) ?: sendVoiceData(scope, it)
            }
        }
        setMode(InputMode.VOICE_ACTIVE)
        return true
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
        if (_inputMode.value != InputMode.VOICE_ACTIVE) {
            // No action needed. Let's check for error condition, but not try to fix them.
            assert(!audioRecorder.isRecording()) {
                "audioRecorder should not be recording in input mode ${_inputMode.value}"
            }
            assert(recorderJob == null) { "There should be no recorderJob in input mode ${_inputMode.value}" }
            return
        }

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

        setMode(InputMode.VOICE_INACTIVE)
    }

    override fun stopPlayback() = audioUrlPlayer.stop()

    override fun markBlocked() {
        assert(_inputMode.value != InputMode.VOICE_ACTIVE) {
            "Assist function should be blocked before it starts to record."
        }
        setMode(InputMode.BLOCKED)
    }

    // Sets the desired input mode. See AssistantRepository.InputMode.
    private fun setMode(mode: InputMode?) {
        Timber.d("Input mode changed: ${inputMode.value} -> $mode")
        _inputMode.value = mode
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistRepositoryModule {
    @Binds
    abstract fun bindAssistRepository(impl: AssistRepositoryImpl): AssistRepository
}
