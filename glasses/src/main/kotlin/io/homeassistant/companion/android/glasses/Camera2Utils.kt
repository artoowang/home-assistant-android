package io.homeassistant.companion.android.glasses

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Environment
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

    fun convertYuvToJpeg(image: Image): ByteArray {
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

        return out.toByteArray()
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
}
