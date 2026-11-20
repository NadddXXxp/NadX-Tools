package com.nadx.tools

import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.os.BatteryManager
import rikka.shizuku.Shizuku

fun prefs(c: Context): SharedPreferences =
    c.getSharedPreferences("nadx", Context.MODE_PRIVATE)

object Sh {
    fun ready(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun run(cmd: String): String = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) { "" }

    fun fps(layer: String): Int {
        val ts = run("dumpsys SurfaceFlinger --latency '$layer'").lines().drop(1)
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(2)?.toLongOrNull() }
            .filter { it > 0 && it < Long.MAX_VALUE }
        if (ts.size < 2) return -1
        val last = ts.max()
        return ts.count { it > last - 1_000_000_000L }
    }
}

class CpuMeter {
    private var pt = 0L
    private var pi = 0L
    fun read(): Int {
        val n = Sh.run("head -1 /proc/stat").trim().split(Regex("\\s+"))
            .drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 5) return -1
        val idle = n[3] + n[4]
        val tot = n.sum()
        val first = pt == 0L
        val dt = tot - pt
        val di = idle - pi
        pt = tot; pi = idle
        return if (first || dt <= 0) -1 else (100 * (dt - di) / dt).toInt()
    }
}

fun ramInfo(c: Context): Triple<Int, Double, Double> {
    val mi = ActivityManager.MemoryInfo()
    (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
    val tot = mi.totalMem / 1e9
    val used = (mi.totalMem - mi.availMem) / 1e9
    return Triple((100 * used / tot).toInt(), used, tot)
}

fun battInfo(c: Context): Pair<Int, Float> {
    val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    return l to t
}

data class AppItem(val name: String, val pkg: String)

fun getApps(c: Context): List<AppItem> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
        .sortedBy { it.name.lowercase() }
}

val ANIM = listOf("0.8", "0.6", "0.4", "0.2", "0")
val LP = listOf(350, 300, 260, 220, 180)

fun sensUpperOn(level: Int) {
    val i = level.coerceIn(1, 5) - 1
    Sh.run(listOf(
        "settings put global window_animation_scale ${ANIM[i]}",
        "settings put global transition_animation_scale ${ANIM[i]}",
        "settings put global animator_duration_scale ${ANIM[i]}",
        "settings put secure long_press_timeout ${LP[i]}"
    ).joinToString(";"))
}

fun sensUpperOff() {
    Sh.run(listOf(
        "settings put global window_animation_scale 1.0",
        "settings put global transition_animation_scale 1.0",
        "settings put global animator_duration_scale 1.0",
        "settings delete secure long_press_timeout"
    ).joinToString(";"))
}

fun focusMode(on: Boolean) {
    Sh.run("cmd notification set_dnd ${if (on) "priority" else "off"}")
}

fun turboMode(on: Boolean, pkgs: Collection<String>) {
    val mode = if (on) "performance" else "standard"
    if (on) Sh.run("settings put global low_power 0")
    pkgs.forEach { Sh.run("cmd game mode $mode $it") }
}

fun stayAwake(on: Boolean) {
    Sh.run("settings put global stay_on_while_plugged_in ${if (on) 7 else 0}")
}

fun cleanRam(c: Context, keep: Set<String>): String {
    val before = ramInfo(c).second
    val pkgs = Sh.run("pm list packages -3").lines()
        .map { it.removePrefix("package:").trim() }
        .filter {
            it.isNotEmpty() && it != c.packageName && it !in keep &&
                !it.contains("shizuku") && !it.contains("inputmethod") && !it.contains("keyboard")
        }
    Sh.run(pkgs.joinToString(";") { "am force-stop $it" })
    Thread.sleep(1000)
    val after = ramInfo(c).second
    return "${pkgs.size} app dihentikan. RAM terpakai " +
        "%.1f GB menjadi %.1f GB".format(before, after)
}
