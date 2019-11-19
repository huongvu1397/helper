package com.excalibur.gifmaker.ui.modules.camtogif

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.excalibur.gifmaker.KEY_EVENT_EXTRA
import com.excalibur.gifmaker.R
import com.excalibur.gifmaker.ui.BaseFragment
import com.excalibur.gifmaker.ui.widgets.record.CompareSizesByArea
import com.excalibur.gifmaker.ui.widgets.record.FileType
import com.excalibur.gifmaker.ui.widgets.record.GestureListener
import com.excalibur.gifmaker.ui.widgets.record.SwipeGesture
import com.excalibur.gifmaker.utils.PermissionsUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.fragment_camera_to_gif.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt


/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraToGifFragment : BaseFragment(), SwipeGesture {
    private var broadcastManager: LocalBroadcastManager? = null
    private lateinit var outputDirectory: File
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var cameraDevice: CameraDevice? = null
    private var isRecordingVideo = false
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var gestureDetector: GestureDetector? = null
    private lateinit var cameraManager: CameraManager
    private var captureSession: CameraCaptureSession? = null
    private lateinit var videoSize: Size
    private lateinit var previewSize: Size

    override fun getStatusBarColor(): Int {
        return Color.BLACK
    }

    override fun isLightStatusBarColor(): StatusBarState {
        return StatusBarState.Dark
    }

    companion object {
        const val MAX_DURATION_IN_MILLISECONDS: Long = 30 * 1000
        const val EXTRA_PERMISSION_MISSING = "permissionMissing"
        private const val REQUEST_GALLERY_PICK = 1
        private val bg_tag = "camera_background_thread"

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(
                baseFolder, SimpleDateFormat(format, Locale.getDefault())
                    .format(System.currentTimeMillis()) + extension
            )
    }

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraToGifFragment.cameraDevice = cameraDevice
            startPreview()
            this@CameraToGifFragment.activity?.configureTransform(
                previewSize,
                textureView.width,
                textureView.height
            )
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraToGifFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraToGifFragment.cameraDevice = null
            this@CameraToGifFragment.activity?.finish()
        }
    }

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            this@CameraToGifFragment.activity?.configureTransform(previewSize, width, height)
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    }

    private val countDownTimer = object : CountDownTimer(MAX_DURATION_IN_MILLISECONDS, 1000) {
        override fun onTick(millisUntilFinished: Long) {
            Log.e("HVV1312", " AMX : $MAX_DURATION_IN_MILLISECONDS va $millisUntilFinished")
            val elapseTime: Int =
                ((MAX_DURATION_IN_MILLISECONDS - millisUntilFinished) / 1000).toInt() + 1
            Log.e("HVV1312", "elapseTime : $elapseTime")
        }

        override fun onFinish() {
            Log.e("HVV1312", "onFinish")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = this.activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camera_to_gif, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = this.activity ?: return
        setImmersiveMode()
        val isFlashSupported =
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

        gestureDetector = GestureDetector(activity, GestureListener(this))

        img_btn_flash.setOnClickListener {
            if (isFlashSupported) {
                toggleCameraFlash()
            }
        }

        img_cancel_camera.setOnClickListener {
            findNavController().navigate(R.id.action_cameraToGifFragment_to_cutVideoFragment)
        }

        img_btn_change_camera.setOnClickListener {
            if (PermissionsUtils.isPermissionGranted(
                    activity,
                    PermissionsUtils.Permission.CAMERA
                ) && !isRecordingVideo
            ) {
                switchCameraOutputs()
            }
        }
        btn_capture_video.setOnClickListener {
            Log.e("HVV1312", "toggleRecordVideo")
            toggleRecordVideo()
        }
    }

    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    Log.e("HVV1312", "KEYCODE_VOLUME_DOWN")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        broadcastManager?.unregisterReceiver(volumeDownReceiver)
        removeStatusbarFlags()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (this.textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            this.textureView.surfaceTextureListener = surfaceTextureListener
            this.textureView.setOnTouchListener { _, event ->
                if (event.pointerCount == 2) {
                    pinchToZoom(event)
                }
                captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
                true
            }
        }
    }

    override fun onPause() {
        Log.e("HVV1312", "onPause")
        closeCamera()
        stopRecordingVideo()
        stopBackgroundThread()
        super.onPause()
    }

    //region BackgroundThread
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread(bg_tag)
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        if (backgroundHandler != null) {
            backgroundThread?.quitSafely()
            try {
                backgroundThread?.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }


    private fun showTransparentStatusbar() {
        activity!!.window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
    }

    private fun removeStatusbarFlags() {
        activity!!.window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }


    @SuppressLint("MissingPermission")
    @Throws(RuntimeException::class)
    private fun openCamera(width: Int, height: Int) {
        val activity = this.activity
        if (activity == null || activity.isFinishing) return

        if (!activity.hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_PERMISSION_MISSING, true)
            activity.setResult(Activity.RESULT_CANCELED, resultIntent)
            activity.finish()
            return
        }

        if (!this.textureView.isAvailable) {
            return
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Snackbar.make(
                    this.root,
                    getString(R.string.message_record_capture_timeout_exception_error),
                    Snackbar.LENGTH_SHORT
                ).show()
                return
            }

            val cameraId = cameraDirection
            val characteristics = cameraManager.getCameraCharacteristics(cameraDirection)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            maximumZoomLevel =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0F

            if (map == null) {
                Snackbar.make(
                    this.root,
                    getString(R.string.message_record_capture_preview_sizes_exception_error),
                    Snackbar.LENGTH_SHORT
                ).show()
                return
            }

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width,
                height,
                videoSize
            )
            Log.e("HVV1312","video size : $videoSize  previewsize  : $previewSize")

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }

            activity.configureTransform(
                previewSize,
                this.textureView.width,
                this.textureView.height
            )
            mediaRecorder = MediaRecorder()
            cameraManager.openCamera(cameraId, this.stateCallback, null)

        } catch (e: NullPointerException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_camera_api_not_supported_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: InterruptedException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_interrupted_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: CameraAccessException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_unable_access_camera_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
            activity.finish()
        }

    }

    @Throws(RuntimeException::class)
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: InterruptedException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_interrupted_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        } finally {
            cameraOpenCloseLock.release()
        }
    }


    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    //region Camera Zoom
    private fun pinchToZoom(event: MotionEvent) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraDirection)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val currentFingerSpacing: Float = event.calculateFingerSpacing()

        if (fingerSpacing != 0F) {
            var zoomSensitivity = 0.05F

            // Prevent Over Zoom-In
            if (currentFingerSpacing > fingerSpacing) {
                if ((maximumZoomLevel - zoomLevel) <= zoomSensitivity) {
                    zoomSensitivity = maximumZoomLevel - zoomLevel
                }
                zoomLevel += zoomSensitivity

            } else {
                // Prevent Over Zoom-Out
                if (currentFingerSpacing < fingerSpacing) {
                    if ((zoomLevel - zoomSensitivity) < 1f) {
                        zoomSensitivity = zoomLevel - 1f
                    }
                    zoomLevel -= zoomSensitivity
                }
            }

            val ratio = 1 / zoomLevel
            val croppedWidth = (sensorRect?.width() ?: 0) - ((sensorRect?.width()
                ?: 0) * ratio).roundToInt()
            val croppedHeight = (sensorRect?.height() ?: 0) - ((sensorRect?.height()
                ?: 0) * ratio).roundToInt()

            zoomRect = Rect(
                croppedWidth / 2,
                croppedHeight / 2,
                (sensorRect?.width() ?: 0) - croppedWidth / 2,
                (sensorRect?.height() ?: 0) - croppedHeight / 2
            )
            previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
        }
        fingerSpacing = currentFingerSpacing
    }

    fun MotionEvent.calculateFingerSpacing(): Float {
        val x = getX(0) - getX(1)
        val y = getY(0) - getY(1)

        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun startPreview() {
        if (cameraDevice == null || !this.textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = this.textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Snackbar.make(
                            this@CameraToGifFragment.root,
                            getString(R.string.message_record_capture_session_exception_error),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_camera_access_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun updatePreview() {

        if (cameraDevice == null) return
        try {
            HandlerThread(bg_tag).start()
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_camera_access_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }


    private fun toggleCameraFlash() {
        if (cameraDirection == CAMERA_BACK) {
            if (isFlashOn) {
                // this.flashButton.setImageResource(R.drawable.ic_flash_on)
                previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            } else {
                // this.flashButton.setImageResource(R.drawable.ic_flash_off)
                previewRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    CameraMetadata.FLASH_MODE_TORCH
                )
            }

            isFlashOn = !isFlashOn
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
        }
    }

    @SuppressLint("MissingPermission")
    @Throws(Exception::class)
    private fun switchCameraOutputs() {
        when (cameraDirection) {
            CAMERA_BACK -> {
                if (isFlashOn) {
                    isFlashOn = !isFlashOn
                    previewRequestBuilder.set(
                        CaptureRequest.FLASH_MODE,
                        CameraMetadata.FLASH_MODE_OFF
                    )
                    captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
                }
                cameraDirection = CAMERA_FRONT
            }
            CAMERA_FRONT -> {
                cameraDirection = CAMERA_BACK
            }
        }

        cameraOpenCloseLock.release()
        cameraDevice?.close()

        if (cameraManager.cameraIdList.contains(cameraDirection)) {
            cameraManager.openCamera(cameraDirection, this.stateCallback, null)
            val characteristics = cameraManager.getCameraCharacteristics(cameraDirection)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }
    }

    private fun panToZoom(event: MotionEvent) {
        val characteristics = cameraManager.getCameraCharacteristics(cameraDirection)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                anchorPosition = event.rawY
                maximumRawY = maximumZoomLevel * anchorPosition

                if (!isRecordingVideo) {
                    toggleRecordVideo()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isRecordingVideo) {
                    toggleRecordVideo()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                zoomLevel = maximumZoomLevel - (event.rawY / anchorPosition) * maximumZoomLevel

                val ratio = 1 / zoomLevel
                val croppedWidth = (sensorRect?.width() ?: 0) - ((sensorRect?.width()
                    ?: 0) * ratio).roundToInt()
                val croppedHeight = (sensorRect?.height() ?: 0) - ((sensorRect?.height()
                    ?: 0) * ratio).roundToInt()
                zoomRect = Rect(
                    croppedWidth / 2,
                    croppedHeight / 2,
                    (sensorRect?.width() ?: 0) - croppedWidth / 2,
                    (sensorRect?.height() ?: 0) - croppedHeight / 2
                )
                previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
            }
        }
    }

    override fun onTap() {
        //toggleRecordVideo()
    }

    //region Record Controls
    private fun toggleRecordVideo() {
        //this.btn_capture_video.performHapticContextClick()
        if (isRecordingVideo) {
            stopRecordingVideo()
            //this.progressBar.visibility = View.GONE
        } else {
            //this.progressBar.visibility = View.VISIBLE
            //this.recordButton.isEnabled = false
            startRecordingVideo()
        }
    }

    private var isHighQuality = true
    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val cameraActivity = this.activity ?: return

        if (videoAbsolutePath.isNullOrEmpty()) {
            videoAbsolutePath = this.context?.getVideoFilePath(FileType.MP4)
        }

        val rotation = cameraActivity.windowManager.defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES -> mediaRecorder?.setOrientationHint(
                DEFAULT_ORIENTATIONS.get(rotation)
            )
            SENSOR_ORIENTATION_INVERSE_DEGREES -> mediaRecorder?.setOrientationHint(
                INVERSE_ORIENTATIONS.get(rotation)
            )
        }
        setupHightQuality(isHighQuality)
    }

    private fun setupHightQuality(isHigh: Boolean) {
        val cameraProfile =
            CamcorderProfile.get(cameraDirection.toInt(), CamcorderProfile.QUALITY_HIGH)
        if (isHigh) {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setProfile(cameraProfile)
                setOutputFile(videoAbsolutePath)
                setMaxDuration(MAX_DURATION_IN_MILLISECONDS.toInt())
                setOnInfoListener { _, info, _ ->
                    if (info == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecordingVideo()
                    }
                }
                setVideoSize(videoSize.width, videoSize.height)
                prepare()
            }
        } else {
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoAbsolutePath)
                setVideoEncodingBitRate(cameraProfile.videoBitRate)
                setVideoFrameRate(cameraProfile.videoFrameRate)
                setVideoSize(videoSize.width, videoSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setMaxDuration(60000)
                setOnInfoListener(infoListener)
                prepare()
            }
        }
    }

    private val infoListener = MediaRecorder.OnInfoListener { _, what, _ ->
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
            Log.e("HVV1312", "okok media max ")
            stopRecordingVideo()
        }
    }

    private fun startRecordingVideo() {
        Log.e("HVV1312", "startRecordingVideo")
        if (cameraDevice == null || !this.textureView.isAvailable) return

        try {
            closePreviewSession()
            setUpMediaRecorder()

            val texture = this.textureView.surfaceTexture.apply {
                setDefaultBufferSize(
                    previewSize.width,
                    previewSize.height
                )
            }

            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder?.surface ?: return
            val surfaces = arrayListOf(previewSurface, recorderSurface)

            val cameraDevice = cameraDevice ?: return
            previewRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(recorderSurface)
                }

            cameraDevice.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        with(this@CameraToGifFragment) {
                            captureSession = cameraCaptureSession
                            updatePreview()
                            activity?.runOnUiThread {
                                val mediaRecorder = mediaRecorder ?: return@runOnUiThread

                                mediaRecorder.start()
                                countDownTimer.start()

                                isRecordingVideo = true
                                //recordButton.isEnabled = true
                                //recordButton.setImageResource(R.drawable.ic_camera_stop)
                            }
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Snackbar.make(
                            this@CameraToGifFragment.root,
                            getString(R.string.message_record_capture_session_exception_error),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                },
                backgroundHandler
            )

        } catch (e: CameraAccessException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_camera_access_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopRecordingVideo() {
        Log.e("HVV1312", "stopRecordingVideo")
        try {
            isRecordingVideo = false
            this.countDownTimer.cancel()
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
            mediaRecorder?.apply {
                stop()
                reset()
            }
//            if (videoAbsolutePath != "") {
//                preparePreviewFragment(videoAbsolutePath.toString())
//            }
            if (activity != null) showToast("Video saved: ${videoAbsolutePath.toString()}")
            Log.e("HVV1312", "path : ${videoAbsolutePath.toString()}")
            videoAbsolutePath = null
            startPreview()
        } catch (e: RuntimeException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_runtime_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
            this.activity?.finish()
        } catch (e: CameraAccessException) {
            Snackbar.make(
                this.root,
                getString(R.string.message_record_capture_camera_access_exception_error),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // PERMISSION

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private fun shouldShowRequestPermissionRationale(permissions: Array<String>) =
        permissions.any { shouldShowRequestPermissionRationale(it) }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        Log.e("HVV1312", "Need permission for camera")
                        break
                    }
                }
            } else {
                Log.e("HVV1312", "Need permission for camera")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun hasPermissionsGranted(permissions: Array<String>) =
        permissions.none {
            checkSelfPermission(
                (activity as FragmentActivity),
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            //ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS)
        }
    }

    private fun showToast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
//    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
//        it.width == it.height * 4 / 3 && it.width <= 1080
//    } ?: choices[choices.size - 1]

    private fun chooseVideoSize(choices: Array<Size>): Size {
        for (size in choices){
            Log.e("HVV1312","return size 1 ${size.width}    ${size.height} :/ ")

            if(1920 == size.width && 1080 == size.height){
                Log.e("HVV1312","return ")
                return size
            }
        }
        for(size in choices){
            Log.e("HVV1312","return size 2 ${size.width}    ${size.height} :/ ")

            if(size.width == size.height *4/3 && size.width <= 1080){

                return size
            }
        }
        return choices[choices.size - 1]
    }

    /**
     * Given [choices] of [Size]s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal [Size], or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int,
        aspectRatio: Size
    ): Size {
        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun setImmersiveMode() {
        // body is root view in my layout
        root?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
}
