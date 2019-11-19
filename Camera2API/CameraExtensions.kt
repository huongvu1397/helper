package com.excalibur.gifmaker.ui.modules.camtogif

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.excalibur.gifmaker.R
import com.excalibur.gifmaker.ui.widgets.record.FileType
import kotlinx.android.synthetic.main.fragment_camera_to_gif.*
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.math.max

//region Constant Variables
const val CAMERA_FRONT = "1"

const val REQUEST_VIDEO_PERMISSIONS = 1
val VIDEO_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

const val CAMERA_BACK = "0"

var cameraBackId = CameraCharacteristics.LENS_FACING_BACK
var cameraFontId = CameraCharacteristics.LENS_FACING_FRONT


const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}

val INVERSE_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 270)
    append(Surface.ROTATION_90, 180)
    append(Surface.ROTATION_180, 90)
    append(Surface.ROTATION_270, 0)
}
//endreigon

//region Variables
val cameraOpenCloseLock = Semaphore(1)

var zoomLevel = 1F
var maximumRawY = 0F
var fingerSpacing = 0F
var anchorPosition = 0F
var maximumZoomLevel = 0F
var zoomRect: Rect? = null

var captureSession: CameraCaptureSession? = null

var isFlashOn = false

var videoAbsolutePath: String? = null


var mediaRecorder: MediaRecorder? = null

var sensorOrientation = 0
var cameraDirection = CAMERA_BACK

//region Activity Extension
fun Activity.hasPermissionsGranted(permissions: Array<String>) =
    permissions.none {
        ContextCompat.checkSelfPermission((this as FragmentActivity), it) != PackageManager.PERMISSION_GRANTED
    }

fun Activity.configureTransform(previewSize: Size, viewWidth: Int, viewHeight: Int) {
    val rotation = (this as FragmentActivity).windowManager.defaultDisplay.rotation
    val matrix = Matrix()

    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = Math.max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width
        )
        with(matrix) {
            postScale(scale, scale, centerX, centerY)
            postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
    }
    this.textureView.setTransform(matrix)
}

/**
 * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
 * This method should not to be called until the camera preview size is determined in
 * openCamera, or until the size of `textureView` is fixed.
 *
 * @param viewWidth  The width of `textureView`
 * @param viewHeight The height of `textureView`
 */
 fun Activity.configureTransform2(previewSize: Size,viewWidth: Int, viewHeight: Int) {
    val rotation = (this as FragmentActivity).windowManager.defaultDisplay.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / previewSize.height,
            viewWidth.toFloat() / previewSize.width)
        with(matrix) {
            postScale(scale, scale, centerX, centerY)
            postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
    }
    this.textureView.setTransform(matrix)
}
//endregion

//region Context Extension
fun Context.getVideoFilePath(fileType: FileType): String {
    //val dir = this.filesDir
    val dir = Environment.getExternalStorageDirectory()
    val folder = this.getString(R.string.app_name)
    val filename = "${UUID.randomUUID()}.${fileType.name.toLowerCase()}"
    val file = File("$dir/$folder")

    if (!file.exists()) {
        file.mkdir()
    }

    return if (dir == null) {
        filename
    } else {
        "$dir/$folder/$filename"
    }
}

fun Context.getFilePathFromVideoURI(contentUri: Uri): String {
    val filePath = arrayOf(MediaStore.Files.FileColumns.DATA)
    val cursor = this.contentResolver.query(contentUri, filePath, null, null, null) ?: throw Exception("Cursor is null")
    cursor.moveToFirst()

    val videoPath = cursor.getString(cursor.getColumnIndex(filePath[0]))
    cursor.close()

    return videoPath
}
//endregion

//region Video Size
fun chooseVideoSize(choices: Array<Size>) =
    choices.first { it.width <= 1080 }
//endregion