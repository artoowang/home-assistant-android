package io.homeassistant.companion.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.BaseActivity
import io.homeassistant.companion.android.assist.ui.AssistSheetView
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.launch.LaunchActivity
import io.homeassistant.companion.android.glasses.launchGlassesExperience
import io.homeassistant.companion.android.util.compose.HomeAssistantAppTheme
import io.homeassistant.companion.android.webview.WebViewActivity
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class AssistActivity : BaseActivity() {

    private val viewModel: AssistViewModel by viewModels()

    private var contextIsLocked = true

    companion object {
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_PIPELINE = "pipeline"
        private const val EXTRA_START_LISTENING = "start_listening"
        private const val EXTRA_FROM_FRONTEND = "from_frontend"

        fun newInstance(
            context: Context,
            serverId: Int = -1,
            pipelineId: String? = null,
            startListening: Boolean = true,
            fromFrontend: Boolean = true,
        ): Intent {
            return Intent(context, AssistActivity::class.java).apply {
                putExtra(EXTRA_SERVER, serverId)
                putExtra(EXTRA_PIPELINE, pipelineId)
                putExtra(EXTRA_START_LISTENING, startListening)
                putExtra(EXTRA_FROM_FRONTEND, fromFrontend)
            }
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onPermissionResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("ZZZ: AssistActivity.onCreate: savedInstanceState=$savedInstanceState")

        updateShowWhenLocked()

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                if (!viewModel.isRegistered()) {
                    startActivity(Intent(this@AssistActivity, LaunchActivity::class.java))
                    finish()
                }
            }

            viewModel.onCreate(
                hasPermission = hasRecordingPermission(),
                serverId = if (intent.hasExtra(EXTRA_SERVER)) {
                    intent.getIntExtra(EXTRA_SERVER, ServerManager.SERVER_ID_ACTIVE)
                } else {
                    null
                },
                pipelineId = if (intent.hasExtra(EXTRA_PIPELINE)) {
                    intent.getStringExtra(EXTRA_PIPELINE) ?: AssistViewModel.PIPELINE_LAST_USED
                } else {
                    AssistViewModel.PIPELINE_LAST_USED
                },
                startListening = if (intent.hasExtra(EXTRA_START_LISTENING)) {
                    intent.getBooleanExtra(EXTRA_START_LISTENING, true)
                } else if (intent.action == Intent.ACTION_VOICE_COMMAND) {
                    // Always start listening if triggered via the voice command (e.g., from a BT headset).
                    true
                } else {
                    null
                },
            )

            // Starts a coroutine that monitors the input mode. When it becomes null, it means the sheet is closing,
            // so we finish the activity. The input mode signal is backed by State<InputMode?>, which automatically
            // de-duplicate repeated signals, so we won't get repeated it == null signals.
            lifecycleScope.launch {
                snapshotFlow { viewModel.inputMode }
                    .filter { it == null }
                    .collect {
                        Timber.d("ZZZ: Input mode is null, closing activity.")
                        finish()
                    }
            }

            // Make sure the GlassesActivity is launched AFTER AssistViewModel.onCreate(), which sets up the
            // AudioRecord. Otherwise, AudioRecord may record silent audio without error.
            // TODO: Weird, after I launch GlassesActivity in LaunchActivity, now whether I start the intent here or not
            // no longer matters - the mic records fine in either case. We should still start the GA again to make sure
            // it shows up though, but we might want to update the comments above.
            launchGlassesExperience(this)
        }

        val fromFrontend = intent.getBooleanExtra(EXTRA_FROM_FRONTEND, false)

        setContent {
            HomeAssistantAppTheme {
                AssistSheetView(
                    conversation = viewModel.conversation,
                    pipelines = viewModel.pipelines,
                    inputMode = viewModel.inputMode,
                    lastRecordedLevel = viewModel.lastRecordedLevel,
                    fromFrontend = fromFrontend,
                    currentPipeline = viewModel.currentPipeline,
                    onSelectPipeline = viewModel::changePipeline,
                    onManagePipelines =
                    if (fromFrontend && viewModel.userCanManagePipelines) {
                        {
                            startActivity(
                                WebViewActivity.newInstance(
                                    this,
                                    "config/voice-assistants/assistants",
                                ).apply {
                                    flags += Intent.FLAG_ACTIVITY_NEW_TASK // Delivers data in onNewIntent
                                },
                            )
                            finish()
                        }
                    } else {
                        null
                    },
                    onChangeInput = viewModel::onChangeInput,
                    onTextInput = viewModel::onTextInput,
                    onMicrophoneInput = viewModel::onMicrophoneInput,
                    onHide = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("ZZZ: AssistActivity.onResume")
        viewModel.setPermissionInfo(hasRecordingPermission()) {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onPause() {
        super.onPause()
        Timber.d("ZZZ: AssistActivity.onPause")
        viewModel.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("ZZZ: AssistActivity.onDestroy")
        viewModel.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent

        val isLocked = getSystemService<KeyguardManager>()?.isKeyguardLocked ?: false
        viewModel.onNewIntent(intent, contextIsLocked == isLocked)
        updateShowWhenLocked(isLocked)
    }

    /** Set flags to show dialog when (un)locked, and prevent unlocked dialogs from resuming while locked **/
    private fun updateShowWhenLocked(isLocked: Boolean? = null) {
        val locked = isLocked ?: getSystemService<KeyguardManager>()?.isKeyguardLocked ?: false
        contextIsLocked = locked
        if (locked) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
        } else {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                setShowWhenLocked(false)
            }
        }
    }

    private fun hasRecordingPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
