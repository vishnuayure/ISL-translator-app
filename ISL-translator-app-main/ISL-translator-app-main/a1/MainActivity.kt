@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.a1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.a1.ui.theme.ISLTranslatorTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var handDetector: HandDetector
    private var ttsReady = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { setAppContent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GestureStorage.init(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        handDetector = HandDetector(this)

        lifecycleScope.launch {
            println("🔄 Initializing hand detector...")
            handDetector.initialize()
            println(if (handDetector.isInitialized) "✅ Hand detector ready!" else "❌ Failed")
        }

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = textToSpeech.setLanguage(Locale.US) == TextToSpeech.SUCCESS
            }
        }
        requestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textToSpeech.shutdown()
        handDetector.close()
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun setAppContent() {
        setContent {
            ISLTranslatorTheme {
                ISLTranslatorApp(
                    permissionsGranted = checkPermissions(),
                    cameraExecutor = cameraExecutor,
                    handDetector = handDetector,
                    onSpeak = { text ->
                        if (ttsReady && text.isNotEmpty()) {
                            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                )
            }
        }
    }
}

enum class AppMode { SPLASH, RECOGNIZE, TRAIN, MANAGE }

@Composable
fun ISLTranslatorApp(
    permissionsGranted: Boolean,
    cameraExecutor: ExecutorService,
    handDetector: HandDetector,
    onSpeak: (String) -> Unit
) {
    var appMode by remember { mutableStateOf(AppMode.SPLASH) }

    LaunchedEffect(Unit) {
        delay(2500)
        appMode = AppMode.RECOGNIZE
    }

    when (appMode) {
        AppMode.SPLASH -> SplashScreen()
        AppMode.RECOGNIZE -> RecognizeScreen(
            permissionsGranted, cameraExecutor, handDetector, onSpeak
        ) { appMode = it }
        AppMode.TRAIN -> TrainGestureScreen(
            permissionsGranted, cameraExecutor, handDetector
        ) { appMode = AppMode.RECOGNIZE }
        AppMode.MANAGE -> ManageGesturesScreen { appMode = AppMode.RECOGNIZE }
    }
}

@Composable
fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🤟", fontSize = 80.sp)
            Spacer(Modifier.height(16.dp))
            Text("ISL Translator", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Custom Gesture Learning", fontSize = 16.sp, color = Color.White.copy(0.8f))
        }
    }
}

