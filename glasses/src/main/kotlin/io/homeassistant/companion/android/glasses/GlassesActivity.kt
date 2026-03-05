package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.RequiresPermission
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.impl.UseCaseConfigFactory.CaptureType
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
import androidx.compose.runtime.toString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.impl.close
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.Text
import androidx.xr.projected.ProjectedContext
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import androidx.xr.projected.permissions.ProjectedPermissionsRequestParams
import androidx.xr.projected.permissions.ProjectedPermissionsResultContract
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.Collections
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
            setUpCamera2()
        }
    }

    private fun printCharacteristics(cameraId: String, characteristics: CameraCharacteristics) {
        // Log the camera ID to separate the characteristics for each camera
        Timber.d("ZZZ: --- CameraCharacteristics for Camera ID: $cameraId ---")

        // Get the list of all available keys for this camera's characteristics
        val keys = characteristics.keys

        if (keys.isEmpty()) {
            Timber.d("ZZZ: No characteristics keys found for camera $cameraId")
            return
        }

        // Loop through each key and print its corresponding value
        for (key in keys) {
            try {
                val value = characteristics.get(key)
                val valueString = when (value) {
                    is Array<*> -> Arrays.toString(value) // Nicely format arrays
                    is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
                    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
                    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
                    // Add other array types if needed
                    else -> value.toString()
                }
                Timber.d("ZZZ: Key: ${key.name}, Value: $valueString")
            } catch (e: Exception) {
                // Some keys might fail to retrieve, although it's rare
                Timber.w("ZZZ: Could not get value for key ${key.name}")
            }
        }
        Timber.d("ZZZ: --- End of Characteristics for Camera ID: $cameraId ---")
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            Timber.i("ZZZ: onCaptureCompleted")
        }

        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            Timber.i("ZZZ: onCaptureStarted")
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Timber.i("ZZZ: onCaptureFailed: reason=${failure.reason}")
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long,
        ) {
            Timber.i("ZZZ: onCaptureBufferLost")
        }
    }

    private val cameraSessionListener = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Timber.d("ZZZ: Camera capture session configured. handler=$handler")

            val currentCamera = camera ?: run {
                Timber.e("ZZZ: Camera not available")
                return
            }

            try {
                val captureBuilder = currentCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                captureBuilder.addTarget(imageReader.surface)
                val id = session.capture(captureBuilder.build(), captureCallback, handler)
                Timber.d("ZZZ: Capture request id: $id")
            } catch (e: CameraAccessException) {
                Timber.e(e, "ZZZ: Capture failed")
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Timber.e("ZZZ: Camera capture session configuration failed.")
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Timber.d("ZZZ: Camera ${camera.id} opened successfully")
            this@GlassesActivity.camera = camera

            try {
                camera.createCaptureSession(
                    Collections.singletonList(imageReader.surface),
                    cameraSessionListener,
                    handler,
                )
            } catch (e: CameraAccessException) {
                Timber.e(e, "ZZZ: Camera access exception")
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Timber.w("ZZZ: Camera ${camera.id} was disconnected")
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Timber.e("ZZZ: Camera ${camera.id} encountered an error: $error")
            camera.close()
        }
    }

    private lateinit var imageReader: ImageReader
    private lateinit var handler: Handler
    private var camera: CameraDevice? = null

    /**
     * Converts an Image in YUV_420_888 format to a JPEG byte array.
     */
    private fun convertYuvToJpeg(image: Image): ByteArray {
        // 1. Extract Y, U, and V planes into a single byte array.
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped in NV21 format compared to YUV_420_888 plane order
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // 2. Create a YuvImage.
        // NV21 is a common format supported by YuvImage.
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

        // 3. Compress to JPEG.
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out) // 95% quality

        return out.toByteArray()
    }

    /**
     * Saves a byte array to a JPEG file in a background thread.
     */
    private fun saveBytesToFile(bytes: ByteArray) {
        // Use a thread to avoid disk I/O on the handler's thread.
        Thread {
            val outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val outputFile = File(outputDir, "IMG_${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(outputFile).use { output ->
                    output.write(bytes)
                    Timber.d("ZZZ: Image saved successfully to ${outputFile.absolutePath}")
                }
            } catch (e: IOException) {
                Timber.e(e, "ZZZ: Error writing image to file")
            }
        }.start()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun setUpCamera2() {
        Timber.d("ZZZ: setUpCamera2")

        val cameraManager = getSystemService<CameraManager>()
        Timber.d("ZZZ: cameraManager: $cameraManager")
        check(cameraManager != null)
        handler = Handler(Looper.getMainLooper())

        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                Timber.w("ZZZ: No cameras found on this device.")
                return
            }

            // Let's try to open the first camera in the list
            val firstCameraId = cameraIds[0]
            Timber.d("ZZZ: Attempting to open camera with ID: $firstCameraId")

            // Print characteristics for debugging
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(firstCameraId)
            printCharacteristics(firstCameraId, cameraCharacteristics)
//            val width = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.width()
//            val height = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.height()
//            check(width != null && height != null)
            val width = 1024
            val height = 768
            Timber.d("ZZZ: Camera resolution: $width x $height")
            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 10)
            imageReader.setOnImageAvailableListener(
                { reader ->
                    // acquireLatestImage must be paired with image.close() in a finally block
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                    try {
                        // Check if the image format is what we expect.
                        if (image.format == ImageFormat.YUV_420_888) {
                            Timber.d("ZZZ: New YUV image available: ${image.width}x${image.height}")
                            // Convert YUV to JPEG bytes
                            val jpegBytes = convertYuvToJpeg(image)

                            // Offload file saving to a background thread
                            saveBytesToFile(jpegBytes)
                        } else {
                            Timber.w("ZZZ: Received image in unexpected format: ${image.format}")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "ZZZ: Failed to process YUV image")
                    } finally {
                        // CRITICAL: Always close the image to free the buffer for the next frame.
                        image.close()
                    }
                },
                handler,
            )

            // Request to open the camera
            cameraManager.openCamera(
                firstCameraId,
                cameraStateCallback,
                handler,
            )

        } catch (e: SecurityException) {
            Timber.e(e, "ZZZ: Failed to open camera due to security exception. Are permissions granted?")
        } catch (e: Exception) {
            Timber.e(e, "ZZZ: Failed to set up camera.")
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
        camera?.close()
        camera = null
        if (::imageReader.isInitialized) {
            imageReader.close()
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
