package io.homeassistant.companion.android.assist

import android.app.Application
import android.content.pm.PackageManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface AssistEvent {
    sealed class Message(val message: String) : AssistEvent {
        class Input(message: String) : Message(message)
        class Output(message: String) : Message(message)
        class Error(message: String) : Message(message)
    }
    class MessageChunk(val chunk: String) : AssistEvent
    data object ContinueConversation : AssistEvent
}

interface AssistRepository {
    companion object {
        const val PIPELINE_PREFERRED = "preferred"
        const val PIPELINE_LAST_USED = "last_used"
    }

    enum class AssistInputMode {
        TEXT,
        TEXT_ONLY,
        VOICE_INACTIVE,
        VOICE_ACTIVE,
        BLOCKED,
    }

    var selectedServerId: Int
    var recorderProactive: Boolean
    var hasPermission: Boolean
    val hasMicrophone: Boolean

    suspend fun isRegistered(): Boolean
    fun runAssistPipeline(
        scope: CoroutineScope,
        text: String?,
        pipeline: AssistPipelineResponse?,
        onEvent: (AssistEvent) -> Unit
    )
    fun startRecording(): Boolean
    fun setupRecorderQueue(scope: CoroutineScope)
    fun stopRecording(scope: CoroutineScope, sendRecorded: Boolean = true)
    fun stopPlayback()
    fun clearPipelineData()
}

@ViewModelScoped
class AssistRepositoryImpl @Inject constructor(
    private val serverManager: ServerManager,
    private val audioRecorder: AudioRecorder,
    private val audioUrlPlayer: AudioUrlPlayer,
    private val application: Application
) : AssistRepository {

    private val app = application

    override var selectedServerId = ServerManager.SERVER_ID_ACTIVE

    override var recorderProactive = false
    private var recorderJob: Job? = null
    private var recorderQueue: MutableList<ByteArray>? = null
    override val hasMicrophone by lazy { app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
    override var hasPermission = false

    private var binaryHandlerId: Int? = null
    private var conversationId: String? = null
    private var continueConversation = AtomicBoolean(false)

    override suspend fun isRegistered(): Boolean = serverManager.isRegistered()
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
                onEvent(AssistEvent.Message.Output(app.getString(R.string.assist_error)))
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
        recorderProactive = false
    }

    override fun stopPlayback() = audioUrlPlayer.stop()
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class AssistRepositoryModule {
    @Binds
    abstract fun bindAssistRepository(impl: AssistRepositoryImpl): AssistRepository
}
