package io.homeassistant.companion.android.common.assist

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.assist.AssistRepository.AssistEvent
import io.homeassistant.companion.android.common.assist.AssistRepository.InputMode
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
import timber.log.Timber

val ASSIST_FINISH_ACTION = "io.homeassistant.companion.android.ASSIST_FINISH"

// The following are copied from AssistViewModelBase.kt.
// This is to make the core logic a singleton. We can't remove
// AssistViewModelBase.kt since it is still used elsewhere.

// This class represents the core logic and states of the Voice Assist.
// It is going to be shared between multiple view models (e.g., one for mobile, another for
// glasses).
interface AssistRepository {

    sealed interface AssistEvent {
        sealed class Message(val message: String) : AssistEvent {
            class Input(message: String) : Message(message)
            class Output(message: String) : Message(message)
            class Error(message: String) : Message(message)
        }
        class MessageChunk(val chunk: String) : AssistEvent
    }

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

    // Input mode state for Composable to react. null means the assist is not yet started.
    val inputMode: State<InputMode?>

    // The ID of the selected Home Assistant server.
    var selectedServerId: Int
    // True if the required permissions are granted.
    var hasPermission: Boolean

    // True if the system has microphone support.
    val hasMicrophone: Boolean

    // Read-only state of the list of messages.
    val conversation: List<AssistMessage>

    // The audio level of the last recorded voice input, normalized to a value between 0.0f and 1.0f. null if the mic is
    // not currently recording.
    val lastRecordedLevel: State<Float?>

    // Returns if the Home Assistant server is registered through the onboarding process.
    suspend fun isRegistered(): Boolean

    // Initializes the repository. This should be called once when a voice assist session first starts.
    fun init()

    // Releases the repository. This should be called once when the voice assist sessions stops.
    fun release(scope: CoroutineScope)

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
     */
    fun runAssistPipeline(
        scope: CoroutineScope,
        text: String?,
        pipeline: AssistPipelineResponse?,
    )

    // Starts audio recorder.
    fun startRecording(scope: CoroutineScope): Boolean

    // Stops audio recorder.
    fun stopRecording(scope: CoroutineScope, sendRecorded: Boolean = true)

    // Stops audio playback.
    fun stopPlayback()

    // Marks the assist is blocked and cannot function. `errorMsg` will be shown in the conversation.
    fun markBlocked(errorMsg: String)

    // Clears the conversation.
    fun clearConversation()

    // Adds an error message to the conversation. This is used when we have an external error outside of
    // AssistRepository, but we still want to show the error in the conversation.
    fun addErrorMessage(message: String)
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

    private val _lastRecordedLevel = mutableStateOf<Float?>(null)
    override val lastRecordedLevel = _lastRecordedLevel

    // -----------------------------------------------------------------------------------------------------------------
    // Conversation messages.

    // Initial message at the beginning of the conversation.
    private val startMessage =
        AssistMessage(application.getString(R.string.assist_how_can_i_assist), isInput = false)

    // Implementation of the state of the list of messages.
    private val _conversation = mutableStateListOf(startMessage)
    override val conversation: List<AssistMessage> = _conversation

    // -----------------------------------------------------------------------------------------------------------------
    // Pipeline data.
    private var binaryHandlerId: Int? = null
    private var conversationId: String? = null

    private var continueConversation = AtomicBoolean(false)

    override suspend fun isRegistered(): Boolean = serverManager.isRegistered()

    override fun init() {
        Timber.d("ZZZ: init")
        assert(!audioRecorder.isRecording()) { "Audio recorder is already recording at init." }
        assert(recorderQueue == null) { "recorderQueue should be null at init." }
        assert(recorderJob == null) { "recorderJob should be null at init." }

        audioRecorder.setupRecorder()
    }

