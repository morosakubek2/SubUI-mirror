package com.carudibu.android.subuimirror

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.widget.Toast
import android.view.*
import androidx.core.app.NotificationCompat
import java.lang.Exception
import java.lang.reflect.Field
import java.lang.reflect.Method
import android.hardware.SensorManager
import android.hardware.SensorEvent
import android.hardware.Sensor
import android.hardware.SensorEventListener

class MirrorService: Service() {

    private var mProjectionManager: MediaProjectionManager? = null
    private var mScreenSharing = false
    private var mMediaProjection: MediaProjection? = null
    private var mPresentationDialog: Presentation? = null
    private var orientationEventListener: OrientationEventListener? = null
    private var virtualDisplay: Display? = null

    private var sf: Int = 1
    private var currentRotation: Int = Surface.ROTATION_0

    // Debug logging
    private val TAG = "SubUIMirror"
    private var debugMode = true

    // Optymalizacja: mniejsza częstotliwość sensora dla oszczędności baterii
    private val sensorDelay = SensorManager.SENSOR_DELAY_UI

    // Debounce timing
    private val rotationDebounceMs = 300L
    private var lastRotationUpdate = 0L

    // Specyficzne rozdzielczości dla Samsung Galaxy Z Flip 3
    private val flip3CoverWidth = try { BuildConfig.FLIP3_COVER_WIDTH } catch(e: Exception) { 512 }
    private val flip3CoverHeight = try { BuildConfig.FLIP3_COVER_HEIGHT } catch(e: Exception) { 260 }
    private val flip3MainWidth = try { BuildConfig.FLIP3_MAIN_WIDTH } catch(e: Exception) { 1080 }
    private val flip3MainHeight = try { BuildConfig.FLIP3_MAIN_HEIGHT } catch(e: Exception) { 2268 }

    // WakeLock dla utrzymania działania przy zamkniętej klapce
    private var wakeLock: PowerManager.WakeLock? = null

    // Czy auto-rotate jest włączone (domyślnie wyłączone)
    private var autoRotateEnabled: Boolean = false

    // Czy klapka jest zamknięta
    private var isFlipClosed: Boolean = false

    // Target refresh rate dla cover display
    private val targetRefreshRate = 60f

    // BroadcastReceiver dla zmiany stanu klapki
    private var flipStateReceiver: BroadcastReceiver? = null

    // Samsung Good Lock / MultiStar integration
    private var goodLockEnabled: Boolean = false

    // Root mode for advanced power saving
    private var rootModeEnabled: Boolean = false
    private var hasRootAccess: Boolean = false

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        generateForegroundNotification()

        Toast.makeText(this, "Started", Toast.LENGTH_SHORT).show()
        logDebug("Service started")

        val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
        sf = if(sharedPref.getBoolean("crop", false)) 3 else 1
        autoRotateEnabled = sharedPref.getBoolean("auto_rotate", false)
        goodLockEnabled = sharedPref.getBoolean("good_lock", false)
        rootModeEnabled = sharedPref.getBoolean("root_mode", false)

        if (rootModeEnabled) {
            hasRootAccess = checkRootAccess()
            logDebug("Root mode enabled, has root access: $hasRootAccess")
        }

        logDebug("Crop: ${sf > 1}, Auto-rotate: $autoRotateEnabled, Good Lock: $goodLockEnabled, Root: $rootModeEnabled")

        setupWakeLock()
        registerFlipStateReceiver()

        isFlipClosed = checkFlipState()
        logDebug("Initial flip state: ${if(isFlipClosed) "CLOSED" else "OPEN"}")

        if (!isFlipClosed) {
            logDebug("Flip is OPEN - mirroring will start when closed")
            Toast.makeText(this, "Close the flip to start mirroring", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (autoRotateEnabled) {
            orientationEventListener = object : OrientationEventListener(this, sensorDelay) {
                override fun onOrientationChanged(orientation: Int) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastRotationUpdate < rotationDebounceMs) return

                    val windowService = getSystemService(WINDOW_SERVICE) as WindowManager
                    val newRotation = windowService.defaultDisplay.rotation
                    if (newRotation != currentRotation) {
                        logDebug("Rotation: $currentRotation -> $newRotation")
                        currentRotation = newRotation
                        lastRotationUpdate = currentTime
                        updateOrientation()
                    }
                }
            }
            orientationEventListener?.enable()
            logDebug("Orientation listener enabled")
        } else {
            logDebug("Orientation listener disabled (auto-rotate OFF)")
        }

        val subDisplay: Display? = getSubDisplay()
        if (subDisplay == null) {
            Toast.makeText(this, "Cover screen not detected!", Toast.LENGTH_SHORT).show()
            logDebug("ERROR: Cover screen not detected!")
            stopSelf()
            return START_NOT_STICKY
        }

        logDebug("Cover display found: ${subDisplay.displayId}")
        setupMirroring(subDisplay, intent)

