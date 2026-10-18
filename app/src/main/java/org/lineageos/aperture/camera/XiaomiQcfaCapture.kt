/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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

    suspend fun capture(
        cameraId: String,
        deviceOrientation: Int,
        flashMode: FlashMode,
    ): ByteArray? =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
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
                        // Do not silently save an upscaled or HAL-clamped image as a 50 MP photo.
                        if (image.width < OUTPUT_SIZE.width || image.height < OUTPUT_SIZE.height) {
                            completed.complete(null)
                            return@setOnImageAvailableListener
                        }
                        val buffer: ByteBuffer = image.planes[0].buffer
                        completed.complete(ByteArray(buffer.remaining()).also(buffer::get))
                    }
                } catch (_: Throwable) {
                    completed.complete(null)
                }
            }, handler)

            cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        camera.createCaptureSession(
                            listOf(previewSurface, reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configuredSession: CameraCaptureSession) {
                                    session = configuredSession
                                    try {
                                        val request = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_MODE,
                                                CaptureRequest.CONTROL_MODE_AUTO)
                                            set(
                                                CaptureRequest.CONTROL_AE_MODE,
                                                when (flashMode) {
                                                    FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                                                    FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                                                    else -> CaptureRequest.CONTROL_AE_MODE_ON
                                                },
                                            )
                                            if (flashMode == FlashMode.TORCH) {
                                                set(
                                                    CaptureRequest.FLASH_MODE,
                                                    CaptureRequest.FLASH_MODE_TORCH,
                                                )
                                            }
                                            val sensorOrientation = cameraManager
                                                .getCameraCharacteristics(cameraId)
                                                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                            set(
                                                CaptureRequest.JPEG_ORIENTATION,
                                                (sensorOrientation - deviceOrientation + 360) % 360,
                                            )
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
                                                override fun onCaptureFailed(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    failure: CaptureFailure,
                                                ) {
                                                    completed.complete(null)
                                                }
                                            },
                                            handler,
                                        )
                                    } catch (_: Exception) {
                                        completed.complete(null)
                                    }
                                }

                                override fun onConfigureFailed(
                                    failedSession: CameraCaptureSession,
                                ) {
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
                    completed.complete(null)
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    completed.complete(null)
                    camera.close()
                }
            })

            completed.await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
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

    private companion object {
        val OUTPUT_SIZE = Size(8192, 6144)
        val PREVIEW_SIZE = Size(1280, 720)
        const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