    override fun release(scope: CoroutineScope) {
        Timber.d("ZZZ: release")

        stopRecording(scope)
        stopPlayback()

        setMode(null)
        selectedServerId = ServerManager.SERVER_ID_ACTIVE
        hasPermission = false
        clearPipelineData()
        clearConversation()
        continueConversation.set(false)

        audioRecorder.releaseRecorder()
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
    ) {
        val isVoice = text == null

        stopPlayback()

        // Initial user message is "…" if using voice input (i.e., `text` is null), or the actually provided initial
        // input (i.e., `text`) otherwise.
        val initialUserMessage = AssistMessage(text ?: "…", isInput = true)
        _conversation.add(initialUserMessage)

        // Placeholder Home Assistant response (i.e., "…") when we have the user input and are waiting for the response.
        // - For voice input, this is added when we receive the STT result (AssistEvent.Message.Input).
        // - For text input, this is added immediately below since the user input is already added.
        val haPlaceholderMessage = AssistMessage("…", isInput = false)

        // This is a reference to the last placeholder message currently in the conversation.
        var lastPlaceholderMessage = if (isVoice) {
            // For voice input, it is the initial placeholder user message (since we are waiting for the STT result).
            initialUserMessage
        } else {
            // For text input, it is the placeholder assistant message (since the user message is already added).
            _conversation.add(haPlaceholderMessage)
            haPlaceholderMessage
        }

        // TODO: We can probably merge this into the flow handling below, and skip all AssistEvents
        fun onAssistEvent(event: AssistEvent) {
            when (event) {
                // Complete user (input) or assistant (output) message:
                // - User messages represent the STT outputs, and we only get these messages with voice assist. (Text
                //   input is provided directly through `text`).
                // - Assistant messages represent the Home Assistant responses.
                is AssistEvent.Message -> {
                    // The `lastPlaceholderMessage` is not necessarily in the conversation:
                    // - If it is not in the conversation, it means we are not doing voice input (so no input
                    //   placeholder), and the output is already replaced by MessageChunks. In such case, we don't add
                    //   the new message in the event.
                    //   TODO: This does mean that we lose the potential error message.
                    // - If it is still in the conversation, we then replace the last placeholder with the incoming
                    //   message. If the event is an input message, we also add a new placeholder for the output, and
                    //   update the last placeholder reference accordingly.
                    // TODO: It seems we can make this easier to read by separately handle input/output/error messages.
                    _conversation.indexOf(lastPlaceholderMessage).takeIf { pos -> pos >= 0 }?.let { index ->
                        val isInput = event is AssistEvent.Message.Input
                        val isError = event is AssistEvent.Message.Error
                        _conversation[index] = AssistMessage(
                            message = event.message.trim(),
                            isInput = isInput,
                            isError = isError,
                        )
                        if (isInput) {
                            _conversation.add(haPlaceholderMessage)
                            lastPlaceholderMessage = haPlaceholderMessage
                        }
                    }
                }
                is AssistEvent.MessageChunk -> {
                    val lastMessage = _conversation.last()
                    if (lastMessage == haPlaceholderMessage) {
                        // Remove "…" message and add the chunk received
                        _conversation.removeAt(_conversation.lastIndex)
                        _conversation.add(lastMessage.copy(message = event.chunk))
                    } else {
                        // Replace last message with the updated message with the new chunk append
                        _conversation[_conversation.lastIndex] =
                            lastMessage.copy(message = lastMessage.message + event.chunk)
                    }
                }
            }
        }

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
                            onAssistEvent(AssistEvent.Message.Input(response["text"] as String))
                        }
                    }
                    AssistPipelineEventType.INTENT_PROGRESS -> {
                        (it.data as? AssistPipelineIntentProgress)?.chatLogDelta?.content?.let { delta ->
                            onAssistEvent(AssistEvent.MessageChunk(delta))
                        }
                    }
                    AssistPipelineEventType.INTENT_END -> {
                        val data = (it.data as? AssistPipelineIntentEnd)?.intentOutput ?: return@collect
                        conversationId = data.conversationId
                        continueConversation.set(data.continueConversation)
                        data.response.speech?.plain?.get("speech")?.let { speech ->
                            onAssistEvent(AssistEvent.Message.Output(speech))
                        }
                    }
                    AssistPipelineEventType.TTS_END -> {
                        if (!isVoice) return@collect
                        scope.launch {
                            val audioPath = (it.data as? AssistPipelineTtsEnd)?.ttsOutput?.url
                            if (!audioPath.isNullOrBlank()) {
                                playAudio(audioPath)
                            }
                            // TODO: Update comments
                            // We send the continueConversation flag here after getting it from AssistPipelineEventType.INTENT_END so that
                            // we let the mediaplayer finishing playing the audio before recording a new entry from the user.
                            if (continueConversation.getAndSet(false)) {
                                // TODO: Can we continue to runAssistPipeline from here? Or do we need to do it from
                                // AVM?
                            }
                        }
                    }
                    AssistPipelineEventType.RUN_END -> {
                        stopRecording(scope)
                        job?.cancel()
                    }
                    AssistPipelineEventType.ERROR -> {
                        val errorMessage = (it.data as? AssistPipelineError)?.message ?: return@collect
                        onAssistEvent(AssistEvent.Message.Error(errorMessage))
                        stopRecording(scope)
                        job?.cancel()
                    }
                    else -> { /* Do nothing */ }
                }
            } ?: run {
                onAssistEvent(AssistEvent.Message.Output(application.getString(R.string.assist_error)))
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
            addErrorMessage(application.getString(R.string.assist_error))
            return false;
        }

        recorderQueue = mutableListOf()
        recorderJob = scope.launch {
            audioRecorder.audioBytes.collect {
                var sumOfAbsValues = 0.0f
                val sampleCount = it.size / 2
                for (i in 0 until sampleCount) {
                    val byteIndex = i * 2
                    val lsb = it[byteIndex].toInt() and 0xFF
                    val msb = it[byteIndex + 1].toInt()
                    val sample = ((msb shl 8) or lsb).toShort()
                    sumOfAbsValues += kotlin.math.abs(sample.toFloat())
                }
                val meanAbsValue = (sumOfAbsValues / sampleCount) / 65536.0f
                _lastRecordedLevel.value = kotlin.math.sqrt(meanAbsValue)

                // Audio data is handled in 2 different states:
                // 1) Buffering state: When recording starts, we still need to wait for the Home Assistant server to
                //    tell us where to stream the audio data. During this period, recorderQueue exists to buffer the
                //    data.
                // 2) Streaming State (recorderQueue is null): Once the server provides the info, we send all the
                //    buffered audio from recorderQueue, and send new data directly to the server.
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
        _lastRecordedLevel.value = null

        setMode(InputMode.VOICE_INACTIVE)
    }

    override fun stopPlayback() = audioUrlPlayer.stop()

    override fun markBlocked(errorMsg: String) {
        assert(_inputMode.value != InputMode.VOICE_ACTIVE) {
            "Assist function should be blocked before it starts to record."
        }
        setMode(InputMode.BLOCKED)
        _conversation.clear()
        addErrorMessage(errorMsg)
    }

    override fun clearConversation() {
        _conversation.clear()
        _conversation.add(startMessage)
    }

    override fun addErrorMessage(message: String) {
        _conversation.add(AssistMessage(message, isInput = false, isError = true))
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
