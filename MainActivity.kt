package com.example.meteorcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var rangeText: TextView
    private lateinit var countdownText: TextView
    private lateinit var exposureSpinner: Spinner
    private lateinit var isoSpinner: Spinner
    private lateinit var continuousCheck: CheckBox
    private lateinit var captureButton: Button

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId = "0"

    private var exposureRange: Range<Long>? = null
    private var isoRange: Range<Int>? = null
    private var sensorWidth = 0
    private var sensorHeight = 0

    private var isCapturing = false
    private var captureCount = 0

    private val handler = Handler(Looper.getMainLooper())

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            status("Kamera açık.")
            createSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            status("Kamera bağlantısı kesildi.")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            status("Kamera hatası: $error")
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            saveJpeg(bytes)
        } finally {
            image.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        rangeText = findViewById(R.id.rangeText)
        countdownText = findViewById(R.id.countdownText)
        exposureSpinner = findViewById(R.id.exposureSpinner)
        isoSpinner = findViewById(R.id.isoSpinner)
        continuousCheck = findViewById(R.id.continuousCheck)
        captureButton = findViewById(R.id.captureButton)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        exposureSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("5 sn", "10 sn", "15 sn", "20 sn", "25 sn", "30 sn")
        )

        isoSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("ISO 400", "ISO 800", "ISO 1600", "ISO 3200", "ISO 6400", "ISO Otomatik")
        )

        textureView.surfaceTextureListener = textureListener
        captureButton.setOnClickListener {
            if (!isCapturing) startCountdown()
            else stopCapture()
        }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            status("Kamera izni gerekli.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!hasCameraPermission()) return

        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val best = jpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: Size(1920, 1080)

            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            sensorWidth = sensorRect?.width() ?: 0
            sensorHeight = sensorRect?.height() ?: 0

            exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

            rangeText.text = buildString {
                append("Pozlama: ")
                if (exposureRange != null) {
                    append(formatExposure(exposureRange!!.lower))
                    append(" – ")
                    append(formatExposure(exposureRange!!.upper))
                } else append("bilinmiyor")
                append("   ISO: ")
                if (isoRange != null) append("${isoRange!!.lower}–${isoRange!!.upper}")
                else append("bilinmiyor")
            }

            imageReader?.close()
            imageReader = ImageReader.newInstance(
                best.width, best.height, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener(imageListener, handler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, handler)
        } catch (e: Exception) {
            status("Kamera açılamadı: ${e.message}")
        }
    }

    private fun createSession() {
        val texture = textureView.surfaceTexture ?: return
        val previewSize = Size(1920, 1080)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)

        val previewSurface = Surface(texture)
        val jpegSurface = imageReader!!.surface

        try {
            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, jpegSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview(previewSurface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        status("Kamera oturumu oluşturulamadı.")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            status("Oturum hatası: ${e.message}")
        }
    }

    private fun startPreview(previewSurface: Surface) {
        val camera = cameraDevice ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            captureSession?.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            status("Önizleme hatası: ${e.message}")
        }
    }

    private fun startCountdown() {
        if (cameraDevice == null || captureSession == null) {
            status("Kamera henüz hazır değil.")
            return
        }

        isCapturing = true
        captureCount = 0
        captureButton.text = "DURDUR"

        countdownText.visibility = TextView.VISIBLE

        var remaining = 5
        countdownText.text = remaining.toString()

        val tick = object : Runnable {
            override fun run() {
                if (!isCapturing) {
                    countdownText.visibility = TextView.GONE
                    return
                }
                remaining--
                if (remaining <= 0) {
                    countdownText.visibility = TextView.GONE
                    takePhoto()
                } else {
                    countdownText.text = remaining.toString()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    private fun stopCapture() {
        isCapturing = false
        handler.removeCallbacksAndMessages(null)
        countdownText.visibility = TextView.GONE
        captureButton.text = "5 sn geri sayım → ÇEK"
        status("Çekim durduruldu.")
        try {
            cameraDevice?.let {
                captureSession?.abortCaptures()
                val surfaceTexture = textureView.surfaceTexture ?: return
                val previewSurface = Surface(surfaceTexture)
                startPreview(previewSurface)
            }
        } catch (_: Exception) {}
    }

    private fun takePhoto() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return

        val requestedSeconds = listOf(5L, 10L, 15L, 20L, 25L, 30L)
            .getOrNull(exposureSpinner.selectedItemPosition) ?: 30L

        val requestedNs = requestedSeconds * 1_000_000_000L
        val actualExposure = exposureRange?.let {
            requestedNs.coerceIn(it.lower, it.upper)
        } ?: requestedNs

        val iso = when (isoSpinner.selectedItemPosition) {
            0 -> 400
            1 -> 800
            2 -> 1600
            3 -> 3200
            4 -> 6400
            else -> null
        }?.let { requested ->
            isoRange?.let { requested.coerceIn(it.lower, it.upper) } ?: requested
        }

        status("Pozlama: ${formatExposure(actualExposure)} — çekiliyor…")

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)

                // Manuel pozlama.
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposure)

                if (iso != null) {
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                } else {
                    // ISO Otomatik seçildiğinde, manuel AE ile birlikte sensör
                    // ISO'su için mevcut alt sınıra yakın güvenli bir değer kullan.
                    val fallbackIso = isoRange?.lower ?: 100
                    set(CaptureRequest.SENSOR_SENSITIVITY, fallbackIso)
                }

                // Meteor için sonsuz odak.
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

                // Beyaz dengesini sabitle.
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)

                // Tripod çekiminde elektronik stabilizasyonu kapatmayı dene.
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)

                set(CaptureRequest.JPEG_ORIENTATION, 90)
            }

            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    captureCount++
                    status("Fotoğraf $captureCount kaydediliyor…")

                    if (isCapturing && continuousCheck.isChecked) {
                        handler.postDelayed({ takePhoto() }, 400)
                    } else {
                        isCapturing = false
                        captureButton.text = "5 sn geri sayım → ÇEK"
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    status("Çekim başarısız: ${failure.reason}")
                    if (isCapturing && continuousCheck.isChecked) {
                        handler.postDelayed({ takePhoto() }, 1000)
                    } else {
                        isCapturing = false
                        captureButton.text = "5 sn geri sayım → ÇEK"
                    }
                }
            }, handler)
        } catch (e: Exception) {
            status("Pozlama isteği reddedildi: ${e.message}")
            isCapturing = false
            captureButton.text = "5 sn geri sayım → ÇEK"
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "Meteor_${stamp}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MeteorCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri: Uri? = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )

        if (uri == null) {
            runOnUiThread { status("Fotoğraf kaydedilemedi.") }
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            runOnUiThread {
                status("Kaydedildi: $name")
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            runOnUiThread { status("Kayıt hatası: ${e.message}") }
        }
    }

    private fun formatExposure(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1f sn", seconds)
        } else {
            "${ns / 1_000_000} ms"
        }
    }

    private fun status(message: String) {
        runOnUiThread { statusText.text = message }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
    }
}
