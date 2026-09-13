package com.example.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * 头部姿态追踪（陀螺仪 / 旋转向量）。
 *
 * ## 为什么需要"姿态基准"（recenter）
 * 旋转向量给出的是设备在**世界坐标系中的绝对姿态**。如果直接拿它当视角用，
 * 那么用户拿起手机那一刻手机歪多少、朝向哪里，画面初始朝向就是什么——
 * 表现为"一开陀螺仪视角就是斜的，而且怎么调都调不回来"。
 *
 * 正确做法是先记录一个基准姿态，之后只输出**相对基准的转动**：
 *
 *     相机矩阵 = R_基准 × R_当前ᵀ          （R 为 device→world 旋转矩阵）
 *
 * 这样开启瞬间的正前方即为"视角原点"，之后画面只跟随头部相对转动。
 *
 * ## 与 VRGLRenderer 的接口约定
 * 渲染端旋转的是**全景球模型**（不是相机），因此直接输出相对基准的转动：
 *
 *     输出 = R_当前 × R_基准ᵀ
 *
 * 渲染端把它左乘到模型矩阵上即可。
 * （v125 之前渲染端还会多做一次 transpose，实测导致上下左右全部反向，已修正；
 *   少数机型若仍相反，可用 VRGLRenderer.gyroInverted 开关切回旧行为。）
 *
 * ## 朝向模式
 * "设备坐标系 → 世界坐标系"的轴向映射随握持方式而变，两种场景的正确轴向
 * 完全不同，因此提供 [OrientationMode] 显式区分。
 */
class VRSensorManager(private val context: Context, private val renderer: VRGLRenderer) : SensorEventListener {

    /**
     * 陀螺仪朝向模式。
     *
     * 注：手持横屏与 VR 眼镜平放时，"屏幕的上方"在设备坐标系里对应完全不同的轴，
     * 用错会导致低头时画面左右转、转头时画面上下滚。
     */
    enum class OrientationMode {
        /** 手持横屏举着看：屏幕上方由设备 X 轴表达，且随屏幕旋转变化。 */
        HANDHELD,

        /** 放进 VR 眼镜平放观看：视线沿屏幕法线，世界上方对应设备 Z 轴，与屏幕旋转无关。 */
        VR_BOX
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // 优先使用 GAME_ROTATION_VECTOR（无磁力计融合，抗磁干扰漂移）；
    // 设备不支持时回退到 ROTATION_VECTOR。
    //
    // 注意：GAME_ROTATION_VECTOR 抗的是**磁干扰**，但它没有绝对北向基准，
    // 因此 yaw（水平朝向）会随陀螺仪零偏缓慢漂移——这是原理性的，
    // 靠 [recenter] 重新对齐即可消除。
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var isListening = false

    // ---- 复用缓冲：传感器回调频率约 50Hz，避免每次分配对象 ----
    /** getRotationMatrixFromVector 的输出（device→world） */
    private val tempMatrix = FloatArray(16)
    /** remapCoordinateSystem 的输出 */
    private val remappedMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    /** R_基准ᵀ */
    private val baselineTransposed = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    /** 最终输出给渲染端的矩阵 */
    private val outputMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    /** 姿态基准 R_基准；hasBaseline=false 时表示尚未捕获 */
    private val baselineMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var hasBaseline = false

    /** 请求在下一个采样点重新捕获基准。跨线程访问，用 [baselineLock] 保护。 */
    private var recenterRequested = true
    private val baselineLock = Any()

    /**
     * 当前朝向模式。切换后会强制重新对齐基准，否则视角会在切换瞬间跳变。
     */
    @Volatile
    var orientationMode: OrientationMode = OrientationMode.HANDHELD
        private set

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

    /**
     * 根据朝向模式与当前屏幕旋转，决定 remapCoordinateSystem 的轴向。
     *
     * - VR_BOX：对应官方"透过屏幕看"（AR/VR）用法，固定 (X, Z)，**不随屏幕旋转变化**。
     * - HANDHELD：标准映射（全为 X/Y 组合），随屏幕旋转变化。
     */
    private fun axesForMode(mode: OrientationMode, rotation: Int): Pair<Int, Int> =
        when (mode) {
            OrientationMode.VR_BOX ->
                SensorManager.AXIS_X to SensorManager.AXIS_Z

            OrientationMode.HANDHELD -> when (rotation) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
        }

    /** 把当前姿态设为"正前方"。开启陀螺仪、切模式、或用户主动重置视角时调用。 */
    fun recenter() {
        synchronized(baselineLock) {
            recenterRequested = true
        }
    }

    /** 切换朝向模式；会顺带重新对齐基准，避免视角跳变。 */
    fun setOrientationMode(mode: OrientationMode) {
        if (orientationMode == mode) return
        orientationMode = mode
        recenter()
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
        recenter()            // 开启瞬间以当前姿态为视角原点
    }

    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        synchronized(baselineLock) {
            hasBaseline = false // 下次开启重新捕获基准
        }
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

            // 用平滑后的四元数生成旋转矩阵（device → world）
            SensorManager.getRotationMatrixFromVector(tempMatrix, smoothQuat)

            // 按朝向模式重映射坐标系
            val mode = orientationMode
            val (axisX, axisY) = axesForMode(mode, getDisplayRotation())
            if (!SensorManager.remapCoordinateSystem(tempMatrix, axisX, axisY, remappedMatrix)) {
                // remap 失败时不修改输出矩阵，可能残留旧值（首次为全 0 会让画面消失），
                // 因此丢弃这一帧，等下一次采样。
                Log.w("VRSensorManager", "remapCoordinateSystem failed (axisX=$axisX axisY=$axisY)")
                return
            }

            // ---- 姿态基准对齐（recenter）----
            synchronized(baselineLock) {
                if (recenterRequested || !hasBaseline) {
                    System.arraycopy(remappedMatrix, 0, baselineMatrix, 0, 16)
                    hasBaseline = true
                    recenterRequested = false
                    // 基准自身的转置：输出 = R_当前 × R_基准ᵀ
                    Matrix.transposeM(baselineTransposed, 0, baselineMatrix, 0)
                }
            }

            // 输出 = R_当前 × R_基准ᵀ
            // （渲染端还会 transpose 一次，最终得到 R_基准 × R_当前ᵀ）
            Matrix.multiplyMM(outputMatrix, 0, remappedMatrix, 0, baselineTransposed, 0)

            renderer.updateGyroRotationMatrix(outputMatrix)
        } catch (e: Exception) {
            Log.e("VRSensorManager", "Error updating rotation from vector", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Unused
    }
}
