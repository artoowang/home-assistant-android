package io.homeassistant.companion.android.glasses

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import timber.log.Timber


object Camera2Utils {
    fun printCharacteristics(cameraId: String, characteristics: android.hardware.camera2.CameraCharacteristics) {
        Timber.d("ZZZ: --- CameraCharacteristics for Camera ID: $cameraId ---")

        val keys = characteristics.keys

        if (keys.isEmpty()) {
            Timber.d("ZZZ: No characteristics keys found for camera $cameraId")
            return
        }

        for (key in keys) {
            try {
                val value = characteristics.get(key)
                val valueString = when (value) {
                    is Array<*> -> Arrays.toString(value)
                    is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
                    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
                    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
                    else -> value.toString()
                }
                Timber.d("ZZZ: Key: ${key.name}, Value: $valueString")
            } catch (e: Exception) {
                Timber.w("ZZZ: Could not get value for key ${key.name}")
            }
        }
        Timber.d("ZZZ: --- End of Characteristics for Camera ID: $cameraId ---")
    }

    fun convertYuvToJpeg(image: Image, orientation: Int = 0): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)

        val jpegBytes = out.toByteArray()

        if (orientation != 0) {
            return addExifOrientation(jpegBytes, orientation)
        }

        return jpegBytes
    }

    private fun addExifOrientation(jpegBytes: ByteArray, orientation: Int): ByteArray {
        return try {
            val tempFile = File.createTempFile("temp_jpeg", ".jpg")
            try {
                FileOutputStream(tempFile).use { it.write(jpegBytes) }
                val exif = ExifInterface(tempFile.absolutePath)
                val exifOrientation = when (orientation) {
                    0 -> ExifInterface.ORIENTATION_NORMAL
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                exif.saveAttributes()
                tempFile.inputStream().use { it.readBytes() }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "ZZZ: Failed to add EXIF orientation")
            jpegBytes
        }
    }

    fun saveBytesToFile(context: Context, bytes: ByteArray) {
        Thread {
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
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

    // Combined the given image `orientation` with the sensor orientation in the camera characteristics.
    // Modified from https://developer.android.com/reference/android/hardware/camera2/CaptureResult#JPEG_ORIENTATION
    fun combineOrientationWithSensorOrientation(c: CameraCharacteristics, orientation: Int): Int {
        val sensorOrientation: Int? = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
        if (sensorOrientation == null) {
            Timber.w("ZZZ: No sensor orientation found. Keep the original orientation.")
            return orientation
        }

        // Round device orientation to a multiple of 90.
        var orientation = (orientation + 45) / 90 * 90

        // Reverse device orientation for front-facing cameras.
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            orientation = -orientation
        }

        // Calculate combined orientation.
        return (sensorOrientation + orientation + 360) % 360
    }
}
