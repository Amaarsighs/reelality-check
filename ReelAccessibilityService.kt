package com.example.reel_ality_check.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.widget.Toast

class ReelAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelAccessibilityService"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val REELS_NAVIGATION_ID = "com.instagram.android:id/tab_bar_reels"
        private const val REELS_ACTIVE_COLOR = -16776961 // Blue color value
        
        @Volatile
        private var instance: ReelAccessibilityService? = null
        
        fun getInstance(): ReelAccessibilityService? = instance
    }

    private var isReelsActive = false
    private var lastDetectionTime = 0L
    private val detectionCooldown = 2000L // 2 seconds cooldown

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service Connected")
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or 
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_DEFAULT
            packageNames = arrayOf(INSTAGRAM_PACKAGE)
            notificationTimeout = 100
        }
        serviceInfo = info
        
        Toast.makeText(this, "Reel-ality Check monitoring started", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        if (event.packageName != INSTAGRAM_PACKAGE) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDetectionTime < detectionCooldown) return
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // Check if Reels navigation is active using resource ID
            val reelsNavNode = findNodeById(rootNode, REELS_NAVIGATION_ID)
            if (reelsNavNode != null) {
                val isCurrentlyActive = isReelsNavigationActive(reelsNavNode)
                
                if (isCurrentlyActive && !isReelsActive) {
                    // Reels just became active
                    isReelsActive = true
                    onReelsStarted()
                } else if (!isCurrentlyActive && isReelsActive) {
                    // Reels just became inactive
                    isReelsActive = false
                    onReelsEnded()
                }
            }
            
            // Alternative: Pixel color detection for UI-driven triggering
            if (isReelsActive) {
                detectReelsContent(rootNode)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling window content change", e)
        } finally {
            rootNode?.recycle()
        }
        
        lastDetectionTime = currentTime
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        Log.d(TAG, "Window state changed: ${event.className}")
        
        // Check if we're in a Reels-specific activity
        if (event.className?.toString()?.contains("Reel") == true ||
            event.className?.toString()?.contains("reel") == true) {
            if (!isReelsActive) {
                isReelsActive = true
                onReelsStarted()
            }
        }
    }

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            
            if (node.viewIdResourceName == id) {
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            
            node.recycle()
        }
        
        return null
    }

    private fun isReelsNavigationActive(node: AccessibilityNodeInfo): Boolean {
        // Check if the node is selected/active
        if (node.isSelected) return true
        
        // Check color as fallback (pixel color comparator)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        // This would require screen capture to get actual pixel colors
        // For now, we'll use selection state as primary indicator
        
        return node.isSelected || node.isAccessibilityFocused
    }

    private fun detectReelsContent(rootNode: AccessibilityNodeInfo) {
        // Extract overlay text and metadata using OCR-like approach
        val textNodes = mutableListOf<String>()
        
        collectTextNodes(rootNode, textNodes)
        
        if (textNodes.isNotEmpty()) {
            val content = textNodes.joinToString(" ")
            onContentDetected(content)
        }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo, textNodes: MutableList<String>) {
        node.text?.let { text ->
            val textContent = text.toString().trim()
            if (textContent.isNotEmpty() && textContent.length > 2) {
                textNodes.add(textContent)
            }
        }
        
        node.contentDescription?.let { desc ->
            val descContent = desc.toString().trim()
            if (descContent.isNotEmpty() && descContent.length > 2) {
                textNodes.add(descContent)
            }
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectTextNodes(child, textNodes)
                child.recycle()
            }
        }
    }

    private fun onReelsStarted() {
        Log.d(TAG, "Reels session started")
        // Notify Flutter app to start screen capture
        sendBroadcastToFlutter("REELS_STARTED")
    }

    private fun onReelsEnded() {
        Log.d(TAG, "Reels session ended")
        // Notify Flutter app to stop screen capture and process data
        sendBroadcastToFlutter("REELS_ENDED")
    }

    private fun onContentDetected(content: String) {
        Log.d(TAG, "Content detected: $content")
        // Send detected content to Flutter app
        sendBroadcastToFlutter("CONTENT_DETECTED", mapOf("content" to content))
    }

    private fun sendBroadcastToFlutter(action: String, extras: Map<String, String> = emptyMap()) {
        val intent = Intent("com.example.reel_ality_check.REEL_EVENT").apply {
            putExtra("action", action)
            extras.forEach { (key, value) ->
                putExtra(key, value)
            }
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility Service Destroyed")
    }
}
