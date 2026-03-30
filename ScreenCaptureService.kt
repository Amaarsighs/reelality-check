package com.example.reel_ality_check.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CHANNEL_NAME = "Screen Capture Service"
        
        @Volatile
        private var instance: ScreenCaptureService? = null
        
        fun getInstance(): ScreenCaptureService? = instance
        
        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var resultCode: Int = 0
        private var resultData: Intent? = null
        
        fun setMediaProjectionData(code: Int, data: Intent?) {
            resultCode = code
            resultData = data
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var displayMetrics: DisplayMetrics
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    
    private var isCapturing = false
    private var captureFrameCount = 0
    private val maxFrames = 300 // 5 seconds at 60fps
    
    private val captureListener = object : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            if (!isCapturing) return
            
            val image = reader.acquireLatestImage() ?: return
            
            try {
                // Process the frame for OCR and analysis
                processFrame(image)
                captureFrameCount++
                
                if (captureFrameCount >= maxFrames) {
                    stopCapture()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                image.close()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        
        screenDensity = displayMetrics.densityDpi
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        Log.d(TAG, "Screen Capture Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_CAPTURE" -> {
                startCapture()
            }
            "STOP_CAPTURE" -> {
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen capture service for Reel-ality Check"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reel-ality Check")
            .setContentText("Screen capture active - Monitoring Instagram Reels")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startCapture() {
        if (isCapturing) return
        
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            if (mediaProjection == null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
            }
            
            mediaProjection?.let { mp ->
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                
                virtualDisplay = mp.createVirtualDisplay(
                    "Reel-ality Check",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    handler
                )
                
                imageReader!!.setOnImageAvailableListener(captureListener, handler)
                isCapturing = true
                captureFrameCount = 0
                
                Log.d(TAG, "Screen capture started")
                sendCaptureStatus("CAPTURE_STARTED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            sendCaptureStatus("CAPTURE_ERROR", e.message)
        }
    }

    private fun stopCapture() {
        if (!isCapturing) return
        
        try {
            isCapturing = false
            
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            Log.d(TAG, "Screen capture stopped")
            sendCaptureStatus("CAPTURE_STOPPED")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        }
    }

    private fun processFrame(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        // Create bitmap from image data
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Process frame for OCR and content detection
        processFrameForContent(bitmap)
        
        // Clean up to comply with NFR-1 (Ephemeral Data Processing)
        bitmap.recycle()
    }

    private fun processFrameForContent(bitmap: Bitmap) {
        // This would integrate with OCR libraries
        // For now, we'll simulate content detection
        
        handler.postDelayed({
            // Send frame data to Flutter app for processing
            sendFrameData(bitmap)
        }, 100)
    }

    private fun sendFrameData(bitmap: Bitmap) {
        // Convert bitmap to base64 or temporary file path
        // Send to Flutter app via broadcast or method channel
        
        val intent = Intent("com.example.reel_ality_check.FRAME_DATA").apply {
            putExtra("frame_timestamp", System.currentTimeMillis())
            putExtra("frame_width", bitmap.width)
            putExtra("frame_height", bitmap.height)
        }
        sendBroadcast(intent)
    }

    private fun sendCaptureStatus(status: String, error: String? = null) {
        val intent = Intent("com.example.reel_ality_check.CAPTURE_STATUS").apply {
            putExtra("status", status)
            error?.let { putExtra("error", it) }
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        instance = null
        Log.d(TAG, "Screen Capture Service Destroyed")
    }
}
