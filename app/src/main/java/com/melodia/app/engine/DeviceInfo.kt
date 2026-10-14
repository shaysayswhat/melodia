package com.melodia.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.io.File

class DeviceInfo(private val context: Context) {

    val totalRamBytes: Long by lazy {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    }

    val isArm64: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    /** The RAM size printed on the box (8, 12, 16 GB…), from what Android reports. */
    val ramClassGb: Int by lazy {
        val gib = totalRamBytes / (1024.0 * 1024.0 * 1024.0)
        listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 24, 32).firstOrNull { it >= gib } ?: gib.toInt()
    }

    fun availableRamBytes(): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)

    data class Verdict(val ok: Boolean, val message: String?)

    /** Can this phone run the on-device singer? */
    fun phoneVerdict(allowLowRam: Boolean): Verdict {
        if (!isArm64) return Verdict(false, "Phone AI needs a 64-bit ARM phone.")
        if (ramClassGb < 8 && !allowLowRam) {
            return Verdict(false, "Phone AI needs at least 8 GB of RAM; this phone has about $ramClassGb GB. Use PC AI instead, or turn on \"Try on phones with less RAM\" in Settings.")
        }
        if (ramClassGb < 12) {
            return Verdict(true, "Works on this phone, but expect slow songs. 12 GB+ RAM is smoother. Close other apps first.")
        }
        return Verdict(true, null)
    }

    fun thermalSevere(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    /** Battery percent and whether it is charging. */
    fun battery(): Pair<Int, Boolean> {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return 100 to true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level to bm.isCharging
    }
}
