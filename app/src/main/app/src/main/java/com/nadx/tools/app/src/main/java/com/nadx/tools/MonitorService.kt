package com.nadx.tools

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class MonitorService : Service() {
    private var wm: WindowManager? = null
    private var tv: TextView? = null
    @Volatile private var running = false
    @Volatile private var pkg = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        pkg = i?.getStringExtra("pkg") ?: ""
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("nadx", "NadX Monitor", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            1,
            Notification.Builder(this, "nadx")
                .setContentTitle("ROG Monitoring aktif")
                .setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        )
        if (tv == null) addOverlay()
        if (!running) {
            running = true
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    private fun addOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f
            setBackgroundColor(0x99000000.toInt()); setPadding(16, 8, 16, 8)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 20 }
        wm?.addView(tv, lp)
    }

    private fun loop() {
        val cpu = CpuMeter()
        var layer: String? = null
        while (running) {
            val c = cpu.read()
            val (rp, _, _) = ramInfo(this)
            val (bl, bt) = battInfo(this)
            var f = -1
            if (pkg.isNotEmpty()) {
                if (layer == null) {
                    val l = Sh.run("dumpsys SurfaceFlinger --list").lines()
                    layer = l.firstOrNull { it.contains(pkg) && it.contains("SurfaceView") }
                        ?: l.firstOrNull { it.contains(pkg) }
                }
                f = layer?.let { Sh.fps(it) } ?: -1
                if (f < 0) layer = null
            }
            val cs = if (c < 0) "-" else "$c"
            val fs = if (f < 0) "-" else "$f"
            val t = "CPU $cs%  RAM $rp%\nFPS $fs  BAT $bl%  ${"%.1f".format(bt)}C"
            tv?.post { tv?.text = t }
            Thread.sleep(2000)
        }
    }

    override fun onDestroy() {
        running = false
        try { wm?.removeView(tv) } catch (e: Exception) {}
        tv = null
        super.onDestroy()
    }
}
