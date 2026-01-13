package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import androidx.xr.projected.permissions.ProjectedPermissionsRequestParams
import androidx.xr.projected.permissions.ProjectedPermissionsResultContract
import timber.log.Timber

// This is modified from AI Sample Catalog, Gemini Live Todo example.
class GlassesActivity : ComponentActivity() {

    // -----------------------------------------------------------------------------------------------------------------
    // Permission Utilities.

    // Keeps track if the required permissions by glasses are granted.
    private var isPermissionsGranted by mutableStateOf(false)
    private val requiredPermissions = listOf(
        Manifest.permission.RECORD_AUDIO
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
                    rationale = "We need microphone access to continue to the main experience."
                )
            )
        )
    }

    // -----------------------------------------------------------------------------------------------------------------

    private fun setupContent() {
        setContent {
            GlimmerTheme {
                RootScreen(isGranted = isPermissionsGranted)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("ZZZ: GlassesActivity.onCreate: savedInstanceState=$savedInstanceState")

        val allGranted = checkAllPermissionsGranted()
        isPermissionsGranted = allGranted

        setupContent()

        if (!allGranted) {
            requestPermissions()
        }
    }
}

@Composable
fun RootScreen(isGranted: Boolean, modifier: Modifier = Modifier) {
    // TODO
//    if (isGranted) {
//        GlimmerTodoScreen(modifier = modifier)
//    } else {
        Text(
            text = "Permissions Denied. Please grant Audio access on the host phone to proceed.",
            modifier = modifier
        )
//    }
}
