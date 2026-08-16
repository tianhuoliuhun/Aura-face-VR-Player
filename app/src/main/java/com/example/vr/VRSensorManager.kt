package com.example.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.WindowManager
import android.view.Surface

class VRSensorManager(private val context: Context, private val renderer: VRGLRenderer) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // 优先使用 GAME_ROTATION_VECTOR（无磁力计融合，抗磁干扰漂移）；
    // 设备不支持时回退到 ROTATION_VECTOR。
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var isListening = false
    private val tempMatrix = FloatArray(16)
    private val landscapeRemappedMatrix = FloatArray(16)

    // 平滑四元数：对传感器原始四元数做指数移动平均，消除手持/震动抖动。
    private val smoothQuat = FloatArray(4)
    private var hasSmoothInit = false
    private val smoothAlpha = 0.25f // 平滑系数（越小越平滑，响应越慢）

    private fun getDisplayRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: Surface.ROTATION_0
            } catch (e: Exception) {
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    fun start() {
        if (sensorManager == null || rotationSensor == null) {
            Log.w("VRSensorManager", "Rotation Vector sensor not supported on this device.")
            return
        }
        if (isListening) return

        sensorManager.registerListener(
            this,
            rotationSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
        isListening = true
        hasSmoothInit = false // 重新开始监听时重置平滑状态
    }

    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        try {
            // 旋转向量事件值 = 四元数 [x, y, z, w]（+ 可选的偏航/精度）
            if (event.values.size < 4) return
            val qx = event.values[0]
            val qy = event.values[1]
            val qz = event.values[2]
            val qw = event.values[3]

            if (!hasSmoothInit) {
                smoothQuat[0] = qx; smoothQuat[1] = qy; smoothQuat[2] = qz; smoothQuat[3] = qw
                hasSmoothInit = true
            } else {
                // 指数移动平均：smooth = normalize(smooth * (1-a) + new * a)
                // 处理四元数双覆盖（q 与 -q 等价）：点积为负时翻转新四元数，避免插值绕远路
                val dot = smoothQuat[0] * qx + smoothQuat[1] * qy + smoothQuat[2] * qz + smoothQuat[3] * qw
                val sign = if (dot < 0) -1f else 1f
                val invAlpha = 1f - smoothAlpha
                smoothQuat[0] = smoothQuat[0] * invAlpha + qx * sign * smoothAlpha
                smoothQuat[1] = smoothQuat[1] * invAlpha + qy * sign * smoothAlpha
                smoothQuat[2] = smoothQuat[2] * invAlpha + qz * sign * smoothAlpha
                smoothQuat[3] = smoothQuat[3] * invAlpha + qw * sign * smoothAlpha
                // 归一化
                val norm = kotlin.math.sqrt(
                    smoothQuat[0] * smoothQuat[0] + smoothQuat[1] * smoothQuat[1] +
                    smoothQuat[2] * smoothQuat[2] + smoothQuat[3] * smoothQuat[3]
                )
                if (norm > 1e-6f) {
                    smoothQuat[0] /= norm; smoothQuat[1] /= norm
                    smoothQuat[2] /= norm; smoothQuat[3] /= norm
                }
            }

            // 用平滑后的四元数生成旋转矩阵
            SensorManager.getRotationMatrixFromVector(tempMatrix, smoothQuat)

            // Dynamic rotation-aware sensor remap configuration:
            var axisX = SensorManager.AXIS_X
            var axisY = SensorManager.AXIS_Z // Portrait default

            when (getDisplayRotation()) {
                Surface.ROTATION_0 -> {
                    axisX = SensorManager.AXIS_X
                    axisY = SensorManager.AXIS_Z
                }
                Surface.ROTATION_90 -> {
                    axisX = SensorManager.AXIS_Z
                    axisY = SensorManager.AXIS_MINUS_X
                }
                Surface.ROTATION_180 -> {
                    axisX = SensorManager.AXIS_MINUS_X
                    axisY = SensorManager.AXIS_MINUS_Z
                }
                Surface.ROTATION_270 -> {
                    axisX = SensorManager.AXIS_MINUS_Z
                    axisY = SensorManager.AXIS_X
                }
            }

            SensorManager.remapCoordinateSystem(
                tempMatrix,
                axisX,
                axisY,
                landscapeRemappedMatrix
            )

            renderer.updateGyroRotationMatrix(landscapeRemappedMatrix)
        } catch (e: Exception) {
            Log.e("VRSensorManager", "Error updating rotation from vector", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Unused
    }
}
