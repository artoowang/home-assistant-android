package io.homeassistant.companion.android.common.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Wrapper around [AudioRecord] providing pre-configured audio recording functionality.
 */
class AudioRecorder(private val audioManager: AudioManager?) {

    companion object {
        // Docs: 'currently the only rate that is guaranteed to work on all devices'
        const val SAMPLE_RATE = 44100

        // Docs: only format '[g]uaranteed to be supported by devices'
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val AUDIO_SOURCE = AudioSource.MIC
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    private var recorder: AudioRecord? = null
    private var recorderJob: Job? = null

    private val _audioBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Flow emitting audio recording bytes as they come in */
    val audioBytes = _audioBytes.asSharedFlow()

    private var focusRequest: AudioFocusRequestCompat? = null
    private val focusListener = OnAudioFocusChangeListener { /* Not used */ }

    /**
     * Start the recorder. After calling this function, data will be available via [audioBytes].
     * @throws SecurityException when missing permission to record audio
     * @return `true` if the recorder started, or `false` if not
     */
    fun startRecording(): Boolean {
        Timber.d("ZZZ: startRecording: recorder=$recorder, recorderJob=$recorderJob")
        recorder?.let {
            val ready = it.state == AudioRecord.STATE_INITIALIZED
            if (!ready) return false

            if (recorderJob == null || recorderJob?.isActive == false) {
                requestFocus()
                it.startRecording()
                recorderJob = ioScope.launch {
                    val dataSize = minBufferSize()
                    while (isActive) {
                        // We're recording in 16-bit as that is guaranteed to be supported but bytes are
                        // 8-bit. So first read as shorts, then manually split them into two bytes, and
                        // finally send all pairs of two as one array to the flow.
                        // Split/conversion based on https://stackoverflow.com/a/47905328/4214819.
                        val data = ShortArray(dataSize)
                        val numSamples = it.read(data, 0, dataSize) // blocking!
                        val byteArray = ByteArray(numSamples * 2)
                        for (i in 0 until numSamples) {
                            val sample = data[i]
                            val byteIndex = i * 2
                            // Manually place the two bytes for each short into the new array.
                            byteArray[byteIndex] = (sample.toInt() and 0x00FF).toByte()
                            byteArray[byteIndex + 1] = ((sample.toInt() and 0xFF00) shr 8).toByte()
                        }
                        _audioBytes.emit(byteArray)
                    }
                    Timber.d("ZZZ: ioScope job is done.")
                }
            }
        } ?: run {
            Timber.e("Recorder is not yet created.")
            return false
        }
        return true
    }

    fun stopRecording() {
        Timber.d("ZZZ: stopRecording: recorder=$recorder, recorderJob=$recorderJob")
        recorder?.stop()
        recorderJob?.cancel()
        recorderJob = null
        abandonFocus()
    }

    fun isRecording(): Boolean {
        return recorderJob != null
    }

    @SuppressLint("MissingPermission")
    fun setupRecorder() {
        if (recorder != null) {
            Timber.e("A recorder has already been created.")
            return
        }

        val bufferSize = minBufferSize() * 10
        recorder = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        Timber.d("ZZZ: setupRecorder: recorder=$recorder")
    }

    fun releaseRecorder() {
        Timber.d("ZZZ: releaseRecorder: recorder=$recorder")
        recorder?.release() ?: Timber.e("Recorder is already released.")
        recorder = null
    }

    private fun minBufferSize() = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    private fun requestFocus() {
        check(audioManager != null) { "Audio manager is not available." }
        if (focusRequest == null) {
            focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).run {
                setAudioAttributes(
                    AudioAttributesCompat.Builder().run {
                        setUsage(AudioAttributesCompat.USAGE_ASSISTANT)
                        setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                        build()
                    },
                )
                setOnAudioFocusChangeListener(focusListener)
                build()
            }
        }

        focusRequest?.let {
            try {
                AudioManagerCompat.requestAudioFocus(audioManager, it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to request audio focus")
                // We don't use the result / focus if available but if not still continue
            }
        } ?: Timber.e("Failed to build focus request.")
    }

    private fun abandonFocus() {
        if (audioManager == null || focusRequest == null) return
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest!!)
    }
}