        return START_STICKY
    }

    private fun setupMirroring(subDisplay: Display, intent: Intent) {
        if (mPresentationDialog == null) {
            mPresentationDialog = CoverPresentation(this, subDisplay)
        }
        mPresentationDialog!!.window!!.clearFlags(8)
        mPresentationDialog!!.window!!.addFlags(2097152)
        mPresentationDialog!!.window!!.addFlags(128)
        mPresentationDialog!!.window!!.addFlags(67108864)
        mPresentationDialog!!.window!!.addFlags(1024)
        val attributes = mPresentationDialog!!.window!!.attributes
        try {
            val field: Field = attributes.javaClass.getField("layoutInDisplayCutoutMode")
            field.setAccessible(true)
            field.setInt(attributes, 1)
            mPresentationDialog!!.window!!.attributes = attributes
            logDebug("Display cutout mode set")
        } catch (e: Exception) {
            logDebug("Could not set cutout mode: ${e.message}")
        }
        try {
            mPresentationDialog!!.show()
            logDebug("Presentation shown")
        } catch (e: Exception) {
            logDebug("Error showing presentation: ${e.message}")
            e.printStackTrace()
        }

        val surfaceView = mPresentationDialog!!.findViewById<View>(R.id.surface_view) as TextureView
        val surfaceTexture = surfaceView.surfaceTexture
        if (surfaceTexture == null) {
            logDebug("SurfaceTexture is null")
            return
        }

        val displayMetrics = resources.displayMetrics
        val targetWidth = if (sf > 1) 768 else flip3CoverWidth
        val targetHeight = if (sf > 1) 390 else flip3CoverHeight

        logDebug("VirtualDisplay: ${targetWidth}x${targetHeight}, DPI: ${displayMetrics.densityDpi}")

        var vdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                      DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val field: Field = DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_TRUSTED_ONLY")
                vdFlags = vdFlags or field.getInt(null)
                logDebug("TRUSTED_ONLY flag added")
            } catch (e: Exception) {
                logDebug("TRUSTED_ONLY not available")
            }
        }

        val vdHandler = Handler(Looper.getMainLooper())
        val vdCallback = object : VirtualDisplay.Callback() {
            override fun onStopped() { logDebug("VD stopped") }
            override fun onPaused() { logDebug("VD paused") }
            override fun onResumed() { logDebug("VD resumed") }
        }

        virtualDisplay = mMediaProjection!!.createVirtualDisplay(
            "cover",
            targetWidth,
            targetHeight,
            displayMetrics.densityDpi,
            vdFlags,
            Surface(surfaceTexture),
            vdCallback,
            vdHandler
        )
        logDebug("VirtualDisplay created")

        trySetRefreshRate(subDisplay, targetRefreshRate)
    }

    private fun trySetRefreshRate(display: Display, rate: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val method: Method = display.javaClass.getMethod("setRefreshRate", Float::class.javaPrimitiveType)
                method.invoke(display, rate)
                logDebug("Refresh rate set to $rate Hz")
            } catch (e: Exception) {
                logDebug("Could not set refresh rate: ${e.message}")
            }
        }
    }

    private fun getSubDisplay(): Display? {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        logDebug("Available displays: ${displays.size}")
        for (display in displays) {
            logDebug("Display ${display.displayId}: ${display.name}")
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                return display
            }
        }
        return null
    }

    private fun registerFlipStateReceiver() {
        flipStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.intent.action.COVER_STATE_CHANGED") {
                    val state = intent.getIntExtra("state", -1)
                    val closed = (state == 1)
                    logDebug("Flip state: $state (closed=$closed)")
                    
                    if (closed != isFlipClosed) {
                        isFlipClosed = closed
                        if (closed) {
                            logDebug("Flip CLOSED - starting mirroring")
                            // Restart service logic if needed
                        } else {
                            logDebug("Flip OPEN - stopping mirroring")
                            stopSelf()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("android.intent.action.COVER_STATE_CHANGED")
        try {
            registerReceiver(flipStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            logDebug("Flip receiver registered")
        } catch (e: Exception) {
            logDebug("Failed to register flip receiver: ${e.message}")
        }
    }

    private fun checkFlipState(): Boolean {
        // Placeholder - actual implementation depends on Samsung API availability
        // For now assume closed if service started
        return true
    }

    private fun setupWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubUIMirror::WakeLock")
        wakeLock?.acquire(10*60*1000L)
        logDebug("WakeLock acquired")
    }

    private fun checkRootAccess(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo test")).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun updateOrientation() {
        // Update matrix if needed
        logDebug("Orientation updated to $currentRotation")
    }

    private fun generateForegroundNotification() {
        val channelId = "mirror_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Mirror Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SubUI Mirror")
            .setContentText("Running (flip closed)")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun logDebug(msg: String) {
        if (debugMode) Log.d(TAG, msg)
    }

    override fun onDestroy() {
        logDebug("Service destroyed")
        orientationEventListener?.disable()
        flipStateReceiver?.let { unregisterReceiver(it) }
        wakeLock?.release()
        mPresentationDialog?.dismiss()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class CoverPresentation(context: Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cover_presentation)
    }
}
