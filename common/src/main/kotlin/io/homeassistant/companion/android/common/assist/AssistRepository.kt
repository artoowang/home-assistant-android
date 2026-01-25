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
import io.homeassistant.companion.android.common.assist.AssistRepository.AssistState
import io.homeassistant.companion.android.common.R
import io.homeassistant.companion.android.common.assist.AssistRepository.InputModality
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

    enum class AssistState {
        // When the assist session is waiting for remote response. Input should be disabled in this mode.
        WAITING,
        // For when the user is expected to type their request.
        TEXT,
        // The voice assist state is ready but not currently listening.
        VOICE_INACTIVE,
        // The microphone is actively listening for the user's voice command.
        VOICE_ACTIVE,
        // The assist feature is unavailable, for instance, if the app is not registered with a Home Assistant server.
        BLOCKED,
    }

    // Indicates the input modality used by assist.
    enum class InputModality {
        TEXT,
        VOICE,
    }

    // Assist state for Composable to react. null means the assist is not yet started.
    val assistState: State<AssistState?>

    // Indicates the current input modality, or null if it hasn't been specified.
    val inputModality: State<InputModality?>

    // The ID of the selected Home Assistant server.
    var selectedServerId: Int

    // True if the system has microphone support.
    val hasMicrophone: Boolean

    // True if voice assist is supported.
    val supportVoice: State<Boolean>

    // Read-only state of the list of messages.
    val conversation: List<AssistMessage>

    // The audio level of the last recorded voice input, normalized to a value between 0.0f and 1.0f. null if the mic
    // has not received recording.
    val lastRecordedLevel: State<Float?>

    // Returns if the Home Assistant server is registered through the onboarding process.
    suspend fun isRegistered(): Boolean

    // Creates the AudioRecord. This needs to be called once at the app start, and the AudioRecord will last the entire
    // lifespan of the app.
    // TODO: Currently we need to explicitly create AudioRecord before GlassesActivity is launched, or otherwise the
    // microphone records just silence (and without any warning or error). This is likely a bug, and once that is fixed,
    // it is better to have init() to create / release() to destroy AudioRecord to save resources.
    fun setupRecorder()

    // Initializes the repository. This should be called when a voice assist session first starts. It can happen through
    // multiple UIs (e.g., Assist sheet on the mobile, or UI from the glasses), and repeated request is a no-op.
    fun init()

    // Releases the repository. This should be called when the voice assist sessions stops. It can happen through
    // multiple UIs (e.g., Assist sheet on the mobile, or UI from the glasses), and repeated request is a no-op.
    fun release()

    // Sets the pipeline to use, and the initial input modality.
    fun setPipeline(
        pipeline: AssistPipelineResponse,
        inputModality: InputModality
    )

    // Clears pipeline related data.
    fun clearPipelineData()

    // Changes assist to voice mode.
    fun switchToVoice()

    // Changes assist to text mode. Set `textOnly` to true to indicate voice assist mode is not supported.
    fun switchToText(textOnly: Boolean = false)

    /**
     * @param text input to run an intent pipeline with, or `null` to run an STT pipeline (check if
     * STT is supported _before_ calling this function)
     */
    fun runAssistPipeline(
        scope: CoroutineScope,
        text: String?,
    )

    // Starts audio recorder.
    fun startRecording(scope: CoroutineScope): Boolean

    // Stops audio recorder. By default this discards all remaining audio data. To send those to the remote, provide
    // `sendRecordedScope`.
    fun stopRecording(sendRecordedScope: CoroutineScope? = null)

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
    private val _assistState = mutableStateOf<AssistState?>(null)
    override val assistState: State<AssistState?> = _assistState

    override var selectedServerId = ServerManager.SERVER_ID_ACTIVE

    private var _inputModality = mutableStateOf<InputModality?>(null)
    override var inputModality = _inputModality

    // Audio recorder states.
    private var recorderJob: Job? = null
    private var recorderQueue: MutableList<ByteArray>? = null

    override val hasMicrophone by lazy {
        application.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    private val _supportVoice = mutableStateOf(false)
    override val supportVoice = _supportVoice

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

    // Pipeline ID or null if no pipeline has been set.
    private var pipelineId: String? = null

    // True if the pipeline supports Text-to-speech.
    private var pipelineHasTtsEngine = false

    // This is the ID of the STT binary handler. It is set at RUN_START. Later, at STT_START, we send all queued audio
    // data in `recorderQueue` to this binary handler, and delete the `recorderQueue`. All subsequent recording data
    // will then be sent straight to the binary handler.
    private var binaryHandlerId: Int? = null
    private var conversationId: String? = null

    // -----------------------------------------------------------------------------------------------------------------

    private var continueConversation = AtomicBoolean(false)

    override suspend fun isRegistered(): Boolean = serverManager.isRegistered()

    override fun setupRecorder() {
        audioRecorder.setupRecorder()
    }

    override fun init() {
        Timber.d("ZZZ: init")
        if (_assistState.value != null) {
            Timber.i("Assist has already initialized. Ignored re-initialization.")
            return
        }

        assert(!audioRecorder.isRecording()) { "Audio recorder is already recording at init." }
        assert(recorderQueue == null) { "recorderQueue should be null at init." }
        assert(recorderJob == null) { "recorderJob should be null at init." }

        // Makes the mode leaves null to indicate the repository has initialized.
        setState(AssistState.WAITING)
    }

    override fun release() {
        Timber.d("ZZZ: release")
        if (_assistState.value == null) {
            Timber.i("Assist has already released. Ignored re-release.")
            return
        }

        stopRecording()
        stopPlayback()

        // Returns to null assist state to indicate the repository has been released.
        setState(null)
        selectedServerId = ServerManager.SERVER_ID_ACTIVE
        clearPipelineData()
        clearConversation()
        continueConversation.set(false)
    }

    override fun setPipeline(
        pipeline: AssistPipelineResponse,
        inputModality: InputModality
    ) {
        assert(
            _assistState.value == AssistState.WAITING ||
            _assistState.value == AssistState.VOICE_INACTIVE ||
            _assistState.value == AssistState.TEXT
        ) {
            "Pipeline should only be set at start, or when waiting for user input."
        }

        clearPipelineData()
        pipelineId = pipeline.id
        pipelineHasTtsEngine = pipeline.ttsEngine?.isNotBlank() == true
        val pipelineHasSttEngine = pipeline.sttEngine?.isNotBlank() == true
        _supportVoice.value = hasMicrophone && pipelineHasSttEngine

        when (inputModality) {
            InputModality.TEXT -> setState(AssistState.TEXT)
            InputModality.VOICE -> setState(AssistState.VOICE_INACTIVE)
        }
        _inputModality.value = inputModality
    }

    override fun clearPipelineData() {
        pipelineId = null
        pipelineHasTtsEngine = false
        binaryHandlerId = null
        conversationId = null
    }

    override fun switchToVoice() {
        if (_inputModality.value == InputModality.VOICE) {
            Timber.w("Assist is already in voice mode.")
            return
        }
        assert(_assistState.value == AssistState.TEXT) {
            "Should only switch to voice input when waiting for text input."
        }
        setState(AssistState.VOICE_INACTIVE)
        _inputModality.value = InputModality.VOICE
    }

    // TODO: Remove textOnly.
    override fun switchToText(textOnly: Boolean) {
        if (_inputModality.value == InputModality.TEXT) {
            Timber.w("Assist is already in text mode.")
            return
        }
        assert(_assistState.value == AssistState.VOICE_INACTIVE) {
            "Should only switch to text input when waiting for voice input."
        }
        setState(AssistState.TEXT)
        _inputModality.value = InputModality.TEXT
    }

    override fun runAssistPipeline(
        scope: CoroutineScope,
        text: String?,
    ) {
        val isVoice = text == null

        stopPlayback()

        // Initial user message is "…" if using voice input, or the actually provided initial input (i.e., `text`)
        // otherwise.
        var inputPlaceholderId: Int? = null
        if (isVoice) {
            inputPlaceholderId = _conversation.size
            _conversation.add(AssistMessage("…", isInput = true))
        } else {
            _conversation.add(AssistMessage(text, isInput = true))
            setState(AssistState.WAITING)
        }

        // Placeholder Home Assistant response (i.e., "…") when we have the user input and are waiting for the response.
        // - For voice input, this is added when we receive the STT result (AssistEvent.Message.Input).
        // - For text input, this is added immediately below since the user input is already added.
        val outputPlaceholderMessage = AssistMessage("…", isInput = false)
        var outputPlaceholderId: Int? = null
        if (!isVoice) {
            outputPlaceholderId = _conversation.size
            _conversation.add(outputPlaceholderMessage)
        }

        // TODO: We can probably merge this into the flow handling below, and skip all AssistEvents
        fun onAssistEvent(event: AssistEvent) {
            when (event) {
                // Complete user (input), assistant (output) message, or error message:
                // - User messages represent the STT outputs, and we only get these messages with voice assist. (Text
                //   input is provided directly through `text`).
                // - Assistant messages represent the Home Assistant responses.
                is AssistEvent.Message -> {
                    when (event) {
                        is AssistEvent.Message.Input -> {
                            assert(isVoice) { "Should not get AssistEvent.Message.Input type with text input" }
                            val id = inputPlaceholderId
                            inputPlaceholderId = null
                            if (id != null) {
                                _conversation[id] = AssistMessage(
                                    message = event.message.trim(),
                                    isInput = true,
                                )
                                outputPlaceholderId = _conversation.size
                                _conversation.add(outputPlaceholderMessage)
                            } else {
                                Timber.e("No input place holder to populate input message: $event")
                            }
                            setState(AssistState.WAITING)
                        }

                        is AssistEvent.Message.Output -> {
                            val id = outputPlaceholderId
                            outputPlaceholderId = null
                            if (id != null) {
                                _conversation[id] = AssistMessage(
                                    message = event.message.trim(),
                                    isInput = false,
                                )
                            } else {
                                Timber.e("No output place holder to populate output message: $event")
                            }

                            assert(_assistState.value == AssistState.WAITING) {
                                "InputMode should be WAITING when we received an output message."
                            }
                            when (_inputModality.value) {
                                InputModality.TEXT -> setState(AssistState.TEXT)
                                InputModality.VOICE -> setState(AssistState.VOICE_INACTIVE)
                                null -> assert(false) {
                                    "We should not receive any output before input modality is determined."
                                }
                            }
                        }

                        is AssistEvent.Message.Error -> {
                            val id = outputPlaceholderId
                            outputPlaceholderId = null
                            if (id != null) {
                                _conversation[id] = AssistMessage(
                                    message = event.message.trim(),
                                    isInput = false,
                                    isError = true,
                                )
                            } else {
                                Timber.e("No output place holder to populate error message: $event")
                            }
                        }
                    }
                }

                is AssistEvent.MessageChunk -> {
                    val id = outputPlaceholderId
                    if (id != null) {
                        if (_conversation[id] == outputPlaceholderMessage) {
                            // Replace the placeholder message with the chunk received.
                            _conversation[id] = AssistMessage(
                                message = event.chunk,
                                isInput = false,
                            )
                        } else {
                            // Replace the placeholder message with the updated message with the new chunk append.
                            _conversation[id] = AssistMessage(
                                message = _conversation[id].message + event.chunk,
                                isInput = false,
                            )
                        }
                    } else {
                        Timber.e("No output place holder to populate output message chunk: $event")
                    }
                }
            }
        }

        var job: Job? = null
        job = scope.launch {
            assert(pipelineId != null) { "Pipeline ID should be set before assist pipeline starts." }
            val flow = if (isVoice) {
                serverManager.webSocketRepository(selectedServerId).runAssistPipelineForVoice(
                    sampleRate = AudioRecorder.SAMPLE_RATE,
                    outputTts = pipelineHasTtsEngine,
                    pipelineId = pipelineId,
                    conversationId = conversationId,
                )
            } else {
                serverManager.integrationRepository(selectedServerId).getAssistResponse(
                    text = text,
                    pipelineId = pipelineId,
                    conversationId = conversationId,
                )
            }

            flow?.collect {
                when (it.type) {
                    AssistPipelineEventType.RUN_START -> {
                        Timber.d("ZZZ: RUN_START")
                        if (!isVoice) return@collect
                        val data = (it.data as? AssistPipelineRunStart)?.runnerData
                        binaryHandlerId = data?.get("stt_binary_handler_id") as? Int
                    }
                    AssistPipelineEventType.STT_START -> {
                        Timber.d("ZZZ: STT_START")
                        scope.launch {
                            // Make a snapshot of the recorderQueue, and clear it (so the future input will be sent
                            // straight to the remote). A snapshot is needed because the forEach loop below may stop in
                            // the middle since sendVoiceData() is a suspend function, and we need to avoid the list
                            // being modified in the middle of the loop.
                            val queueSnapshot = recorderQueue?.toList()
                            recorderQueue = null

                            val id = binaryHandlerId
                            if (id != null) {
                                // Manually loop here to avoid the queue being reset too soon
                                queueSnapshot?.forEach { data ->
                                    serverManager.webSocketRepository(selectedServerId).sendVoiceData(id, data)
                                }
                            } else {
                                Timber.e("No binary handler ID available at STT_START. Recording will not " +
                                    "be sent.")
                            }
                        }
                    }
                    AssistPipelineEventType.STT_END -> {
                        Timber.d("ZZZ: STT_END")
                        stopRecording(sendRecordedScope = scope)
                        (it.data as? AssistPipelineSttEnd)?.sttOutput?.let { response ->
                            onAssistEvent(AssistEvent.Message.Input(response["text"] as String))
                        }
                    }
                    AssistPipelineEventType.INTENT_PROGRESS -> {
                        Timber.d("ZZZ: INTENT_PROGRESS: $it")
                        (it.data as? AssistPipelineIntentProgress)?.chatLogDelta?.content?.let { delta ->
                            onAssistEvent(AssistEvent.MessageChunk(delta))
                        }
                    }
                    AssistPipelineEventType.INTENT_END -> {
                        Timber.d("ZZZ: INTENT_END: $it")
                        val data = (it.data as? AssistPipelineIntentEnd)?.intentOutput ?: return@collect
                        conversationId = data.conversationId
                        continueConversation.set(data.continueConversation)
                        data.response.speech?.plain?.get("speech")?.let { speech ->
                            onAssistEvent(AssistEvent.Message.Output(speech))
                        }
                    }
                    AssistPipelineEventType.TTS_END -> {
                        Timber.d("ZZZ: TTS_END")
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
                        Timber.d("ZZZ: RUN_END")
                        stopRecording(sendRecordedScope = scope)
                        job?.cancel()
                    }
                    AssistPipelineEventType.ERROR -> {
                        Timber.d("ZZZ: ERROR")
                        val errorMessage = (it.data as? AssistPipelineError)?.message ?: return@collect
                        onAssistEvent(AssistEvent.Message.Error(errorMessage))
                        stopRecording(sendRecordedScope = scope)
                        job?.cancel()
                    }
                    else -> {
                        Timber.d("ZZZ: Unhandled AssistPipelineEvent: ${it.type}")
                    }
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
        assert(_assistState.value == AssistState.VOICE_INACTIVE) {
            "UX error: should only start recording from VOICE_INACTIVE, but it is ${_assistState.value}."
        }

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
        setState(AssistState.VOICE_ACTIVE)
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

    override fun stopRecording(sendRecordedScope: CoroutineScope?) {
        if (_assistState.value != AssistState.VOICE_ACTIVE) {
            // No action needed. Let's check for error condition, but not try to fix them.
            assert(!audioRecorder.isRecording()) {
                "audioRecorder should not be recording in assist state ${_assistState.value}"
            }
            assert(recorderJob == null) { "There should be no recorderJob in assist state ${_assistState.value}" }
            return
        }

        audioRecorder.stopRecording()
        recorderJob?.cancel()
        recorderJob = null
        if (sendRecordedScope != null) {
            if (binaryHandlerId != null) {
                // TODO: This is actually launching a coroutine to iterate through the queue, which lauches new
                // coroutines for each recording. Is this desired or necessary?
                sendRecordedScope.launch {
                    recorderQueue?.forEach {
                        sendVoiceData(sendRecordedScope, it)
                    }
                    sendVoiceData(sendRecordedScope, byteArrayOf()) // Empty message to indicate end of recording
                }
            } else {
                Timber.w("Ignore sending the remaining data at stop recording: no binary handler ID.")
            }
        }
        recorderQueue = null
        binaryHandlerId = null
        _lastRecordedLevel.value = null
        setState(AssistState.VOICE_INACTIVE)
    }

    override fun stopPlayback() = audioUrlPlayer.stop()

    override fun markBlocked(errorMsg: String) {
        assert(_assistState.value != AssistState.VOICE_ACTIVE) {
            "Assist function should be blocked before it starts to record."
        }
        setState(AssistState.BLOCKED)
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

    // Sets the desired assist state. See AssistantRepository.AssistState.
    private fun setState(assistState: AssistState?) {
        Timber.d("Assist state changed: ${_assistState.value} -> $assistState")
        _assistState.value = assistState
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistRepositoryModule {
    @Binds
    abstract fun bindAssistRepository(impl: AssistRepositoryImpl): AssistRepository
}
