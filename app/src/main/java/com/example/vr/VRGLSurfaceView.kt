package com.example.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class VRGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val renderer = VRGLRenderer(context)
    var isUiLocked = false
    var isViewLocked = false

    // Touch event notifications for UI overlay reset triggers
    var onInteractionTriggered: (() -> Unit)? = null
    var onTouchEventState: ((Boolean) -> Unit)? = null
    private var hasMovedSinceDown = false
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    private var previousX = 0f
    private var previousY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // Gestures detectors
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        // Setup EGL core version 2.0
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY

        // Configuration of pinch zoom detector
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scale = detector.scaleFactor
                // Invert scale because zooming in reduces FOV degrees
                var fov = renderer.fovDeg / scale
                if (fov < 25f) fov = 25f
                if (fov > 125f) fov = 125f
                renderer.fovDeg = fov
                onInteractionTriggered?.invoke()
                return true
            }
        })

        // Configuration of click detectors and clamped fling
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                onInteractionTriggered?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                onInteractionTriggered?.invoke()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!isViewLocked) {
                    // Restrict maximum fling velocity to prevent the video from shifting too fast or too far
                    val maxFlingVelocity = 1200f // pixels per second
                    val clampedVx = velocityX.coerceIn(-maxFlingVelocity, maxFlingVelocity)
                    val clampedVy = velocityY.coerceIn(-maxFlingVelocity, maxFlingVelocity)

                    // Convert velocity (pixels/second) to angular delta per frame (assuming ~60fps)
                    val sensitivity = renderer.flingSensitivity
                    val scaleFactor = 1.0f / 60.0f
                    renderer.flingVelocityYaw = -clampedVx * scaleFactor * sensitivity
                    renderer.flingVelocityPitch = -clampedVy * scaleFactor * sensitivity
                }
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            android.util.Log.d("VRGLSurfaceView", "touch down at x=${event.x} y=${event.y}")
        }
        if (isUiLocked) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                onSingleTap?.invoke()
            }
            return true
        }

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            hasMovedSinceDown = false
        }

        // Let detectors check events first
        // 拖动后松手：用 CANCEL 替代 UP 传给 GestureDetector，防止误判为单点 tap（避免 UI 弹出）
        if (action == MotionEvent.ACTION_UP && hasMovedSinceDown) {
            val cancelEvt = MotionEvent.obtain(event)
            cancelEvt.action = MotionEvent.ACTION_CANCEL
            gestureDetector.onTouchEvent(cancelEvt)
            cancelEvt.recycle()
        } else {
            gestureDetector.onTouchEvent(event)
        }
        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress) {
            // While scaling/pinching, reset active pointer to avoid sudden drag jumps when scaling ends
            activePointerId = MotionEvent.INVALID_POINTER_ID
            onTouchEventState?.invoke(true)
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel any active flings immediately on new touch down
                renderer.flingVelocityYaw = 0f
                renderer.flingVelocityPitch = 0f

                activePointerId = event.getPointerId(0)
                previousX = event.x
                previousY = event.y
                // Note: no onTouchEventState(true) here on purpose.
                // A pure tap (DOWN then UP without MOVE) must not hide the UI,
                // otherwise the single-tap toggle would always re-show it.
                onInteractionTriggered?.invoke()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // When a secondary finger touches down, update our tracking focus to prevent coordinate jumps
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                previousX = event.getX(index)
                previousY = event.getY(index)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex != MotionEvent.INVALID_POINTER_ID) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)

                    val dx = x - previousX
                    val dy = y - previousY

                    // 记录发生过拖动：拖动后的松手不应被判定为单点 tap（防止 UI 弹出）
                    if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                        hasMovedSinceDown = true
                    }

                    if (!isViewLocked) {
                        val sensitivity = renderer.panSensitivity
                        renderer.manualYaw = (renderer.manualYaw - dx * sensitivity) % 360f
                        renderer.manualPitch = (renderer.manualPitch - dy * sensitivity).coerceIn(-85f, 85f)
                    }

                    previousX = x
                    previousY = y
                } else {
                    // Fallback to primary pointer if the tracked one becomes invalid
                    activePointerId = event.getPointerId(0)
                    previousX = event.x
                    previousY = event.y
                }
                onTouchEventState?.invoke(true)
                onInteractionTriggered?.invoke()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    // The active finger went up. Select a remaining pointer to track and reset coords
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newPointerIndex)
                        previousX = event.getX(newPointerIndex)
                        previousY = event.getY(newPointerIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                onTouchEventState?.invoke(false)
                onInteractionTriggered?.invoke()
            }
        }
        return true
    }

    fun updateImage(bitmap: Bitmap) {
        renderer.updateImage(bitmap)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    fun release() {
        renderer.release()
    }
}
