package com.sfxreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * خدمة بالخلفية: تلتقط الشاشة كل ~0.6 ثانية، تقرأ النصوص، وتشغّل صوت المؤثر
 * أول ما يظهر على الشاشة (فوق أي تطبيق قراءة).
 */
class LiveService : Service() {

    companion object {
        val running = MutableStateFlow(false)
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANG = "lang"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "live"
        private const val REPLAY_GAP_MS = 2500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var recognizer: TextRecognizer? = null
    private var player: SfxPlayer? = null
    private var stopping = false
    private val lastSeen = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running.value) return START_NOT_STICKY

        startForegroundNow()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val lang = runCatching { Lang.valueOf(intent?.getStringExtra(EXTRA_LANG) ?: "") }.getOrDefault(Lang.KOREAN)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        // أندرويد 14 يشترط تسجيل callback قبل إنشاء VirtualDisplay
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val dm = resources.displayMetrics
        val scale = minOf(1f, 1000f / dm.widthPixels)
        val w = (dm.widthPixels * scale).toInt()
        val h = (dm.heightPixels * scale).toInt()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "sfx-capture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        recognizer = lang.recognizer()
        player = SfxPlayer(this)
        running.value = true
        job = scope.launch { loop() }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        var prev: IntArray? = null
        while (scope.isActive) {
            delay(600)
            val bmp = grab() ?: continue
            try {
                val sig = signature(bmp)
                val p = prev
                if (p != null && diff(p, sig) < 2.0) continue // الشاشة ما تغيرت
                prev = sig
                process(bmp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // نتجاوز الإطار الفاشل
            } finally {
                bmp.recycle()
            }
        }
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val rowWidth = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowWidth, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (rowWidth != img.width) {
                Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
            } else full
        } finally {
            img.close()
        }
    }

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 16, 32, false)
        val px = IntArray(16 * 32)
        s.getPixels(px, 0, 16, 0, 0, 16, 32)
        if (s !== b) s.recycle()
        return IntArray(px.size) { (px[it] shr 8) and 0xFF }
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var t = 0L
        for (i in a.indices) t += abs(a[i] - b[i])
        return t.toDouble() / a.size
    }

    private suspend fun process(bmp: Bitmap) {
        val rec = recognizer ?: return
        val res = rec.process(InputImage.fromBitmap(bmp, 0)).await()
        val found = HashMap<String, String>() // key -> category
        for (block in res.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val ratio = box.centerY().toFloat() / bmp.height
            if (ratio < 0.08f || ratio > 0.92f) continue
            val cat = SfxDictionary.match(line.text) ?: continue
            val key = cat + ":" + line.text.lowercase().filter { it.isLetterOrDigit() }
            found[key] = cat
        }
        val now = SystemClock.elapsedRealtime()
        for ((key, cat) in found) {
            val seen = lastSeen[key]
            lastSeen[key] = now
            if (seen == null || now - seen > REPLAY_GAP_MS) player?.play(cat)
        }
        if (lastSeen.size > 200) lastSeen.entries.removeAll { now - it.value > 60_000 }
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "الوضع المباشر", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("المؤثرات الصوتية شغّالة")
            .setContentText("افتح تطبيق المانهوا واقرأ")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, n)
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            job?.cancel()
            scope.cancel()
            display?.release()
            reader?.close()
            projection?.stop()
            recognizer?.close()
            player?.release()
        }
        running.value = false
        super.onDestroy()
    }
}
