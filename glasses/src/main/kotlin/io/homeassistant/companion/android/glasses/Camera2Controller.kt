package io.homeassistant.companion.android.glasses

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.Collections

class Camera2Controller(private val context: Context) {

    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // Client provided callback to invoke when we receive image data.
    private var onImageCaptured: ((ByteArray) -> Unit)? = null

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // TODO: Do we need this?
    val isCameraOpen: Boolean
        get() = camera != null && captureSession != null

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(onImageCaptured: (ByteArray) -> Unit) {
        Timber.d("ZZZ: Camera2Controller.openCamera")

        handlerThread = HandlerThread("Camera2Controller").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        this.onImageCaptured = onImageCaptured

        try {
            val cameraId = getFirstCameraId() ?: run {
                Timber.w("ZZZ: No cameras found")
                return
            }

            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            Camera2Utils.printCharacteristics(cameraId, cameraCharacteristics)

            val width = 1024
            val height = 768
            Timber.d("ZZZ: Camera resolution: $width x $height")

            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 10)
            imageReader?.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                    try {
                        if (image.format == ImageFormat.YUV_420_888) {
                            Timber.d("ZZZ: New YUV image: ${image.width}x${image.height}")
                            val jpegBytes = Camera2Utils.convertYuvToJpeg(image)
                            Camera2Utils.saveBytesToFile(context, jpegBytes)
                            onImageCaptured(jpegBytes)
                        } else {
                            Timber.w("ZZZ: Unexpected format: ${image.format}")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "ZZZ: Failed to process image")
                    } finally {
                        image.close()
                    }
                },
                handler,
            )

            cameraManager.openCamera(cameraId, createStateCallback(), handler)

        } catch (e: SecurityException) {
            Timber.e(e, "ZZZ: Camera permission not granted")
        } catch (e: Exception) {
            Timber.e(e, "ZZZ: Failed to start camera")
        }
    }

    fun capturePhoto() {
        val currentCamera = camera
        val session = captureSession

        if (session == null || currentCamera == null) {
            Timber.w("ZZZ: Cannot capture photo: camera not ready")
            return
        }

        try {
            val builder = currentCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface)
            session.capture(builder.build(), createCaptureCallback(), handler)
        } catch (e: CameraAccessException) {
            Timber.e(e, "ZZZ: Capture failed")
        }
    }

    fun closeCamera() {
        Timber.d("ZZZ: Camera2Controller.closeCamera")

        imageReader?.setOnImageAvailableListener(null, handler)

        captureSession?.close()
        captureSession = null

        camera?.close()
        camera = null

        imageReader?.close()
        imageReader = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        onImageCaptured = null
    }

    private fun getFirstCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun createStateCallback(): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Timber.d("ZZZ: Camera ${camera.id} opened")
                this@Camera2Controller.camera = camera

                try {
                    camera.createCaptureSession(
                        Collections.singletonList(imageReader?.surface),
                        createSessionCallback(),
                        handler,
                    )
                } catch (e: CameraAccessException) {
                    Timber.e(e, "ZZZ: Failed to create capture session")
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Timber.w("ZZZ: Camera ${camera.id} disconnected")
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Timber.e("ZZZ: Camera error: $error")
                camera.close()
            }
        }
    }

    private fun createSessionCallback(): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Timber.d("ZZZ: Session configured")
                captureSession = session
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Timber.e("ZZZ: Session configuration failed")
            }
        }
    }

    private fun createCaptureCallback(): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                Timber.d("ZZZ: Capture completed")
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                Timber.e("ZZZ: Capture failed: ${failure.reason}")
            }
        }
    }
}
