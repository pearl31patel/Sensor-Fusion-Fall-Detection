package com.example.falltest

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class FallDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    // Fall detection state
    private var freeFallDetected = false
    private var freeFallTime = 0L
    private var impactDetected = false
    private var impactTime = 0L
    private var stillnessStart = 0L

    // Rolling SMV window for variance-based stillness detection
    private val smvWindow = mutableListOf<Float>()

    // Alert state
    private var alertTimer: CountDownTimer? = null
    private var alertActive = false

    companion object {
        const val TAG = "FallDetection"

        // --- Thresholds (tuned for sensitivity) ---
        // Free-fall: normal gravity is ~9.8. Below 6.5 = reduced-G phase (rapid drop or actual free fall)
        const val FREE_FALL_THRESHOLD = 6.5f
        // Impact: spike above 15 m/s² (~1.5G) after the low-G phase
        const val IMPACT_THRESHOLD = 15.0f
        // How long after free fall we wait for the impact spike
        const val IMPACT_WINDOW_MS = 3000L
        // Stillness: variance of recent SMV samples below this = person lying still
        const val STILLNESS_VARIANCE = 1.5f
        // How long stillness must persist to confirm the fall
        const val STILLNESS_DURATION_MS = 1000L
        // How many samples in the rolling window (~400ms at 50Hz)
        const val WINDOW_SIZE = 20

        const val CHANNEL_ID = "fall_detection_channel"
        const val NOTIF_ID = 1
        const val ACTION_CANCEL_ALERT = "com.example.falltest.CANCEL_FALL_ALERT"
        const val ACTION_SIMULATE_FALL = "com.example.falltest.SIMULATE_FALL"
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CANCEL_ALERT -> cancelAlert()
                ACTION_SIMULATE_FALL -> onFallConfirmed()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Monitoring for falls..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Monitoring for falls..."))
        }
        acquireWakeLock()
        initSensors()

        val filter = IntentFilter().apply {
            addAction(ACTION_CANCEL_ALERT)
            addAction(ACTION_SIMULATE_FALL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Accelerometer registered")
        } ?: Log.e(TAG, "No accelerometer found!")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            processAccelerometer(event.values, System.currentTimeMillis())
        }
    }

    private fun processAccelerometer(values: FloatArray, now: Long) {
        val smv = sqrt(
            values[0] * values[0] +
            values[1] * values[1] +
            values[2] * values[2]
        )

        // Maintain rolling window for variance calculation
        smvWindow.add(smv)
        if (smvWindow.size > WINDOW_SIZE) smvWindow.removeAt(0)

        when {
            // Phase 1 — low-G (free fall / rapid drop)
            !freeFallDetected && !impactDetected && smv < FREE_FALL_THRESHOLD -> {
                freeFallDetected = true
                freeFallTime = now
                Log.d(TAG, "Low-G phase detected — SMV: $smv")
            }

            // Phase 1 window expired without impact
            freeFallDetected && !impactDetected && (now - freeFallTime) > IMPACT_WINDOW_MS -> {
                freeFallDetected = false
                Log.d(TAG, "Low-G window expired, resetting")
            }

            // Phase 2 — impact spike after low-G
            freeFallDetected && smv > IMPACT_THRESHOLD -> {
                val elapsed = now - freeFallTime
                if (elapsed <= IMPACT_WINDOW_MS) {
                    Log.d(TAG, "Impact detected — SMV: $smv, elapsed: ${elapsed}ms")
                    impactDetected = true
                    impactTime = now
                    freeFallDetected = false
                    stillnessStart = 0L
                } else {
                    freeFallDetected = false
                }
            }

            // Phase 3 — stillness via variance after impact
            impactDetected && smvWindow.size >= WINDOW_SIZE -> {
                // Timeout: if no stillness after 10s, reset
                if (now - impactTime > 10000L) {
                    Log.d(TAG, "Impact timeout — no fall confirmed")
                    resetState()
                    return
                }

                val mean = smvWindow.average().toFloat()
                val variance = smvWindow.map { (it - mean) * (it - mean) }.average().toFloat()

                if (variance < STILLNESS_VARIANCE) {
                    if (stillnessStart == 0L) stillnessStart = now
                    val stillDuration = now - stillnessStart
                    Log.d(TAG, "Still... variance=${"%.2f".format(variance)} duration=${stillDuration}ms")
                    if (stillDuration >= STILLNESS_DURATION_MS) {
                        Log.d(TAG, "Fall confirmed!")
                        onFallConfirmed()
                        resetState()
                    }
                } else {
                    if (stillnessStart != 0L) Log.d(TAG, "Movement after impact, resetting stillness")
                    stillnessStart = 0L
                }
            }
        }
    }

    private fun resetState() {
        freeFallDetected = false
        impactDetected = false
        stillnessStart = 0L
        freeFallTime = 0L
        impactTime = 0L
        smvWindow.clear()
    }

    private fun onFallConfirmed() {
        if (alertActive) return
        Log.d(TAG, "FALL CONFIRMED — triggering alert")
        sendBroadcast(Intent("FALL_DETECTED"))
        updateNotification("Fall detected! Open app to cancel alert.")
        startAlertTimer()
    }

    private fun startAlertTimer() {
        alertActive = true
        alertTimer?.cancel()
        alertTimer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                alertActive = false
                EmailSender.sendFallAlert(this@FallDetectionService) { success, error ->
                    val msg = if (success) "Emergency email sent to contacts."
                              else "Alert sent (email error: $error)"
                    updateNotification(msg)
                    Log.d(TAG, msg)
                }
                sendBroadcast(Intent("FALL_ALERT_SENT"))
            }
        }.start()
    }

    private fun cancelAlert() {
        alertTimer?.cancel()
        alertActive = false
        updateNotification("Alert cancelled. Monitoring for falls...")
        sendBroadcast(Intent("FALL_ALERT_CANCELLED"))
        Log.d(TAG, "Alert cancelled by user")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Fall Detection", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Fall detection monitoring service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Guard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FallDetector::WakeLock")
        wakeLock.acquire(10 * 60 * 1000L)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        alertTimer?.cancel()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }
}
