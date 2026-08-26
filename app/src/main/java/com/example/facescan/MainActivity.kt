package com.example.facescan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.room.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

// 1. DATABASE COMPONENT (Offline SQLite Room Layer)
@Entity(tableName = "profiles")
data class UserProfile(
    @PrimaryKey val phone: String,
    val name: String,
    val semester: String,
    val faceMetricX: Float, // Metric representation layout for basic offline matching
    val faceMetricY: Float
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles")
    fun getAllProfiles(): List<UserProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveProfile(profile: UserProfile)
}

@Database(entities = [UserProfile::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}

// 2. MAIN LOGIC CONTROLLER
class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var etName: EditText
    private lateinit var etSemester: EditText
    private lateinit var etPhone: EditText
    private lateinit var tvResults: TextView
    private lateinit var db: AppDatabase
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.cameraPreview)
        etName = findViewById(R.id.etName)
        etSemester = findViewById(R.id.etSemester)
        etPhone = findViewById(R.id.etPhone)
        tvResults = findViewById(R.id.tvResults)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "face_db")
            .allowMainThreadQueries().build()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { processFaceAction(isRegister = true) }
        findViewById<Button>(R.id.btnScan).setOnClickListener { processFaceAction(isRegister = false) }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera setup error", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFaceAction(isRegister: Boolean) {
        val capture = imageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    val options = FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
                    val detector = FaceDetection.getClient(options)

                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val detectedFace = faces[0]
                                // Extract dimensional structural points for lightweight structural metrics tracking
                                val metricX = detectedFace.boundingBox.width().toFloat()
                                val metricY = detectedFace.boundingBox.height().toFloat()

                                runOnUiThread {
                                    if (isRegister) {
                                        val profile = UserProfile(
                                            phone = etPhone.text.toString(),
                                            name = etName.text.toString(),
                                            semester = etSemester.text.toString(),
                                            faceMetricX = metricX,
                                            faceMetricY = metricY
                                        )
                                        db.profileDao().saveProfile(profile)
                                        tvResults.text = "Status: Profile Saved/Updated!"
                                    } else {
                                        val profiles = db.profileDao().getAllProfiles()
                                        var matchedUser: UserProfile? = null
                                        var closestDiff = 50.0f

                                        for (p in profiles) {
                                            val diff = abs(p.faceMetricX - metricX) + abs(p.faceMetricY - metricY)
                                            if (diff < closestDiff) {
                                                closestDiff = diff
                                                matchedUser = p
                                            }
                                        }

                                        if (matchedUser != null) {
                                            tvResults.text = "Match Found:\nName: ${matchedUser.name}\nSem: ${matchedUser.semester}\nPhone: ${matchedUser.phone}"
                                        } else {
                                            tvResults.text = "Status: No Matching Face Found."
                                        }
                                    }
                                }
                            } else {
                                runOnUiThread { tvResults.text = "Status: No Face Detected in View" }
                            }
                            imageProxy.close()
                        }
                        .addOnFailureListener {
                            imageProxy.close()
                        }
                }
            }
        })
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

