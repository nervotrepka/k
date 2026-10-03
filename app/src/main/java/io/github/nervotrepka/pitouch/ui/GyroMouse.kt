package io.github.nervotrepka.pitouch.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sign

/**
 * Air mouse: hold the phone flat, top pointing at the screen.
 * Turning left/right moves the cursor horizontally, tilting the top up/down moves it vertically.
 */
class GyroMouse(context: Context, private val onMove: (Int, Int) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available: Boolean get() = gyro != null
    var sensitivity = 1f
    var running = false
        private set

    private var lastTimestamp = 0L
    private var fracX = 0f
    private var fracY = 0f

    fun start(): Boolean {
        val sensor = gyro ?: return false
        lastTimestamp = 0L
        running = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return running
    }

    fun stop() {
        sensors?.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ts = event.timestamp
        val dt = (ts - lastTimestamp) / 1e9f
        lastTimestamp = ts
        if (dt <= 0f || dt > 0.1f) return
        val gain = GAIN * sensitivity * dt
        fracX += -deadZone(event.values[2]) * gain
        fracY += -deadZone(event.values[0]) * gain
        val ix = fracX.toInt()
        val iy = fracY.toInt()
        fracX -= ix; fracY -= iy
        if (ix != 0 || iy != 0) onMove(ix, iy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun deadZone(v: Float): Float = if (abs(v) < DEAD_ZONE) 0f else v - sign(v) * DEAD_ZONE

    private companion object {
        /** Pixels per radian. */
        const val GAIN = 1500f
        /** rad/s, filters hand tremor and sensor drift. */
        const val DEAD_ZONE = 0.03f
    }
}
