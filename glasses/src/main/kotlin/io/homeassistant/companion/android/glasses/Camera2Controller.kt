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
import java.util.Collections
import timber.log.Timber

class Camera2Controller(private val context: Context) {

    private var camera: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // -----------------------------------------------------------------------------------------------------------------
    // State flags: we need to use these flags to track asynchronous events (but all on the same thread), since there is
    // no guarantee those events will be received in a fixed order.

    // Indicates if CaptureCallback.onCaptureCompleted() has been invoked.
    private var captureCompleted = false
    // Indicates if OnImageAvailableListener has been invoked.
    private var imageReceived = false
    // Indicates if cleanup() has already been called.
    private var hasCleanedUp = false

    // -----------------------------------------------------------------------------------------------------------------

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun capturePhotoWithSession(onImageCaptured: (ByteArray) -> Unit) {
        Timber.d("ZZZ: Camera2Controller.capturePhotoWithSession")

        if (handler != null) {
            Timber.w("ZZZ: another camera capture session already exists. Ignored new request.")
            return
        }

        resetCaptureState()

        handlerThread = HandlerThread("Camera2Controller").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        try {
            val cameraId = getFirstCameraId() ?: run {
                Timber.w("ZZZ: No cameras found")
                cleanup()
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
                    if (hasCleanedUp) {
                        Timber.d("ZZZ: Ignoring image - the camera capture has already been cleaned up")
                        return@setOnImageAvailableListener
                    }

                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    var jpegBytes: ByteArray? = null

                    try {
                        if (image.format == ImageFormat.YUV_420_888) {
                            Timber.d("ZZZ: New YUV image: ${image.width}x${image.height}")
                            jpegBytes = Camera2Utils.convertYuvToJpeg(image)
                        } else {
                            Timber.w("ZZZ: Unexpected format: ${image.format}")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "ZZZ: Failed to process image")
                    } finally {
                        // Once we reached here, we are done with the `image` and `reader`.
                        image.close()
                        imageReceived = true
                        if (captureCompleted) {
                            Timber.d("ZZZ: Image processed and capture result received, cleaning up")
                            cleanup()
                        }
                    }

                    if (jpegBytes != null) {
                        onImageCaptured(jpegBytes)
                    } else {
                        Timber.e("ZZZ: Failed to obtain JPEG image")
                    }
                },
                handler,
            )

            cameraManager.openCamera(cameraId, createStateCallback(), handler)
        } catch (e: SecurityException) {
            Timber.e(e, "ZZZ: Camera permission not granted")
            cleanup()
        } catch (e: Exception) {
            Timber.e(e, "ZZZ: Failed to capture photo")
            cleanup()
        }
    }

    // Resets capture state flags.
    private fun resetCaptureState() {
        captureCompleted = false
        imageReceived = false
        hasCleanedUp = false
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
                    cleanup()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Timber.w("ZZZ: Camera ${camera.id} disconnected")
                cleanup()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Timber.e("ZZZ: Camera error: $error")
                cleanup()
            }
        }
    }

    private fun createSessionCallback(): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Timber.d("ZZZ: Session configured")
                captureSession = session
                capturePhoto()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Timber.e("ZZZ: Session configuration failed")
                cleanup()
            }
        }
    }

    private fun capturePhoto() {
        val currentCamera = camera
        val session = captureSession

        if (session == null || currentCamera == null) {
            Timber.w("ZZZ: Cannot capture photo: camera not ready")
            cleanup()
            return
        }

        try {
            val builder = currentCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface)
            session.capture(builder.build(), createCaptureCallback(), handler)
        } catch (e: CameraAccessException) {
            Timber.e(e, "ZZZ: Capture failed")
            cleanup()
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
                captureCompleted = true
                if (imageReceived) {
                    Timber.d("ZZZ: Capture result received and image processed, cleaning up")
                    cleanup()
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                Timber.e("ZZZ: Capture failed: ${failure.reason}")
                cleanup()
            }
        }
    }

    private fun cleanup() {
        if (hasCleanedUp) {
            Timber.d("ZZZ: Cleanup already in progress, skipping")
            return
        }
        hasCleanedUp = true

        Timber.d("ZZZ: Camera2Controller.cleanup")

        imageReader?.setOnImageAvailableListener(null, handler)

        captureSession?.close()
        captureSession = null

        camera?.close()
        camera = null

        // TODO: I got "[ImageReader-1024x768f23m10-19156-0] abandonLocked: ConsumerBase is abandoned!" with this, but
        // no idea what I can do to avoid it.
        imageReader?.close()
        imageReader = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }
}
