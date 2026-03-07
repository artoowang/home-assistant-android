package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.Text
import androidx.xr.projected.ProjectedContext
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import androidx.xr.projected.permissions.ProjectedPermissionsRequestParams
import androidx.xr.projected.permissions.ProjectedPermissionsResultContract
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@OptIn(ExperimentalProjectedApi::class)
fun launchGlassesExperience(context: Context) {
    Timber.d("ZZZ: Attempting to launch GlassesActivity on connected device...")

    try {
        val projectedContext = ProjectedContext.createProjectedDeviceContext(context)
        val options = ProjectedContext.createProjectedActivityOptions(projectedContext)
        val intent = Intent(context, GlassesActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent, options.toBundle())
        Timber.i("Successfully sent launch intent to the projected device.")
    } catch (e: IllegalStateException) {
        Timber.e("Projected device not ready: ${e.message}")
    } catch (e: Exception) {
        Timber.e("Error during launch: ${e.message}")
    }
}

@AndroidEntryPoint
class GlassesActivity : ComponentActivity() {
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
        Timber.d("ZZZ: onCreate: savedInstanceState=$savedInstanceState")

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
    }

    // Launches the assist activity for glasses.
    private fun startAssistActivity() {
        try {
            val intent = Intent(this, GlassesAssistActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e("Error during launch: ${e.message}")
        }
    }

    private fun setupContent() {
        setContent {
            GlimmerTheme {
                when {
                    isPermissionsGranted -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable {
                                startAssistActivity()
                            },
                    ) {
                        // TODO: Probably want to remove this for the actual UX on Glasses, so we won't have a large icon always on
                        // the screen?
                        Image(
                            painter = painterResource(id = R.drawable.ha_icon),
                            contentDescription = "Home Assistant Icon",
                            modifier = Modifier.size(GlimmerTheme.iconSizes.large),
                        )
                    }

                    else -> PermissionNotice()
                }
            }
        }
    }
}

@Composable
fun PermissionNotice() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Text(
            text = "Permissions Denied. Please grant Audio access on the host phone to proceed.",
            color = Color(0xFFFF0000),
        )
    }
}

@Preview(
    widthDp = EmulatorScreenWidthDp,
    heightDp = EmulatorScreenHeightDp,
)
@Composable
private fun PermissionNoticePreview() {
    GlimmerTheme {
        PermissionNotice()
    }
}
