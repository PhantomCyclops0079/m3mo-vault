package com.m3mo.vault.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.m3mo.vault.domain.contract.ThermalState

/**
 * DeviceProfile - Device Capability Detection
 *
 * Detects device hardware capabilities to enable adaptive algorithm selection.
 * Classifies devices into tiers for resource-aware processing.
 *
 * ## Device Tiers
 *
 * | Tier | RAM | CPU Cores | Example Devices |
 * |------|-----|-----------|-----------------|
 * | LOW_END | <3GB | ≤4 | Budget phones, old devices |
 * | MID_RANGE | 3-6GB | 4-6 | Most 2020-2022 phones |
 * | HIGH_END | >6GB | 8+ | Flagships |
 *
 * ## Usage
 *
 * ```kotlin
 * val profile = DeviceProfile.detect(context)
 * when (profile.tier) {
 *     DeviceTier.LOW_END -> useMinimalAlgorithms()
 *     DeviceTier.MID_RANGE -> useStandardAlgorithms()
 *     DeviceTier.HIGH_END -> useFullAlgorithms()
 * }
 * ```
 */
class DeviceProfile private constructor(
    /**
     * Device tier classification
     */
    val tier: DeviceTier,

    /**
     * Total RAM in MB
     */
    val totalRamMb: Int,

    /**
     * Available heap memory for app in MB
     */
    val availableHeapMb: Int,

    /**
     * Number of CPU cores
     */
    val cpuCores: Int,

    /**
     * Available storage in MB
     */
    val availableStorageMb: Int,

    /**
     * Current battery percentage
     */
    val batteryPercent: Int,

    /**
     * Whether device is charging
     */
    val isCharging: Boolean,

    /**
     * Current thermal state
     */
    val thermalState: ThermalState,

    /**
     * Android API level
     */
    val apiLevel: Int,

    /**
     * Device model
     */
    val deviceModel: String,

    /**
     * Is low RAM device (declared by OEM)
     */
    val isLowRamDevice: Boolean
) {
    /**
     * Check if device can handle transformer models
     */
    val canRunTransformers: Boolean
        get() = tier == DeviceTier.HIGH_END && totalRamMb >= 6000

    /**
     * Check if device can handle Word2Vec
     */
    val canRunWord2Vec: Boolean
        get() = tier != DeviceTier.LOW_END && totalRamMb >= 3000

    /**
     * Recommended maximum batch size
     */
    val recommendedBatchSize: Int
        get() = when (tier) {
            DeviceTier.LOW_END -> 50
            DeviceTier.MID_RANGE -> 150
            DeviceTier.HIGH_END -> 300
        }

    /**
     * Recommended embedding cache size
     */
    val recommendedCacheSize: Int
        get() = when (tier) {
            DeviceTier.LOW_END -> 500
            DeviceTier.MID_RANGE -> 1000
            DeviceTier.HIGH_END -> 2000
        }

    /**
     * Should enable Stage 2 vectorization
     */
    val shouldEnableVectorization: Boolean
        get() = tier != DeviceTier.LOW_END || (batteryPercent > 30 || isCharging)

    override fun toString(): String {
        return "DeviceProfile(tier=$tier, ram=${totalRamMb}MB, cores=$cpuCores, " +
                "storage=${availableStorageMb}MB, battery=$batteryPercent%, " +
                "charging=$isCharging, thermal=$thermalState)"
    }

    companion object {
        /**
         * Detect device capabilities
         */
        fun detect(context: Context): DeviceProfile {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalRamMb = (memoryInfo.totalMem / (1024 * 1024)).toInt()
            val availableHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val availableStorageMb = getAvailableStorageMb()
            val batteryInfo = getBatteryInfo(context)
            val isLowRamDevice = activityManager.isLowRamDevice

            val tier = classifyDevice(totalRamMb, cpuCores, isLowRamDevice)

            return DeviceProfile(
                tier = tier,
                totalRamMb = totalRamMb,
                availableHeapMb = availableHeapMb,
                cpuCores = cpuCores,
                availableStorageMb = availableStorageMb,
                batteryPercent = batteryInfo.first,
                isCharging = batteryInfo.second,
                thermalState = ThermalState.NOMINAL, // Updated by system callbacks
                apiLevel = Build.VERSION.SDK_INT,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                isLowRamDevice = isLowRamDevice
            )
        }

        /**
         * Create a mock profile for testing
         */
        fun mock(
            tier: DeviceTier = DeviceTier.MID_RANGE,
            totalRamMb: Int = 4000,
            batteryPercent: Int = 80,
            isCharging: Boolean = false
        ): DeviceProfile {
            return DeviceProfile(
                tier = tier,
                totalRamMb = totalRamMb,
                availableHeapMb = 256,
                cpuCores = 4,
                availableStorageMb = 10000,
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                thermalState = ThermalState.NOMINAL,
                apiLevel = Build.VERSION.SDK_INT,
                deviceModel = "Test Device",
                isLowRamDevice = tier == DeviceTier.LOW_END
            )
        }

        /**
         * Classify device into tier based on specs
         *
         * Uses combined RAM and CPU analysis:
         * - HIGH_END: RAM ≥ 6GB AND cores ≥ 6
         * - MID_RANGE: RAM 3-6GB OR (RAM ≥ 6GB but cores < 6)
         * - LOW_END: RAM < 3GB OR cores ≤ 4 OR OEM declared
         *
         * Key insight: High RAM with weak CPU should be downgraded
         * because vector operations are CPU-bound, not memory-bound.
         */
        private fun classifyDevice(ramMb: Int, cores: Int, isLowRamDevice: Boolean): DeviceTier {
            // If OEM declared low RAM, trust it
            if (isLowRamDevice) return DeviceTier.LOW_END

            // CPU is critical for vector operations
            // A device with 8GB RAM but 4 cores will struggle with semantic search
            val hasStrongCpu = cores >= 6
            val hasVeryStrongCpu = cores >= 8
            val hasWeakCpu = cores <= 4

            // RAM thresholds
            val hasHighRam = ramMb >= 6000
            val hasMidRam = ramMb >= 3000
            val hasLowRam = ramMb < 3000

            return when {
                // Low-end: Either low RAM or weak CPU
                hasLowRam || hasWeakCpu -> DeviceTier.LOW_END

                // High-end: Both high RAM and strong CPU
                hasHighRam && hasVeryStrongCpu -> DeviceTier.HIGH_END

                // Mid-range: Everything else
                // Including: high RAM + moderate CPU, moderate RAM + strong CPU
                else -> DeviceTier.MID_RANGE
            }
        }

        /**
         * Get available storage in MB
         */
        private fun getAvailableStorageMb(): Int {
            return try {
                val stat = StatFs(Environment.getDataDirectory().path)
                val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                (availableBytes / (1024 * 1024)).toInt()
            } catch (e: Exception) {
                1000 // Default 1GB
            }
        }

        /**
         * Get battery info (percent, isCharging)
         */
        private fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
            return try {
                val batteryIntent = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )

                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val percent = (level * 100) / scale

                val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                Pair(percent, isCharging)
            } catch (e: Exception) {
                Pair(50, false) // Default values
            }
        }
    }
}

/**
 * Device capability tiers
 */
enum class DeviceTier {
    /**
     * Budget devices with limited resources
     * - RAM < 3GB
     * - Use TF-IDF only
     * - Small batch sizes
     * - May skip Stage 2
     */
    LOW_END,

    /**
     * Average devices
     * - RAM 3-6GB
     * - Can use Word2Vec
     * - Medium batch sizes
     */
    MID_RANGE,

    /**
     * Flagship devices
     * - RAM > 6GB
     * - Can use transformers
     * - Large batch sizes
     */
    HIGH_END
}

/**
 * Runtime device state (for periodic updates)
 */
data class DeviceState(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalState: ThermalState,
    val availableMemoryMb: Int,
    val availableStorageMb: Int,
    val timestamp: Long = System.currentTimeMillis()
)
