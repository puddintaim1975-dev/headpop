package dev.zerokool.headpop

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Streams face boxes (upright-frame coordinates + tracking ids) to the game.
 * Every ~200ms also captures an upright camera frame and crops each detected
 * face so the balloon can be textured with the player's actual face.
 */
class FaceAnalyzer(
    private val cb: (List<FaceOverlayView.FaceBox>, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
    )
    private var lastAt = 0L
    private var lastTexAt = 0L
    private val rotateMatrix = Matrix()

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) { imageProxy.close(); return }
        val now = System.currentTimeMillis()
        if (now - lastAt < 33) { imageProxy.close(); return }
        lastAt = now
        val grabTex = now - lastTexAt > 200
        if (grabTex) lastTexAt = now
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(media, rotation)

        // upright frame for face-texture crops (throttled to ~5 Hz)
        var frame: Bitmap? = null
        if (grabTex) {
            try {
                val raw = imageProxy.toBitmap()
                frame = if (rotation == 0) raw else {
                    rotateMatrix.setRotate(rotation.toFloat())
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotateMatrix, true)
                }
            } catch (_: Exception) { frame = null }
        }

        detector.process(image)
            .addOnSuccessListener { faces ->
                val rotated = rotation == 90 || rotation == 270
                val uw = if (rotated) imageProxy.height else imageProxy.width
                val uh = if (rotated) imageProxy.width else imageProxy.height
                val boxes = faces.map { f ->
                    val b = f.boundingBox
                    val id = f.trackingId ?: b.hashCode()
                    val texBmp = frame?.let { cropFace(it, b) }
                    FaceOverlayView.FaceBox(
                        id,
                        b.left.toFloat(), b.top.toFloat(),
                        b.right.toFloat(), b.bottom.toFloat(),
                        texBmp
                    )
                }
                frame?.recycle()  // crops are independent bitmaps; safe
                cb(boxes, uw, uh)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Crop the face box (+5% margin) from the upright frame; null if degenerate. */
    private fun cropFace(src: Bitmap, b: Rect): Bitmap? {
        val m = (b.width() * 0.05f).coerceAtLeast(4f).toInt()
        val l = (b.left - m).coerceAtLeast(0)
        val t = (b.top - m).coerceAtLeast(0)
        val r = (b.right + m).coerceAtMost(src.width)
        val bo = (b.bottom + m).coerceAtMost(src.height)
        val w = r - l
        val h = bo - t
        if (w < 24 || h < 24) return null
        return try {
            Bitmap.createBitmap(src, l, t, w, h)
        } catch (_: Exception) { null }
    }
}