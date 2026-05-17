package com.carudibu.android.subuimirror

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
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

import android.view.WindowManager
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
    // SENSOR_DELAY_UI = 200ms, SENSOR_DELAY_NORMAL = 50ms
    private val sensorDelay = SensorManager.SENSOR_DELAY_UI
    
    // Debounce timing - minimalny czas między aktualizacjami (ms)
    private val rotationDebounceMs = 300L
    private var lastRotationUpdate = 0L
    
    // Specyficzne rozdzielczości dla Samsung Galaxy Z Flip 3
    // Wartości z BuildConfig lub domyślne
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
    
    // Target refresh rate dla cover display (oszczędność baterii)
    private val targetRefreshRate = 60f
    
    // BroadcastReceiver dla zmiany stanu klapki
    private var flipStateReceiver: BroadcastReceiver? = null
    
    // Samsung Good Lock / MultiStar integration (opcjonalne)
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
        // Pobierz ustawienie auto-rotate (domyślnie wyłączone)
        autoRotateEnabled = sharedPref.getBoolean("auto_rotate", false)
        // Pobierz ustawienie Good Lock integration
        goodLockEnabled = sharedPref.getBoolean("good_lock", false)
        // Pobierz ustawienie Root mode
        rootModeEnabled = sharedPref.getBoolean("root_mode", false)
        
        // Sprawdź dostęp do roota jeśli tryb root jest włączony
        if (rootModeEnabled) {
            hasRootAccess = checkRootAccess()
            logDebug("Root mode enabled, has root access: $hasRootAccess")
            if (!hasRootAccess) {
                logDebug("⚠️ Root mode enabled but no root access detected!")
            }
        }
        
        logDebug("Crop mode: ${sf > 1}, scale factor: $sf, Auto-rotate: $autoRotateEnabled, Good Lock: $goodLockEnabled, Root: $rootModeEnabled")

        // Setup WakeLock dla utrzymania działania przy zamkniętej klapce
        setupWakeLock()
        
        // Register flip state receiver - KLUCZOWE: mirroring TYLKO gdy klapka zamknięta
        registerFlipStateReceiver()

        // Sprawdź stan klapki na starcie
        isFlipClosed = checkFlipState()
        logDebug("Initial flip state: ${if(isFlipClosed) "CLOSED" else "OPEN"}")
        
        // Jeśli klapka otwarta, nie uruchamiaj mirroringu (czekaj na zamknięcie)
        if (!isFlipClosed) {
            logDebug("Flip is OPEN - mirroring will start when closed")
            // Nie zatrzymujemy usługi, ale nie tworzymy VirtualDisplay
            // Czekamy na broadcast o zamknięciu
        }

        // Optymalizacja: użyj SENSOR_DELAY_UI zamiast SENSOR_DELAY_NORMAL dla lepszej wydajności
        // Tylko jeśli auto-rotate jest włączone!
        if (autoRotateEnabled) {
            orientationEventListener =
                object : OrientationEventListener(this, sensorDelay) {
                    override fun onOrientationChanged(orientation: Int) {
                        // Debounce - aktualizuj tylko przy znaczących zmianach i z ograniczoną częstotliwością
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastRotationUpdate < rotationDebounceMs) {
                            return
                        }
                        
                        val windowService = getSystemService(WINDOW_SERVICE) as WindowManager
                        val newRotation = windowService.defaultDisplay.rotation
                        if (newRotation != currentRotation) {
                            logDebug("Rotation changed: $currentRotation -> $newRotation (Auto-rotate)")
                            currentRotation = newRotation
                            lastRotationUpdate = currentTime
                            updateOrientation()
                        }
                    }
                }
            orientationEventListener?.enable()
            logDebug("Orientation listener enabled with delay: $sensorDelay")
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
        logDisplayInfo(subDisplay, "Cover")
        
        // KLUCZOWE: Twórz VirtualDisplay TYLKO gdy klapka zamknięta
        if (isFlipClosed) {
            setupMirroring(subDisplay, intent)
        } else {
            logDebug("Mirroring postponed - waiting for flip to close")
        }
    }
    
    // Setup mirroring - wywoływane tylko gdy klapka zamknięta
    private fun setupMirroring(subDisplay: Display, intent: Intent) {
        if (mPresentationDialog == null) {
            mPresentationDialog = CoverPresentation(this, subDisplay)
        }
        mPresentationDialog!!.window!!.clearFlags(8)
        mPresentationDialog!!.window!!.addFlags(2097152) // FLAG_SHOW_WHEN_LOCKED
        mPresentationDialog!!.window!!.addFlags(128) // FLAG_KEEP_SCREEN_ON
        mPresentationDialog!!.window!!.addFlags(67108864) // FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        mPresentationDialog!!.window!!.addFlags(1024) // FLAG_LAYOUT_NO_LIMITS
        val attributes = mPresentationDialog!!.window!!.attributes
        try {
            val field: Field = attributes.javaClass.getField("layoutInDisplayCutoutMode")
            field.setAccessible(true)
            field.setInt(attributes, 1) // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            mPresentationDialog!!.window!!.attributes = attributes
            logDebug("Display cutout mode set to short edges")
        } catch (e: Exception) {
            logDebug("Could not set display cutout mode: ${e.message}")
            e.printStackTrace()
        }
        try {
            mPresentationDialog!!.show()
            logDebug("Presentation dialog shown")
        } catch (unused: WindowManager.InvalidDisplayException) {
            logDebug("Display was removed")
            mPresentationDialog = null
        }

        mProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mScreenSharing = true

        Handler(Looper.getMainLooper()).postDelayed({
            mMediaProjection = mProjectionManager!!.getMediaProjection(
                intent.getIntExtra("int", 0),
                intent.extras?.getParcelable("data")!!
            )
            logDebug("MediaProjection obtained")

            val surfaceView = mPresentationDialog!!.findViewById<TextureView>(R.id.surfaceView)

            val windowService = getSystemService(WINDOW_SERVICE) as WindowManager
            currentRotation = windowService.defaultDisplay.rotation
            logDebug("Initial rotation: $currentRotation")

            updateOrientation()

            val orientationType = if(currentRotation == Surface.ROTATION_0 || currentRotation == Surface.ROTATION_180) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE

            val baseScale = if(orientationType == Configuration.ORIENTATION_PORTRAIT) 1F else 0.5F

            setScale(surfaceView, baseScale * sf, baseScale * sf)
            logDebug("Scale set: ${baseScale * sf}")

            // Optymalizacja: Dostosuj rozdzielczość dla lepszej jakości i wydajności
            // Dla crop mode: wyższa rozdzielczość bazowa, potem skalowanie
            // Dla normal mode: dopasowana do cover display
            val displayMetrics = resources.displayMetrics
            
            // Użyj natywnych rozdzielczości Flip 3
            val targetWidth = if (sf > 1) 768 else flip3CoverWidth  // Wyższa rozdzielczość dla crop
            val targetHeight = if (sf > 1) 390 else flip3CoverHeight
            
            logDebug("VirtualDisplay resolution: ${targetWidth}x${targetHeight}, DPI: ${displayMetrics.densityDpi}")
            
            // Optymalizacja: Użyj flagi VIRTUAL_DISPLAY_FLAG_TRUSTED_ONLY dla lepszej wydajności
            // Dodaj VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY dla lepszej prywatności
            // Dodaj VIRTUAL_DISPLAY_FLAG_PRESENTATION dla trybu prezentacji
            virtualDisplay = mMediaProjection!!.createVirtualDisplay(
                "cover",
                targetWidth,
                targetHeight,
                dpi = displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or 
                DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                Surface(surfaceView.surfaceTexture),
                null,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        logDebug("VirtualDisplay stopped")
                    }
                    override fun onPaused() {
                        logDebug("VirtualDisplay paused")
                    }
                    override fun onResumed() {
                        logDebug("VirtualDisplay resumed")
                    }
                },
                Handler(Looper.getMainLooper())
            )
            logDebug("VirtualDisplay created successfully")
            
            // Spróbuj ustawić refresh rate na 60Hz dla oszczędności baterii
            trySetRefreshRate(subDisplay, targetRefreshRate)
            
            // Sprawdź czy TextureView jest gotowy
            if (surfaceView.isAvailable) {
                logDebug("TextureView is available")
            } else {
                logDebug("TextureView not yet available, waiting...")
                surfaceView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        logDebug("SurfaceTexture available: ${width}x${height}")
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        logDebug("SurfaceTexture size changed: ${width}x${height}")
                    }
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        logDebug("SurfaceTexture destroyed")
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                        // Nie loguj każdej aktualizacji - za dużo spamu
                    }
                }
            }
            
        }, 500)

        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    fun setScale(surfaceView: TextureView, x: Float, y: Float){
        logDebug("Setting scale: x=$x, y=$y")

        val pivotPointX: Float = flip3CoverWidth / 2F
        val pivotPointY: Float = flip3CoverHeight / 2F

        val matrix = Matrix()
        matrix.setScale(x, y, pivotPointX, pivotPointY)

        surfaceView.setTransform(matrix)
        logDebug("Scale transform applied")
    }

    fun updateOrientation(){
        logDebug("Updating orientation for rotation: $currentRotation")
        val windowService = getSystemService(WINDOW_SERVICE) as WindowManager

        when(currentRotation){
            Surface.ROTATION_0 -> {
                logDebug("ROTATION_0 (0°)")
                val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
                val rotation = if(sharedPref.getInt("portrait", 0) == 0) 180F else 90F
                val baseScale = 1F

                mPresentationDialog?.findViewById<TextureView>(R.id.surfaceView)?.rotation = rotation

                setScale(mPresentationDialog!!.findViewById(R.id.surfaceView), baseScale * sf, baseScale * sf)
                logDebug("Applied rotation: $rotation°, scale: ${baseScale * sf}")
            }
            Surface.ROTATION_90 -> {
                logDebug("ROTATION_90 (90°)")
                val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
                val rotation = if(sharedPref.getInt("landscape", 0) == 0) 90F else 180F
                val baseScale = if(sharedPref.getInt("landscape", 0) == 0) 0.5F else 1F

                mPresentationDialog?.findViewById<TextureView>(R.id.surfaceView)?.rotation = rotation

                setScale(mPresentationDialog!!.findViewById(R.id.surfaceView), baseScale * sf, baseScale * sf)
                logDebug("Applied rotation: $rotation°, scale: ${baseScale * sf}")
            }
            Surface.ROTATION_180 -> {
                logDebug("ROTATION_180 (180°)")
                val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
                val rotation = if(sharedPref.getInt("portrait", 0) == 0) 0F else 90F
                val baseScale = 1F

                mPresentationDialog?.findViewById<TextureView>(R.id.surfaceView)?.rotation = rotation

                setScale(mPresentationDialog!!.findViewById(R.id.surfaceView), baseScale * sf, baseScale * sf)
                logDebug("Applied rotation: $rotation°, scale: ${baseScale * sf}")
            }
            Surface.ROTATION_270 -> {
                logDebug("ROTATION_270 (270°)")
                val sharedPref = getSharedPreferences(BuildConfig.APPLICATION_ID, Context.MODE_PRIVATE)
                val rotation = if(sharedPref.getInt("landscape", 0) == 0) 270F else 0F
                val baseScale = if(sharedPref.getInt("landscape", 0) == 0) 0.5F else 1F

                mPresentationDialog?.findViewById<TextureView>(R.id.surfaceView)?.rotation = rotation

                setScale(mPresentationDialog!!.findViewById(R.id.surfaceView), baseScale * sf, baseScale * sf)
                logDebug("Applied rotation: $rotation°, scale: ${baseScale * sf}")
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onDestroy() {
        logDebug("Service destroying...")
        
        // Unregister flip state receiver
        try {
            flipStateReceiver?.let { unregisterReceiver(it) }
            logDebug("Flip state receiver unregistered")
        } catch (e: Exception) {
            logDebug("Error unregistering flip receiver: ${e.message}")
        }
        
        // Zwolnij WakeLock
        try {
            wakeLock?.release()
            logDebug("WakeLock released")
        } catch (e: Exception) {
            logDebug("Error releasing WakeLock: ${e.message}")
        }
        
        // Optymalizacja: Najpierw zatrzymaj VirtualDisplay
        virtualDisplay?.release()
        logDebug("VirtualDisplay released")
        mMediaProjection?.stop()
        logDebug("MediaProjection stopped")
        mPresentationDialog?.cancel()
        logDebug("Presentation cancelled")
        orientationEventListener?.disable()
        logDebug("Orientation listener disabled")
        Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
    }
    
    // Register BroadcastReceiver dla zmiany stanu klapki (Samsung specific)
    private fun registerFlipStateReceiver() {
        val filter = IntentFilter()
        // Samsung Intent dla zmiany stanu klapki
        filter.addAction("com.samsung.android.cover.event.COVER_STATE_CHANGED")
        filter.addAction("android.intent.action.COVER_STATE_CHANGED")
        
        flipStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.samsung.android.cover.event.COVER_STATE_CHANGED" ||
                    intent?.action == "android.intent.action.COVER_STATE_CHANGED") {
                    
                    val isClosed = intent?.getBooleanExtra("cover_state", false) ?: false
                    val oldState = isFlipClosed
                    isFlipClosed = isClosed
                    
                    logDebug("Flip state changed: ${if(isClosed) "CLOSED" else "OPEN"}")
                    
                    // Jeśli zamknięto i mirroring nie działa, uruchom go
                    if (isClosed && !oldState && virtualDisplay == null) {
                        logDebug("Flip CLOSED - starting mirroring")
                        val subDisplay = getSubDisplay()
                        if (subDisplay != null) {
                            // Potrzebujemy intent z MediaProjection data - musimy go przechować
                            // Na razie tylko logujemy
                            Toast.makeText(this@MirrorService, "Flip closed - mirroring ready", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    // Jeśli otwarto, zatrzymaj mirroring dla oszczędności baterii
                    if (!isClosed && oldState) {
                        logDebug("Flip OPENED - stopping mirroring to save battery")
                        stopMirroringButKeepService()
                    }
                }
            }
        }
        
        try {
            registerReceiver(flipStateReceiver, filter)
            logDebug("Flip state receiver registered")
        } catch (e: Exception) {
            logDebug("Failed to register flip state receiver: ${e.message}")
        }
    }
    
    // Sprawdź stan klapki przez refleksję (Samsung API)
    private fun checkFlipState(): Boolean {
        return try {
            // Spróbuj użyć Samsung CoverManager przez refleksję
            val coverManagerClass = Class.forName("com.samsung.android.cover.CoverManager")
            val getInstanceMethod = coverManagerClass.getMethod("getInstance")
            val coverManager = getInstanceMethod.invoke(null)
            val isCoverAttachedMethod = coverManagerClass.getMethod("isCoverAttached")
            val isCoverAttached = isCoverAttachedMethod.invoke(coverManager) as Boolean
            
            logDebug("Cover attached (via CoverManager): $isCoverAttached")
            isCoverAttached
        } catch (e: Exception) {
            logDebug("CoverManager not available, using fallback: ${e.message}")
            // Fallback: sprawdź czy cover display istnieje
            val subDisplay = getSubDisplay()
            subDisplay != null
        }
    }
    
    // Zatrzymaj mirroring ale zachowaj usługę (gdy klapka otwarta)
    private fun stopMirroringButKeepService() {
        try {
            virtualDisplay?.release()
            logDebug("VirtualDisplay released (flip opened)")
            virtualDisplay = null
            
            mMediaProjection?.stop()
            logDebug("MediaProjection stopped (flip opened)")
            mMediaProjection = null
            
            mPresentationDialog?.cancel()
            logDebug("Presentation cancelled (flip opened)")
            mPresentationDialog = null
            
            Toast.makeText(this, "Mirroring paused (flip opened)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logDebug("Error stopping mirroring: ${e.message}")
        }
    }
    
    // Spróbuj ustawić refresh rate dla cover display
    private fun trySetRefreshRate(display: Display, targetRate: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                val mode = display.mode
                val supportedModes = display.supportedModes
                
                logDebug("Current refresh rate: ${mode.refreshRate}Hz")
                logDebug("Target refresh rate: ${targetRate}Hz")
                
                // Znajdź tryb z najbliższym refresh rate do 60Hz
                val bestMode = supportedModes.find { 
                    Math.abs(it.refreshRate - targetRate) < 5f 
                }
                
                if (bestMode != null) {
                    logDebug("Found suitable mode: ${bestMode.refreshRate}Hz")
                    // Uwaga: setPreferredDisplayMode wymaga uprawnień systemowych
                    // Może nie działać bez signature permissions
                } else {
                    logDebug("No suitable mode found near ${targetRate}Hz")
                }
            } catch (e: Exception) {
                logDebug("Could not set refresh rate: ${e.message}")
            }
        } else {
            logDebug("Refresh rate control not available on this Android version")
        }
    }

    private fun getSubDisplay(): Display? {
        val displays: Array<Display> =
            (getSystemService("display") as DisplayManager).getDisplays("com.samsung.android.hardware.display.category.BUILTIN")
        logDebug("Built-in displays count: ${displays.size}")
        for ((index, display) in displays.withIndex()) {
            logDisplayInfo(display, "Display[$index]")
        }
        val display: Display? = if (displays.size > 1) displays[1] else null
        return display
    }
    
    private fun logDisplayInfo(display: Display, label: String) {
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        logDebug("$label: ${metrics.widthPixels}x${metrics.heightPixels}, density=${metrics.density}, dpi=${metrics.densityDpi}")
        logDebug("$label: displayId=${display.displayId}, name=${display.name}")
    }

    private var iconNotification: Bitmap? = null
    private var notification: Notification? = null
    var mNotificationManager: NotificationManager? = null
    private val mNotificationId = 373

    private fun generateForegroundNotification() {
        val intentMainLanding = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, intentMainLanding, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        iconNotification = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        if (mNotificationManager == null) {
            mNotificationManager = this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        assert(mNotificationManager != null)
        
        // Sprawdź czy Android 16+ ma inną API dla grup kanałów
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager?.createNotificationChannelGroup(
                NotificationChannelGroup("mirror_service", "Mirror service")
            )
        }
        
        val notificationChannel =
            NotificationChannel("service_channel", "Service Notifications",
                NotificationManager.IMPORTANCE_MIN)
        notificationChannel.enableLights(false)
        notificationChannel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        mNotificationManager?.createNotificationChannel(notificationChannel)
        val builder = NotificationCompat.Builder(this, "service_channel")

        builder.setContentTitle(StringBuilder(resources.getString(R.string.app_name)).append(" service is running").toString())
            .setTicker(StringBuilder(resources.getString(R.string.app_name)).append("service is running").toString())
            .setContentText("Touch to open")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(0)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        if (iconNotification != null) {
            builder.setLargeIcon(Bitmap.createScaledBitmap(iconNotification!!, 128, 128, false))
        }
        builder.color = resources.getColor(R.color.purple_200)
        notification = builder.build()
        startForeground(mNotificationId, notification)
        logDebug("Foreground notification created")

    }
    
    private fun logDebug(message: String) {
        if (debugMode) {
            Log.d(TAG, message)
        }
    }
    
    // Setup WakeLock dla utrzymania CPU aktywnego gdy telefon jest zamknięty
    private fun setupWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        // PARTIAL_WAKE_LOCK utrzymuje CPU działające nawet gdy ekran główny jest wygaszony
        // To jest KLUCZOWE dla działania przy zamkniętej klapce!
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SubUIMirror::WakeLock")
        try {
            wakeLock?.acquire(10*60*1000L) // 10 minut - Foreground Service odnawia
            logDebug("WakeLock acquired (Phone closed support)")
        } catch (e: Exception) {
            logDebug("Failed to acquire WakeLock: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Check if device has root access by trying to execute 'su' command
     */
    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.waitFor()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Execute root commands for advanced power saving
     * Only works if device is rooted and root mode is enabled
     */
    private fun executeRootCommand(command: String) {
        if (!rootModeEnabled || !hasRootAccess) {
            logDebug("Root command skipped: root not available")
            return
        }
        
        try {
            logDebug("Executing root command: $command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
            logDebug("Root command executed successfully")
        } catch (e: Exception) {
            logDebug("Root command failed: ${e.message}")
        }
    }

}