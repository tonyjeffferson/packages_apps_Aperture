/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.lineageos.aperture.models.FlashMode
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * Xiaomi QCFA capture path used by the vermeer main camera.
 *
 * The Xiaomi processing controls are private Camera2 request keys. The class is only selected on
 * vermeer; all other devices keep using Aperture's CameraX capture path.
 */
class XiaomiQcfaCapture(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    fun isSupportedDevice() = android.os.Build.DEVICE.equals("vermeer", ignoreCase = true)

    fun jpegOrientation(cameraId: String, deviceOrientation: Int): Int {
        // The vermeer QCFA JPEG is already laid out for landscape captures. Applying the
        // regular sensor compensation in landscape can add a 180 degree EXIF rotation.
        if (deviceOrientation == 90 || deviceOrientation == 270) {
            return 0
        }
        val sensorOrientation = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return (sensorOrientation - deviceOrientation + 360) % 360
    }

    suspend fun capture(cameraId: String, flashMode: FlashMode): ByteArray? {
        repeat(MAX_CAMERA_OPEN_ATTEMPTS) { attempt ->
            try {
                return captureOnce(cameraId, flashMode)
            } catch (exception: CameraInUseException) {
                Log.w(
                    TAG,
                    "Camera $cameraId is still owned by CameraX; retry ${attempt + 1}/$MAX_CAMERA_OPEN_ATTEMPTS",
                )
                if (attempt == MAX_CAMERA_OPEN_ATTEMPTS - 1) {
                    return null
                }
                delay(CAMERA_OPEN_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private suspend fun captureOnce(cameraId: String, flashMode: FlashMode): ByteArray? {
        Log.i(TAG, "Starting QCFA capture: camera=$cameraId, output=${OUTPUT_SIZE.width}x${OUTPUT_SIZE.height}")
        val jpeg = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
        val thread = HandlerThread("ApertureXiaomiQcfa").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { command -> handler.post(command) }
        val completed = CompletableDeferred<ByteArray?>()
        val reader = ImageReader.newInstance(
            OUTPUT_SIZE.width,
            OUTPUT_SIZE.height,
            ImageFormat.JPEG,
            2,
        )
        val texture = SurfaceTexture(0).apply {
            setDefaultBufferSize(PREVIEW_SIZE.width, PREVIEW_SIZE.height)
        }
        val previewSurface = Surface(texture)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            reader.setOnImageAvailableListener({ imageReader ->
                try {
                    imageReader.acquireNextImage()?.use { image ->
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        Log.i(TAG, "QCFA image received: ${image.width}x${image.height}, ${bytes.size} bytes")
                        // Do not silently save an upscaled or HAL-clamped image as a 50 MP photo.
                        if (image.width != OUTPUT_SIZE.width || image.height != OUTPUT_SIZE.height) {
                            Log.w(TAG, "HAL returned a smaller image; rejecting QCFA result")
                            completed.complete(null)
                            return@setOnImageAvailableListener
                        }
                        if (!containsUsableImage(bytes)) {
                            Log.w(TAG, "HAL returned an almost-black QCFA frame; falling back to CameraX")
                            completed.complete(null)
                            return@setOnImageAvailableListener
                        }
                        completed.complete(bytes)
                    }
                } catch (exception: Throwable) {
                    Log.e(TAG, "Failed to read QCFA image", exception)
                    completed.complete(null)
                }
            }, handler)

            cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "QCFA camera opened: $cameraId")
                    device = camera
                    try {
                        camera.createCaptureSession(
                            listOf(previewSurface, reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configuredSession: CameraCaptureSession) {
                                    Log.i(TAG, "QCFA session configured")
                                    session = configuredSession
                                    try {
                                        val request = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_MODE,
                                                CaptureRequest.CONTROL_MODE_AUTO)
                                            applyFlashMode(cameraId, flashMode)
                                            set(CaptureRequest.Key(
                                                "xiaomi.remosaic.enabled", Byte::class.javaObjectType
                                            ), 1.toByte())
                                            set(CaptureRequest.Key(
                                                "xiaomi.quadcfa.enabled", Byte::class.javaObjectType
                                            ), 1.toByte())
                                            set(CaptureRequest.Key(
                                                "xiaomi.superResolution.enabled", Byte::class.javaObjectType
                                            ), 1.toByte())
                                        }
                                        configuredSession.capture(
                                            request.build(),
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureStarted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    timestamp: Long,
                                                    frameNumber: Long,
                                                ) {
                                                    Log.i(TAG, "QCFA capture started: frame=$frameNumber")
                                                }

                                                override fun onCaptureFailed(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    failure: CaptureFailure,
                                                ) {
                                                    Log.e(TAG, "QCFA capture failed: reason=${failure.reason}")
                                                    completed.complete(null)
                                                }

                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: android.hardware.camera2.TotalCaptureResult,
                                                ) {
                                                    Log.i(
                                                        TAG,
                                                        "QCFA capture completed: frame=${result.frameNumber}, " +
                                                            "pixelMode=${result.get(CaptureResult.SENSOR_PIXEL_MODE)}, " +
                                                            "exposure=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)}, " +
                                                            "sensitivity=${result.get(CaptureResult.SENSOR_SENSITIVITY)}",
                                                    )
                                                }
                                            },
                                            handler,
                                        )
                                        Log.i(TAG, "QCFA request submitted with remosaic/quadcfa/super-resolution")
                                    } catch (exception: Exception) {
                                        Log.e(TAG, "Failed to submit QCFA request", exception)
                                        completed.complete(null)
                                    }
                                }

                                override fun onConfigureFailed(
                                    failedSession: CameraCaptureSession,
                                ) {
                                    Log.e(TAG, "QCFA session configuration failed")
                                    completed.complete(null)
                                }
                            },
                            handler,
                        )
                    } catch (_: Exception) {
                        completed.complete(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.e(TAG, "QCFA camera disconnected")
                    completed.complete(null)
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "QCFA camera open error: code=$error")
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
                        completed.completeExceptionally(CameraInUseException())
                    } else {
                        completed.complete(null)
                    }
                    camera.close()
                }
            })

            completed.await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: CameraAccessException) {
            if (exception.reason == CameraAccessException.CAMERA_IN_USE) {
                throw CameraInUseException(exception)
            }
            Log.e(TAG, "Camera2 could not open the QCFA camera", exception)
            null
        } catch (exception: CameraInUseException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "QCFA capture threw an exception", exception)
            null
        } finally {
            session?.close()
            device?.close()
            reader.close()
            previewSurface.release()
            texture.release()
            thread.quitSafely()
        }
        }

        if (jpeg == null) {
            Log.w(TAG, "QCFA capture returned no full-resolution image")
        } else {
            Log.i(TAG, "QCFA capture returned ${jpeg.size} bytes")
        }
        return jpeg
    }

    private fun CaptureRequest.Builder.applyFlashMode(cameraId: String, flashMode: FlashMode) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?.toSet()
            .orEmpty()
        when (flashMode) {
            FlashMode.OFF, FlashMode.SCREEN -> {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            FlashMode.AUTO -> {
                if (CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH in aeModes) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                } else {
                    Log.w(TAG, "Camera $cameraId does not advertise auto-flash AE; using flash off")
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            FlashMode.ON -> {
                if (CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH in aeModes) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                } else {
                    Log.w(TAG, "Camera $cameraId lacks always-flash AE; requesting a single flash")
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }
            }
            FlashMode.TORCH -> {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
        Log.i(TAG, "QCFA flash request: mode=$flashMode aeModes=$aeModes")
    }

    private class CameraInUseException(cause: Throwable? = null) : Exception(cause)

    /** Reject broken HAL output that has full JPEG dimensions but contains no usable image. */
    private fun containsUsableImage(jpeg: ByteArray): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = IMAGE_VALIDATION_SAMPLE_SIZE
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            },
        ) ?: return false

        return try {
            var luminanceSum = 0L
            var maxLuminance = 0
            val pixelCount = bitmap.width * bitmap.height
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance =
                        (android.graphics.Color.red(pixel) * 299 +
                            android.graphics.Color.green(pixel) * 587 +
                            android.graphics.Color.blue(pixel) * 114) / 1000
                    luminanceSum += luminance
                    maxLuminance = maxOf(maxLuminance, luminance)
                }
            }
            val meanLuminance = luminanceSum.toDouble() / pixelCount
            Log.i(TAG, "QCFA image validation: meanLuma=$meanLuminance maxLuma=$maxLuminance")
            meanLuminance >= MIN_MEAN_LUMINANCE || maxLuminance >= MIN_MAX_LUMINANCE
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "ApertureQcfa"
        val OUTPUT_SIZE = Size(8192, 6144)
        val PREVIEW_SIZE = Size(1280, 720)
        const val CAPTURE_TIMEOUT_MS = 15_000L
        const val MAX_CAMERA_OPEN_ATTEMPTS = 8
        const val CAMERA_OPEN_RETRY_DELAY_MS = 250L
        const val IMAGE_VALIDATION_SAMPLE_SIZE = 128
        const val MIN_MEAN_LUMINANCE = 5.0
        const val MIN_MAX_LUMINANCE = 48
    }
}
