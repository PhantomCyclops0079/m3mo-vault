package com.m3mo.vault.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.LogTags
import com.m3mo.vault.domain.contract.ThermalState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BatteryAwareScheduler - Intelligent Task Scheduling
 *
 * Schedules heavy operations based on battery state and charging status.
 * Ensures background optimization doesn't drain user's battery.
 *
 * ## Scheduling Rules
 *
 * | Battery | Charging | Allowed Operations |
 * |---------|----------|-------------------|
 * | >50% | No | Full optimization |
 * | 20-50% | No | Incremental only |
 * | <20% | No | Essential only |
 * | Any | Yes | Full optimization |
 *
 * ## Usage
 *
 * ```kotlin
 * val scheduler = BatteryAwareScheduler(context, logger)
 *
 * if (scheduler.canRunFullOptimization()) {
 *     runNightlyOptimization()
 * } else if (scheduler.canRunIncrementalOptimization()) {
 *     runIncrementalDecay()
 * }
 *
 * // Or wait for optimal conditions
 * scheduler.addOptimalConditionListener { 
 *     startHeavyProcessing()
 * }
 * ```
 */
class BatteryAwareScheduler(
    private val context: Context,
    private val logger: Logger
) {
    // Current state
    @Volatile private var batteryPercent: Int = 50
    @Volatile private var isCharging: Boolean = false
    @Volatile private var thermalState: ThermalState = ThermalState.NOMINAL
    @Volatile private var isPowerSaveMode: Boolean = false

    // Listeners
    private val conditionListeners = CopyOnWriteArrayList<OptimalConditionListener>()
    private val stateListeners = CopyOnWriteArrayList<BatteryStateListener>()

    // Battery receiver
    private var batteryReceiver: BroadcastReceiver? = null
    private var isRegistered = false

    init {
        updateBatteryState()
    }

    /**
     * Start monitoring battery changes
     */
    fun startMonitoring() {
        if (isRegistered) return

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> handleBatteryChanged(intent)
                    Intent.ACTION_POWER_CONNECTED -> handlePowerConnected()
                    Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected()
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> handlePowerSaveModeChanged()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }

        context.registerReceiver(batteryReceiver, filter)
        isRegistered = true

        logger.d(LogTags.WORKER, "BatteryAwareScheduler: Started monitoring")
    }

    /**
     * Stop monitoring battery changes
     */
    fun stopMonitoring() {
        if (!isRegistered) return

        batteryReceiver?.let {
            context.unregisterReceiver(it)
        }
        batteryReceiver = null
        isRegistered = false

        logger.d(LogTags.WORKER, "BatteryAwareScheduler: Stopped monitoring")
    }

    // ========== Scheduling Decisions ==========

    /**
     * Can run full nightly optimization?
     * Requires: charging OR battery > 50%
     */
    fun canRunFullOptimization(): Boolean {
        if (isPowerSaveMode) return false
        if (thermalState >= ThermalState.SERIOUS) return false
        return isCharging || batteryPercent > 50
    }

    /**
     * Can run incremental optimization?
     * Requires: charging OR battery > 20%
     */
    fun canRunIncrementalOptimization(): Boolean {
        if (isPowerSaveMode) return false
        if (thermalState >= ThermalState.CRITICAL) return false
        return isCharging || batteryPercent > 20
    }

    /**
     * Can run any background work?
     * Requires: charging OR battery > 15%
     */
    fun canRunBackgroundWork(): Boolean {
        if (isPowerSaveMode && !isCharging) return false
        return isCharging || batteryPercent > 15
    }

    /**
     * Is device in optimal condition for heavy processing?
     * Requires: charging AND battery > 30% AND no thermal issues
     */
    fun isOptimalCondition(): Boolean {
        return isCharging && 
               batteryPercent > 30 && 
               thermalState == ThermalState.NOMINAL &&
               !isPowerSaveMode
    }

    /**
     * Get recommended processing intensity
     */
    fun getRecommendedIntensity(): ProcessingIntensity {
        return when {
            !canRunBackgroundWork() -> ProcessingIntensity.NONE
            isOptimalCondition() -> ProcessingIntensity.FULL
            canRunFullOptimization() -> ProcessingIntensity.HIGH
            canRunIncrementalOptimization() -> ProcessingIntensity.MEDIUM
            else -> ProcessingIntensity.LOW
        }
    }

    /**
     * Get estimated time until optimal conditions
     * Returns null if charging (will reach optimal soon)
     */
    fun estimatedTimeToOptimal(): Long? {
        if (isOptimalCondition()) return 0
        if (isCharging) return 5 * 60 * 1000L // ~5 minutes estimate
        return null // Unknown - not charging
    }

    // ========== Current State ==========

    /**
     * Get current battery percent
     */
    fun getBatteryPercent(): Int = batteryPercent

    /**
     * Is device currently charging?
     */
    fun isCharging(): Boolean = isCharging

    /**
     * Is power save mode enabled?
     */
    fun isPowerSaveMode(): Boolean = isPowerSaveMode

    /**
     * Get current thermal state
     */
    fun getThermalState(): ThermalState = thermalState

    /**
     * Update thermal state (called from system callback)
     */
    fun updateThermalState(state: ThermalState) {
        val wasOptimal = isOptimalCondition()
        thermalState = state

        logger.d(LogTags.WORKER, "Thermal state changed: $state")

        if (state >= ThermalState.SERIOUS) {
            stateListeners.forEach { it.onThermalThrottle(state) }
        }

        checkOptimalConditionChange(wasOptimal)
    }

    // ========== Listeners ==========

    /**
     * Add listener for optimal conditions
     */
    fun addOptimalConditionListener(listener: OptimalConditionListener) {
        conditionListeners.add(listener)
    }

    /**
     * Remove optimal condition listener
     */
    fun removeOptimalConditionListener(listener: OptimalConditionListener) {
        conditionListeners.remove(listener)
    }

    /**
     * Add battery state listener
     */
    fun addStateListener(listener: BatteryStateListener) {
        stateListeners.add(listener)
    }

    /**
     * Remove battery state listener
     */
    fun removeStateListener(listener: BatteryStateListener) {
        stateListeners.remove(listener)
    }

    // ========== Internal Methods ==========

    private fun updateBatteryState() {
        try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            batteryIntent?.let { intent ->
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryPercent = (level * 100) / scale

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            isPowerSaveMode = powerManager?.isPowerSaveMode == true

        } catch (e: Exception) {
            logger.w(LogTags.WORKER, "Failed to update battery state: ${e.message}")
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val wasOptimal = isOptimalCondition()

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPercent = (level * 100) / scale

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        stateListeners.forEach { it.onBatteryChanged(batteryPercent, isCharging) }

        checkOptimalConditionChange(wasOptimal)
    }

    private fun handlePowerConnected() {
        val wasOptimal = isOptimalCondition()
        isCharging = true

        logger.d(LogTags.WORKER, "Power connected")
        stateListeners.forEach { it.onChargingStateChanged(true) }

        checkOptimalConditionChange(wasOptimal)
    }

    private fun handlePowerDisconnected() {
        val wasOptimal = isOptimalCondition()
        isCharging = false

        logger.d(LogTags.WORKER, "Power disconnected")
        stateListeners.forEach { it.onChargingStateChanged(false) }

        checkOptimalConditionChange(wasOptimal)
    }

    private fun handlePowerSaveModeChanged() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        isPowerSaveMode = powerManager?.isPowerSaveMode == true

        logger.d(LogTags.WORKER, "Power save mode: $isPowerSaveMode")
        stateListeners.forEach { it.onPowerSaveModeChanged(isPowerSaveMode) }
    }

    private fun checkOptimalConditionChange(wasOptimal: Boolean) {
        val isOptimal = isOptimalCondition()
        if (isOptimal && !wasOptimal) {
            logger.i(LogTags.WORKER, "Optimal conditions reached!")
            conditionListeners.forEach { it.onOptimalConditionReached() }
        }
    }
}

/**
 * Processing intensity levels
 */
enum class ProcessingIntensity {
    NONE,   // No processing allowed
    LOW,    // Essential operations only
    MEDIUM, // Incremental optimization
    HIGH,   // Full optimization
    FULL    // Aggressive optimization (charging + good battery)
}

/**
 * Listener for optimal processing conditions
 */
fun interface OptimalConditionListener {
    fun onOptimalConditionReached()
}

/**
 * Listener for battery state changes
 */
interface BatteryStateListener {
    fun onBatteryChanged(percent: Int, isCharging: Boolean)
    fun onChargingStateChanged(isCharging: Boolean)
    fun onPowerSaveModeChanged(isPowerSaveMode: Boolean)
    fun onThermalThrottle(state: ThermalState)
}