@Composable
fun RecognizeScreen(
    permissionsGranted: Boolean,
    cameraExecutor: ExecutorService,
    handDetector: HandDetector,
    onSpeak: (String) -> Unit,
    onNavigate: (AppMode) -> Unit
) {
    var recognizedText by remember { mutableStateOf("") }
    var confidence by remember { mutableFloatStateOf(0f) }
    var isRecognizing by remember { mutableStateOf(false) }
    var currentLandmarks by remember { mutableStateOf<HandLandmarks?>(null) }
    var detectionStatus by remember { mutableStateOf("Ready") }
    var isModelReady by remember { mutableStateOf(handDetector.isInitialized) }

    LaunchedEffect(Unit) {
        while (!isModelReady) {
            delay(500)
            isModelReady = handDetector.isInitialized
        }
    }

    LaunchedEffect(currentLandmarks, isRecognizing) {
        if (isRecognizing && currentLandmarks != null) {
            val hasHands = currentLandmarks!!.leftHand != null || currentLandmarks!!.rightHand != null
            if (hasHands) {
                val result = GestureStorage.recognizeGesture(currentLandmarks!!)
                if (result != null) {
                    recognizedText = result.first
                    confidence = result.second
                    detectionStatus = "✅ ${result.first}"
                } else {
                    detectionStatus = "👋 No match"
                }
            } else {
                detectionStatus = "🔍 Looking..."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recognize Gestures", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF6366F1),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { onNavigate(AppMode.MANAGE) }) {
                        Icon(Icons.AutoMirrored.Filled.List, "Manage", tint = Color.White)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigate(AppMode.TRAIN) },
                containerColor = Color(0xFF10B981)
            ) {
                Icon(Icons.Default.Add, "Train")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (permissionsGranted) {
                    CameraPreview(
                        Modifier.fillMaxSize(),
                        cameraExecutor,
                        if (isRecognizing && isModelReady) handDetector else null
                    ) { currentLandmarks = it }

                    if (isRecognizing && isModelReady) {
                        Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Red.copy(0.9f)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                                    Spacer(Modifier.width(6.dp))
                                    Text("REC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Box(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Black.copy(0.7f)
                            ) {
                                Text(
                                    detectionStatus,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                } else {
                    PermissionDeniedView()
                }
            }

            ResultPanel(recognizedText, confidence, isRecognizing, {
                isRecognizing = !isRecognizing
                if (!isRecognizing) {
                    recognizedText = ""
                    confidence = 0f
                }
            }, onSpeak)
        }
    }
}

@Composable
fun ResultPanel(
    recognizedText: String,
    confidence: Float,
    isRecognizing: Boolean,
    onToggleRecognition: () -> Unit,
    onSpeak: (String) -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().height(280.dp),
        color = Color(0xFFF8F9FA),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Translation", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
            Spacer(Modifier.height(12.dp))

            Surface(
                Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                shadowElevation = 2.dp
            ) {
                Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                    if (recognizedText.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                recognizedText,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6366F1),
                                textAlign = TextAlign.Center
                            )
                            if (confidence > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text("${confidence.toInt()}% match", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    } else {
                        Text(
                            "Press Start to recognize gestures",
                            fontSize = 16.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onToggleRecognition,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecognizing) Color(0xFFEF4444) else Color(0xFF6366F1)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        if (isRecognizing) Icons.Default.Close else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRecognizing) "Stop" else "Start",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = { onSpeak(recognizedText) },
                    enabled = recognizedText.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speak", Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Speak", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun TrainGestureScreen(
    permissionsGranted: Boolean,
    cameraExecutor: ExecutorService,
    handDetector: HandDetector,
    onBack: () -> Unit
) {
    var gestureName by remember { mutableStateOf("") }
    var isTraining by remember { mutableStateOf(false) }
    var samplesCollected by remember { mutableIntStateOf(0) }
    val samplesNeeded = 5
    val samples = remember { mutableListOf<HandLandmarks>() }
    var lastCaptureTime by remember { mutableLongStateOf(0L) }
    val captureDelay = 1000L
    var handsDetected by remember { mutableStateOf(false) }
    var detectionStatus by remember { mutableStateOf("") }
    var isModelReady by remember { mutableStateOf(handDetector.isInitialized) }

    LaunchedEffect(Unit) {
        while (!isModelReady) {
            delay(500)
            isModelReady = handDetector.isInitialized
        }
    }

    LaunchedEffect(samplesCollected) {
        if (samplesCollected >= samplesNeeded && isTraining) {
            GestureStorage.saveGesture(GesturePattern(gestureName, samples.toList()))
            delay(1000)
            isTraining = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Train Gesture", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF6366F1),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (permissionsGranted) {
                    CameraPreview(
                        Modifier.fillMaxSize(),
                        cameraExecutor,
                        if (isTraining && isModelReady) handDetector else null
                    ) { landmarks ->
                        val hasHands = landmarks.leftHand != null || landmarks.rightHand != null
                        handsDetected = hasHands

                        detectionStatus = when {
                            landmarks.leftHand != null && landmarks.rightHand != null -> "Both hands ✋✋"
                            landmarks.leftHand != null -> "Left hand ✋"
                            landmarks.rightHand != null -> "Right hand ✋"
                            else -> "No hands"
                        }

                        if (isTraining && samplesCollected < samplesNeeded && hasHands) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastCaptureTime >= captureDelay) {
                                samples.add(landmarks)
                                samplesCollected++
                                lastCaptureTime = currentTime
                            }
                        }
                    }

                    if (!isModelReady) {
                        Box(Modifier.padding(16.dp).align(Alignment.TopCenter)) {
                            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFFFA500).copy(0.9f)) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Loading...", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    if (isTraining && isModelReady) {
                        Box(Modifier.padding(16.dp).align(Alignment.TopStart)) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (handsDetected) Color(0xFF10B981).copy(0.9f) else Color(0xFFEF4444).copy(0.9f)
                            ) {
                                Text(
                                    detectionStatus,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    TrainingOverlay(isTraining, samplesCollected, samplesNeeded, Modifier.align(Alignment.Center))
                } else {
                    PermissionDeniedView()
                }
            }

            TrainingPanel(
                gestureName,
                { gestureName = it },
                isTraining,
                samplesCollected,
                samplesNeeded,
                isModelReady,
                {
                    if (gestureName.isNotBlank() && isModelReady) {
                        isTraining = true
                        samplesCollected = 0
                        samples.clear()
                        lastCaptureTime = System.currentTimeMillis()
                    }
                },
                onBack
            )
        }
    }
}

@Composable
fun TrainingOverlay(
    isTraining: Boolean,
    samplesCollected: Int,
    samplesNeeded: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isTraining,
        modifier = modifier,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(0.7f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Hold gesture steady", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(samplesNeeded) { index ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(
                                if (index < samplesCollected) Color(0xFF10B981) else Color.White.copy(0.3f)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("$samplesCollected / $samplesNeeded", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun TrainingPanel(
    gestureName: String,
    onGestureNameChange: (String) -> Unit,
    isTraining: Boolean,
    samplesCollected: Int,
    samplesNeeded: Int,
    isModelReady: Boolean,
    onStartTraining: () -> Unit,
    onComplete: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Color(0xFFF8F9FA),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(20.dp)) {
            if (!isModelReady) {
                Text(
                    "⏳ Loading model...",
                    fontSize = 14.sp,
                    color = Color(0xFFFFA500),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            } else if (isTraining && samplesCollected == 0) {
                Text(
                    "💡 Show your gesture clearly",
                    fontSize = 14.sp,
                    color = Color(0xFF6366F1),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            OutlinedTextField(
                value = gestureName,
                onValueChange = onGestureNameChange,
                label = { Text("Gesture Name") },
                placeholder = { Text("e.g., Hello, Peace") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTraining,
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            if (samplesCollected >= samplesNeeded) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(8.dp))
                    Text("✅ Saved!", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onStartTraining,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = gestureName.isNotBlank() && !isTraining && isModelReady,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !isModelReady -> "Loading..."
                            isTraining -> "Recording $samplesCollected/$samplesNeeded"
                            else -> "Start Training"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun ManageGesturesScreen(onBack: () -> Unit) {
    var gesturesList by remember { mutableStateOf(GestureStorage.getAllGestures()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Gestures", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF6366F1),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (gesturesList.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👋", fontSize = 64.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("No gestures yet", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Train your first gesture!",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gesturesList.forEach { gesture ->
                    GestureCard(gesture) {
                        GestureStorage.deleteGesture(gesture.name)
                        gesturesList = GestureStorage.getAllGestures()
                    }
                }
            }
        }
    }
}

@Composable
fun GestureCard(gesture: GesturePattern, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(gesture.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${gesture.samples.size} samples",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFEF4444))
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraExecutor: ExecutorService,
    handDetector: HandDetector? = null,
    onHandsDetected: ((HandLandmarks) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()

                if (handDetector != null && onHandsDetected != null) {
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val landmarks = handDetector.detectHands(imageProxy)
                        if (landmarks != null) {
                            onHandsDetected(landmarks)
                        }
                        imageProxy.close()
                    }

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProviderFuture.get().unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
fun PermissionDeniedView() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFFF8F9FA)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("📷", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Camera Permission Required",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Grant camera permission in Settings",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}