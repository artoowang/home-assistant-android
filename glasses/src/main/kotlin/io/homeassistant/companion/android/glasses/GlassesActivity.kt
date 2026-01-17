package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import androidx.xr.projected.permissions.ProjectedPermissionsRequestParams
import androidx.xr.projected.permissions.ProjectedPermissionsResultContract
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.assist.ASSIST_FINISH_ACTION
import kotlin.getValue
import timber.log.Timber

// This is modified from AI Sample Catalog, Gemini Live Todo example.
@AndroidEntryPoint
class GlassesActivity : ComponentActivity() {

    private val viewModel: GlassesViewModel by viewModels()

    // Receives intent from within the app, as well as external intents from ADB.
    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.d("ZZZ: onReceive: intent=$intent")
            if (intent?.action == ASSIST_FINISH_ACTION) {
                finish()
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Permission Utilities.

    // Keeps track if the required permissions by glasses are granted.
    private var isPermissionsGranted by mutableStateOf(false)
    private val requiredPermissions = listOf(
        Manifest.permission.RECORD_AUDIO,
    )

    @OptIn(ExperimentalProjectedApi::class)
    private val requestPermissionLauncher: ActivityResultLauncher<List<ProjectedPermissionsRequestParams>> =
        registerForActivityResult(ProjectedPermissionsResultContract()) { results ->
            val granted = requiredPermissions.all { permission ->
                results[permission] == true
            }
            isPermissionsGranted = granted
            setupContent()
        }

    private fun checkAllPermissionsGranted(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    @OptIn(ExperimentalProjectedApi::class)
    private fun requestPermissions() {
        requestPermissionLauncher.launch(
            listOf(
                ProjectedPermissionsRequestParams(
                    permissions = requiredPermissions,
                    rationale = "We need microphone access to continue to the main experience.",
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("ZZZ: onCreate: savedInstanceState=$savedInstanceState, viewModel=$viewModel")

        registerReceiver(
            intentReceiver,
            IntentFilter(ASSIST_FINISH_ACTION),
            // This allows us to trigger the receiver with ADB command:
            // adb shell am broadcast -a "<ASSIST_FINISH_ACTION>" -p "<package_name>"
            RECEIVER_EXPORTED
        )

        val allGranted = checkAllPermissionsGranted()
        isPermissionsGranted = allGranted

        setupContent()

        if (!allGranted) {
            requestPermissions()
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
        // Unregister the receiver first, so we don't receive the broadcast below.
        unregisterReceiver(intentReceiver)
        // Send a broadcast to finish AssistActivity
        val intent = Intent(ASSIST_FINISH_ACTION).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun setupContent() {
        setContent {
            GlimmerTheme {
                RootScreen(isGranted = isPermissionsGranted, viewModel)
            }
        }
    }
}

@Composable
fun RootScreen(isGranted: Boolean, viewModel: GlassesViewModel, modifier: Modifier = Modifier) {
    if (isGranted) {
        VoiceAssistScreen(
            conversation = viewModel.conversation,
            modifier = modifier,
        )
    } else {
        Text(
            text = "Permissions Denied. Please grant Audio access on the host phone to proceed.",
            color = Color(0xFFFF0000),
            modifier = modifier,
        )
    }
}
