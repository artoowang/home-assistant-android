package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresPermission
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

    // Keeps track if the required permissions by glasses are granted.
    private var isPermissionsGranted by mutableStateOf(false)
    private val requiredPermissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    @OptIn(ExperimentalProjectedApi::class)
    private val requestPermissionLauncher: ActivityResultLauncher<List<ProjectedPermissionsRequestParams>> =
        registerForActivityResult(ProjectedPermissionsResultContract()) { results ->
            val granted = requiredPermissions.all { permission ->
                results[permission] == true
            }
            isPermissionsGranted = granted
        }

    private val camera2Controller by lazy { Camera2Controller(this) }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GlimmerTheme {
                if (isPermissionsGranted) {
                    MainScreen(
                        onClick = { capturePhoto() },
                    )
                } else {
                    PermissionNotice()
                }
            }
        }

        requestPermissions()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun capturePhoto() {
        // TODO
        camera2Controller.capturePhotoWithSession { jpegBytes ->
            Timber.d("ZZZ: Image captured, size: ${jpegBytes.size}")
            Camera2Utils.saveBytesToFile(this, jpegBytes)
        }
    }

    @OptIn(ExperimentalProjectedApi::class)
    private fun requestPermissions() {
        if (requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            isPermissionsGranted = true
        } else {
            requestPermissionLauncher.launch(
                listOf(
                    ProjectedPermissionsRequestParams(
                        permissions = requiredPermissions,
                        rationale = "We need microphone and camera access to continue.",
                    ),
                ),
            )
        }
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
}

@Composable
private fun MainScreen(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onClick() },
    ) {
        // TODO: Probably want to remove this for the actual UX on Glasses, so we won't have a large icon always on
        // the screen?
        Image(
            painter = painterResource(id = R.drawable.ha_icon),
            contentDescription = "Home Assistant Icon",
            modifier = Modifier.size(GlimmerTheme.iconSizes.large),
        )
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
            color = Color.White,
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
