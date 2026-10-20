package dev.zerokool.headpop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlayView
    private lateinit var scoreText: TextView
    private lateinit var bestText: TextView
    private lateinit var flipBtn: Button

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        previewView = PreviewView(this)
        overlay = FaceOverlayView(this)
        overlay.gameEvents = object : FaceOverlayView.GameEvents {
            override fun onScore(score: Int, combo: Int, best: Int) {
                scoreText.text = if (combo > 1) "$score  x$combo" else "$score"
                bestText.text = "best $best"
            }
        }

        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scoreText = TextView(this).apply {
            text = "0"
            textSize = 34f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt())
            gravity = Gravity.CENTER
        }
        bestText = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(0xEEEEEEEE.toInt())
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            gravity = Gravity.CENTER
        }
        hud.addView(scoreText)
        hud.addView(bestText)

        flipBtn = Button(this).apply {
            text = "\uD83D\uDD04"
            alpha = 0.65f
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startCamera()
            }
        }

        root.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(flipBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PermissionChecker.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUse = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, FaceAnalyzer { boxes, uw, uh ->
                runOnUiThread {
                    overlay.mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT
                    overlay.updateFaces(boxes, uw, uh)
                }
            })
            provider.unbindAll()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.bindToLifecycle(this, selector, previewUse, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}