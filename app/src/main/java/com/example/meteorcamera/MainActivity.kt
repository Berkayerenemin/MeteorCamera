```kotlin
package com.example.meteorcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
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
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var countdownText: TextView
    private lateinit var exposureSpinner: Spinner
    private lateinit var isoSpinner: Spinner
    private lateinit var customSeconds: EditText
    private lateinit var totalMinutes: EditText
    private lateinit var deepSkyButton: Button
    private lateinit var meteorButton: Button
    private lateinit var captureButton: Button
    private lateinit var stopButton: Button

    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var cameraId = "0"

    private var exposureRange: Range<Long>? = null
    private var isoRange: Range<Int>? = null

    private var isCapturing = false
    private var currentFrame = 0
    private var targetFrames = 1

    private var currentExposureNs = 30_000_000_000L
    private var currentIso = 1600

    private val handler = Handler(Looper.getMainLooper())

    private val cameraCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            status("Kamera hazır.")
            createCameraSession()
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
            savePhoto(bytes)
        } finally {
            image.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        countdownText = findViewById(R.id.countdownText)

        exposureSpinner = findViewById(R.id.exposureSpinner)
        isoSpinner = findViewById(R.id.isoSpinner)

        customSeconds = findViewById(R.id.customSeconds)
        totalMinutes = findViewById(R.id.totalMinutes)

        deepSkyButton = findViewById(R.id.deepSkyButton)
        meteorButton = findViewById(R.id.meteorButton)

        captureButton = findViewById(R.id.captureButton)
        stopButton = findViewById(R.id.stopButton)

        cameraManager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager

        setupUI()

        textureView.surfaceTextureListener =
            textureListener

        captureButton.setOnClickListener {
            if (!isCapturing) {
                startCountdown()
            }
        }

        stopButton.setOnClickListener {
            stopCapture()
        }

        deepSkyButton.setOnClickListener {
            applyDeepSky()
        }

        meteorButton.setOnClickListener {
            applyMeteor()
        }

        if (!hasCameraPermission()) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                100
            )

        } else {

            if (textureView.isAvailable) {
                openCamera()
            }
        }
    }

    private fun setupUI() {

        val exposureOptions = listOf(
            "5 sn",
            "10 sn",
            "20 sn",
            "30 sn",
            "60 sn",
            "120 sn",
            "180 sn",
            "300 sn",
            "Özel"
        )

        exposureSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                exposureOptions
            )

        isoSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "ISO 100",
                    "ISO 200",
                    "ISO 400",
                    "ISO 800",
                    "ISO 1600",
                    "ISO 3200",
                    "ISO 6400",
                    "ISO MAX"
                )
            )

        exposureSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(parent: AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {

                    customSeconds.isEnabled =
                        position == 8
                }
            }
    }

    private val textureListener =
        object : TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {}

            override fun onSurfaceTextureDestroyed(
                surface: SurfaceTexture
            ): Boolean = true

            override fun onSurfaceTextureUpdated(
                surface: SurfaceTexture
            ) {}
        }

    private fun hasCameraPermission(): Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 100 &&
            grantResults.firstOrNull() ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        } else {
            status("Kamera izni gerekli.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {

        if (!hasCameraPermission()) return

        try {

            val characteristics =
                cameraManager.getCameraCharacteristics(cameraId)

            exposureRange =
                characteristics.get(
                    CameraCharacteristics
                        .SENSOR_INFO_EXPOSURE_TIME_RANGE
                )

            isoRange =
                characteristics.get(
                    CameraCharacteristics
                        .SENSOR_INFO_SENSITIVITY_RANGE
                )

            val map =
                characteristics.get(
                    CameraCharacteristics
                        .SCALER_STREAM_CONFIGURATION_MAP
                )

            val jpegSizes =
                map?.getOutputSizes(ImageFormat.JPEG)
                    ?: emptyArray()

            val largest =
                jpegSizes.maxByOrNull {
                    it.width.toLong() * it.height
                } ?: Size(1920, 1080)

            imageReader?.close()

            imageReader =
                ImageReader.newInstance(
                    largest.width,
                    largest.height,
                    ImageFormat.JPEG,
                    3
                )

            imageReader?.setOnImageAvailableListener(
                imageListener,
                handler
            )

            updateCameraInfo()

            cameraManager.openCamera(
                cameraId,
                cameraCallback,
                handler
            )

        } catch (e: Exception) {

            status(
                "Kamera açılamadı: ${e.message}"
            )
        }
    }

    private fun updateCameraInfo() {

        val exposureText =
            exposureRange?.let {

                "${formatExposure(it.lower)} - " +
                        "${formatExposure(it.upper)}"

            } ?: "Bilinmiyor"

        val isoText =
            isoRange?.let {

                "${it.lower} - ${it.upper}"

            } ?: "Bilinmiyor"

        infoText.text =
            "Sensör pozlama: $exposureText\n" +
                    "ISO aralığı: $isoText"
    }

    private fun createCameraSession() {

        val texture =
            textureView.surfaceTexture ?: return

        texture.setDefaultBufferSize(
            1920,
            1080
        )

        val previewSurface =
            Surface(texture)

        val jpegSurface =
            imageReader!!.surface

        try {

            cameraDevice?.createCaptureSession(
                listOf(
                    previewSurface,
                    jpegSurface
                ),

                object :
                    CameraCaptureSession.StateCallback() {

                    override fun onConfigured(
                        session: CameraCaptureSession
                    ) {

                        captureSession = session

                        startPreview(
                            previewSurface
                        )
                    }

                    override fun onConfigureFailed(
                        session: CameraCaptureSession
                    ) {

                        status(
                            "Kamera oturumu oluşturulamadı."
                        )
                    }
                },

                handler
            )

        } catch (e: Exception) {

            status(
                "Oturum hatası: ${e.message}"
            )
        }
    }

    private fun startPreview(
        previewSurface: Surface
    ) {

        val camera =
            cameraDevice ?: return

        try {

            val request =
                camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
                )

            request.addTarget(
                previewSurface
            )

            request.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest
                    .CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            captureSession?.setRepeatingRequest(
                request.build(),
                null,
                handler
            )

        } catch (e: Exception) {

            status(
                "Önizleme hatası: ${e.message}"
            )
        }
    }

    private fun getExposureSeconds(): Long {

        return when (
            exposureSpinner.selectedItemPosition
        ) {

            0 -> 5
            1 -> 10
            2 -> 20
            3 -> 30
            4 -> 60
            5 -> 120
            6 -> 180
            7 -> 300

            else -> {

                val value =
                    customSeconds.text
                        .toString()
                        .toLongOrNull()

                value ?: 30
            }
        }.coerceIn(1, 300)
    }

    private fun getISO(): Int {

        val values =
            listOf(
                100,
                200,
                400,
                800,
                1600,
                3200,
                6400
            )

        val position =
            isoSpinner.selectedItemPosition

        if (position >= values.size) {

            return isoRange?.upper ?: 6400
        }

        val requested =
            values[position]

        return isoRange?.let {

            requested.coerceIn(
                it.lower,
                it.upper
            )

        } ?: requested
    }

    private fun calculateFrameCount(
        exposureSeconds: Long
    ): Int {

        val minutes =
            totalMinutes.text
                .toString()
                .toDoubleOrNull()
                ?: 0.0

        if (minutes <= 0) return 1

        val totalSeconds =
            minutes * 60.0

        return max(
            1,
            (totalSeconds /
                    exposureSeconds)
                .roundToInt()
        )
    }

    private fun startCountdown() {

        currentExposureNs =
            getExposureSeconds() *
                    1_000_000_000L

        currentIso =
            getISO()

        targetFrames =
            calculateFrameCount(
                getExposureSeconds()
            )

        currentFrame = 0

        if (exposureRange != null) {

            currentExposureNs =
                currentExposureNs.coerceIn(
                    exposureRange!!.lower,
                    exposureRange!!.upper
                )
        }

        isCapturing = true

        captureButton.isEnabled = false
        stopButton.isEnabled = true

        countdownText.visibility =
            android.view.View.VISIBLE

        var seconds = 5

        countdownText.text =
            seconds.toString()

        val runnable =
            object : Runnable {

                override fun run() {

                    if (!isCapturing) {

                        countdownText.visibility =
                            android.view.View.GONE

                        return
                    }

                    seconds--

                    if (seconds <= 0) {

                        countdownText.visibility =
                            android.view.View.GONE

                        takePhoto()

                    } else {

                        countdownText.text =
                            seconds.toString()

                        handler.postDelayed(
                            this,
                            1000
                        )
                    }
                }
            }

        handler.postDelayed(
            runnable,
            1000
        )
    }

    private fun takePhoto() {

        val camera =
            cameraDevice ?: return

        val session =
            captureSession ?: return

        status(
            "Kare ${currentFrame + 1}/$targetFrames — " +
                    "Pozlama ${formatExposure(currentExposureNs)}"
        )

        try {

            val request =
                camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                )

            request.addTarget(
                imageReader!!.surface
            )

            /*
             * MANUEL POZLAMA
             */

            request.set(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )

            request.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                currentExposureNs
            )

            request.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                currentIso
            )

            /*
             * SONSUZ ODAK
             */

            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )

            request.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                0.0f
            )

            /*
             * BEYAZ DENGESİ
             */

            request.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )

            request.set(
                CaptureRequest.CONTROL_AWB_LOCK,
                true
            )

            /*
             * OIS KAPALI
             */

            request.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest
                    .LENS_OPTICAL_STABILIZATION_MODE_OFF
            )

            request.set(
                CaptureRequest.JPEG_ORIENTATION,
                90
            )

            session.capture(
                request.build(),

                object :
                    CameraCaptureSession.CaptureCallback() {

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {

                        currentFrame++

                        status(
                            "Kare $currentFrame/$targetFrames tamamlandı — kaydediliyor…"
                        )

                        if (
                            isCapturing &&
                            currentFrame < targetFrames
                        ) {

                            handler.postDelayed(
                                {
                                    takePhoto()
                                },
                                500
                            )

                        } else {

                            finishCapture()
                        }
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {

                        status(
                            "Çekim başarısız. Kod: ${failure.reason}"
                        )

                        finishCapture()
                    }
                },

                handler
            )

        } catch (e: Exception) {

            status(
                "Pozlama reddedildi: ${e.message}"
            )

            finishCapture()
        }
    }

    private fun finishCapture() {

        isCapturing = false

        captureButton.isEnabled = true
        stopButton.isEnabled = false

        status(
            "✓ Çekim tamamlandı — $currentFrame kare"
        )
    }

    private fun stopCapture() {

        isCapturing = false

        handler.removeCallbacksAndMessages(null)

        try {
            captureSession?.abortCaptures()
        } catch (_: Exception) {}

        captureButton.isEnabled = true
        stopButton.isEnabled = false

        countdownText.visibility =
            android.view.View.GONE

        status(
            "Çekim durduruldu — $currentFrame kare"
        )
    }

    private fun applyDeepSky() {

        exposureSpinner.setSelection(3)

        isoSpinner.setSelection(4)

        totalMinutes.setText("0")

        customSeconds.setText("30")

        status(
            "🌌 Deep Sky: 30 sn / ISO 1600 / ∞ odak"
        )
    }

    private fun applyMeteor() {

        exposureSpinner.setSelection(3)

        isoSpinner.setSelection(3)

        totalMinutes.setText("60")

        customSeconds.setText("30")

        status(
            "☄️ Meteor: 30 sn / ISO 800 / 60 dk"
        )
    }

    private fun savePhoto(
        bytes: ByteArray
    ) {

        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
            ).format(Date())

        val filename =
            "Meteor_${timestamp}.jpg"

        val values =
            ContentValues().apply {

                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    filename
                )

                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )

                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/MeteorCamera"
                )

                put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
                )
            }

        val uri: Uri? =
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )

        if (uri == null) {

            runOnUiThread {
                status("Fotoğraf kaydedilemedi.")
            }

            return
        }

        try {

            contentResolver
                .openOutputStream(uri)
                ?.use {
                    it.write(bytes)
                }

            val completeValues =
                ContentValues().apply {

                    put(
                        MediaStore.Images.Media.IS_PENDING,
                        0
                    )
                }

            contentResolver.update(
                uri,
                completeValues,
                null,
                null
            )

            runOnUiThread {

                status(
                    "✓ Kare $currentFrame/$targetFrames kaydedildi"
                )
            }

        } catch (e: Exception) {

            contentResolver.delete(
                uri,
                null,
                null
            )

            runOnUiThread {

                status(
                    "Kayıt hatası: ${e.message}"
                )
            }
        }
    }

    private fun formatExposure(
        ns: Long
    ): String {

        val seconds =
            ns / 1_000_000_000.0

        return if (seconds >= 1) {

            String.format(
                Locale.US,
                "%.1f sn",
                seconds
            )

        } else {

            "${ns / 1_000_000} ms"
        }
    }

    private fun status(
        message: String
    ) {

        runOnUiThread {

            statusText.text =
                message
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        try {
            captureSession?.close()
        } catch (_: Exception) {}

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}

        try {
            imageReader?.close()
        } catch (_: Exception) {}

        super.onDestroy()
    }
}
```
