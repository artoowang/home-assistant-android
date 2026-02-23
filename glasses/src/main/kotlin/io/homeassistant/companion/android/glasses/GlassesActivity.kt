package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.Text
import androidx.xr.projected.ProjectedContext
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import androidx.xr.projected.permissions.ProjectedPermissionsRequestParams
import androidx.xr.projected.permissions.ProjectedPermissionsResultContract
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        lifecycleScope.launch {
            setUpCamera()
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private suspend fun setUpCamera() {
        Timber.d("ZZZ: setUpCamera")

        val cameraProvider = ProcessCameraProvider.getInstance(this).await()
        Timber.d("ZZZ: cameraProvider=$cameraProvider")
        Timber.d("ZZZ: cameraProvider.availableCameraInfos=${cameraProvider.availableCameraInfos}")

        // Is "back" the primary glasses camera?
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)
        val camera2CameraInfo = Camera2CameraInfo.from(cameraInfo)
        val cameraCharacteristics =
            camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        Timber.d("ZZZ: cameraCharacteristics=$cameraCharacteristics")

        val targetResolution = Size(1920, 1080)
        val resolutionStrategy = ResolutionStrategy(
            targetResolution,
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
        )

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(resolutionStrategy)
            .build()

        // Initialize the ImageCapture use case.
        val imageCapture = ImageCapture.Builder()
            // Optional: Configure resolution, format, etc.
            .setResolutionSelector(resolutionSelector)
            .build()

        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // 4. Bind use cases to camera
            cameraProvider.bindToLifecycle(
                this as LifecycleOwner,
                cameraSelector,
                imageCapture,
            )

            Timber.d("ZZZ: imageCapture=$imageCapture")
            takePhoto(this, imageCapture)
        } catch (exc: Exception) {
            // This catches exceptions like IllegalStateException if use case binding fails
            Timber.e(exc, "Use case binding failed")
        }
    }

    // TODO
    private fun createOutputFileOptions(context: Context): ImageCapture.OutputFileOptions {
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyApp")
            },
        ).build()
    }

    // TODO
    private fun takePhoto(context: Context, imageCapture: ImageCapture) {
        Timber.d("ZZZ: takePhoto")

        val outputOptions = createOutputFileOptions(context)

        imageCapture.takePicture(
            /* outputFileOptions = */
            outputOptions,
            /* executor = */
            ContextCompat.getMainExecutor(context),
            /* imageSavedCallback = */
            object : ImageCapture.OnImageSavedCallback {
                override fun onCaptureStarted() {
                    Timber.d("ZZZ: onCaptureStarted")
                }

                override fun onCaptureProcessProgressed(progress: Int) {
                    Timber.d("ZZZ: onCaptureProcessProgressed: $progress")
                }

                override fun onPostviewBitmapAvailable(bitmap: Bitmap) {
                    Timber.d("ZZZ: onPostviewBitmapAvailable: $bitmap")
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    Timber.d("ZZZ: Photo saved to gallery: $savedUri")
                    Toast.makeText(context, "Photo saved!", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "ZZZ: Photo capture failed: ${exception.message}")
                    Toast.makeText(context, "Failed to capture photo", Toast.LENGTH_SHORT).show()
                }
            },
        )
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

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalProjectedApi::class)
    private fun startCamera() {
        // Get the CameraProvider using the projected context.

        val cameraProviderFuture = ProcessCameraProvider.getInstance(
            ProjectedContext.createProjectedDeviceContext(this),
        )

        cameraProviderFuture.addListener(
            {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                Timber.d("ZZZ: cameraProvider.availableCameraInfos=${cameraProvider.availableCameraInfos}")

                // Select the camera. When using the projected context, DEFAULT_BACK_CAMERA maps to the AI glasses' camera.
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Check for the presence of a camera before initializing the ImageCapture use case.
                if (!cameraProvider.hasCamera(cameraSelector)) {
                    Timber.w("The selected camera is not available.")
                    return@addListener
                }

                // Get supported streaming resolutions.
                val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)
                val camera2CameraInfo = Camera2CameraInfo.from(cameraInfo)

                // Define the resolution strategy.
                val targetResolution = Size(1920, 1080)
                val resolutionStrategy = ResolutionStrategy(
                    targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                )

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(resolutionStrategy)
                    .build()

                // Initialize the ImageCapture use case.
                val imageCapture = ImageCapture.Builder()
                    // Optional: Configure resolution, format, etc.
                    .setResolutionSelector(resolutionSelector)
                    .build()

                try {
                    // Unbind use cases before rebinding
                    cameraProvider.unbindAll()

                    // 4. Bind use cases to camera
                    cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, imageCapture)

                } catch (exc: Exception) {
                    // This catches exceptions like IllegalStateException if use case binding fails
                    Timber.e(exc, "Use case binding failed")
                }

            },
            ContextCompat.getMainExecutor(this),
        )
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
                                // startAssistActivity()
                                // startCamera()
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
