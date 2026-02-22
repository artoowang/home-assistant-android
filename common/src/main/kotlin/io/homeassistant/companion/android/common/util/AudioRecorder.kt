package io.homeassistant.companion.android.common.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import androidx.core.content.ContextCompat
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
class AudioRecorder(private val audioManager: AudioManager, private val context: Context) {

    companion object {
        // Docs: 'currently the only rate that is guaranteed to work on all devices'
        const val SAMPLE_RATE = 44100

        // Docs: only format '[g]uaranteed to be supported by devices'
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val AUDIO_SOURCE = AudioSource.MIC
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        // Gain factor to boost audio samples.
        // TODO: This is currently manually picked for Bluetooth SCO audio, which tends to be very quiet.
        private const val DEFAULT_GAIN_FACTOR = 10.0f
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

    // Audio device callback. This is currently only used to log adding/removing devices.
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Timber.d("ZZZ: Audio devices added: ${addedDevices.size}")
            addedDevices.forEach { device ->
                Timber.d("ZZZ: Added device $device")
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Timber.d("ZZZ: Audio devices removed: ${removedDevices.size}")
            removedDevices.forEach { device ->
                Timber.d("ZZZ: Removed device: $device")
            }
        }
    }

    // Listener to log changes to communication device.
    private val communicationDeviceChangedListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        Timber.d("ZZZ: Communication device changed to $device")
    }

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
                            val boostedSample = (data[i] * DEFAULT_GAIN_FACTOR).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                            val byteIndex = i * 2
                            // Manually place the two bytes for each short into the new array.
                            byteArray[byteIndex] = (boostedSample.toInt() and 0x00FF).toByte()
                            byteArray[byteIndex + 1] = ((boostedSample.toInt() and 0xFF00) shr 8).toByte()
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

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        audioManager.addOnCommunicationDeviceChangedListener(
            ContextCompat.getMainExecutor(context),
            communicationDeviceChangedListener,
        )

        try {
            val availableCommDevices = audioManager.availableCommunicationDevices
            Timber.d("ZZZ: # of available communication devices: ${availableCommDevices.size}")
            availableCommDevices.forEachIndexed { index, device ->
                Timber.d("ZZZ: CommDevice[$index]: $device")
            }

            // Try to find and set Bluetooth headset
            val btDevice = availableCommDevices.find { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (btDevice != null) {
                Timber.d("ZZZ: Found Bluetooth SCO device: $btDevice, setting as communication device")
                val success = audioManager.setCommunicationDevice(btDevice)
                Timber.d("ZZZ: setCommunicationDevice result: $success")
            } else {
                Timber.d("ZZZ: No Bluetooth SCO device found")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get/set communication devices")
        }

        val bufferSize = minBufferSize() * 10
        recorder = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        Timber.d("ZZZ: setupRecorder: recorder=$recorder")
    }

    fun releaseRecorder() {
        Timber.d("ZZZ: releaseRecorder: recorder=$recorder")
        recorder?.release() ?: Timber.e("Recorder is already released.")
        recorder = null

        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.removeOnCommunicationDeviceChangedListener(communicationDeviceChangedListener)
    }

    private fun minBufferSize() = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    private fun requestFocus() {
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
        if (focusRequest == null) return
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest!!)
    }
}
