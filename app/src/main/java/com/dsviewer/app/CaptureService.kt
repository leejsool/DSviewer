package com.dsviewer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.File
import kotlin.math.roundToInt

/**
 * 다른 앱 화면 가져오기: 화면 전송 허락을 받은 뒤 알림에 '이 화면 가져오기' 단추를 둔다.
 * 단추를 누르면 알림창이 닫힌 뒤의 화면을 찍어 상태 표시줄·내비게이션 줄을 잘라 내고 뷰어로 돌아가 새 쪽으로 넣게 한다.
 * (다른 앱 위에 단추를 띄우는 '다른 앱 위에 표시' 권한은 쓰지 않는다: 악성 앱으로 의심받는 권한이라)
 */
class CaptureService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("capture").apply { start() }
    private val bg = Handler(worker.looper)

    /** 가장 최근 화면 한 장 (bg 스레드에서만 만진다) */
    private var last: Image? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            finishCapture(null, cancelled = true)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOT) {
            capture(Rect(intent.getIntExtra("l", 0), intent.getIntExtra("t", 0), intent.getIntExtra("r", 0), intent.getIntExtra("b", 0)))
            return START_NOT_STICKY
        }
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (data == null || projection != null) return START_NOT_STICKY

        // 안드로이드 14부터: 화면 전송 서비스를 먼저 앞에 띄워야 MediaProjection을 받을 수 있다
        startForeground(NOTI_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mp = runCatching {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        }.getOrNull()
        if (mp == null) {
            toast("화면을 가져올 수 없습니다.")
            finishCapture(null)
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            // 사용자가 상태 표시줄 등에서 화면 전송을 멈춘 경우
            override fun onStop() {
                main.post { if (projection != null) finishCapture(null) }
            }
        }, main)
        val (w, h, dpi) = screenSize()
        val r = newReader(w, h)
        reader = r
        display = mp.createVirtualDisplay("DSViewerCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg)
        return START_NOT_STICKY
    }

    /** 화면을 돌리면 받는 크기도 바꾼다 (가로·세로가 뒤바뀐 채로 찍히지 않게) */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val d = display ?: return
        val (w, h, dpi) = screenSize()
        val old = reader
        if (old != null && old.width == w && old.height == h) return
        val r = newReader(w, h)
        reader = r
        d.resize(w, h, dpi)
        d.surface = r.surface
        bg.post {
            last?.close()
            last = null
            old?.close()
        }
    }

    override fun onDestroy() {
        release()
        worker.quitSafely()
        running = false
        super.onDestroy()
    }

    private fun newReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
        // 새 화면이 올 때마다 받아 두고 앞 것은 버린다 (안 받으면 화면 전송이 멈춘다)
        setOnImageAvailableListener({ ir ->
            val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (ir !== reader) { img.close(); return@setOnImageAvailableListener }
            last?.close()
            last = img
        }, bg)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        val b = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
        else Rect().also { wm.defaultDisplay.getRealMetrics(dm); it.set(0, 0, dm.widthPixels, dm.heightPixels) }
        return Triple(b.width(), b.height(), resources.displayMetrics.densityDpi)
    }

    // ================= 찍기 =================

    /**
     * 알림의 '이 화면 가져오기'를 누르면 [ShotActivity]가 알림창을 닫고 그때의 상태 표시줄·내비게이션 줄 두께([bars])를 넘긴다.
     * 알림창이 다 닫힌 뒤의 화면을 찍어 줄을 잘라 낸다
     */
    private fun capture(bars: Rect) {
        if (busy || projection == null) return
        busy = true
        val (sw, sh) = screenSize()
        bg.postDelayed({
            val file = runCatching {
                val img = last ?: error("화면을 받지 못했습니다.")
                val bmp = toBitmap(img)
                try {
                    // 받은 그림 크기가 화면과 다르면(돌리는 중 등) 비율대로 맞춘다
                    val kx = bmp.width.toFloat() / sw
                    val ky = bmp.height.toFloat() / sh
                    val l = (bars.left * kx).roundToInt()
                    val t = (bars.top * ky).roundToInt()
                    val r = bmp.width - (bars.right * kx).roundToInt()
                    val b = bmp.height - (bars.bottom * ky).roundToInt()
                    val crop = if (r - l > 16 && b - t > 16 && (l > 0 || t > 0 || r < bmp.width || b < bmp.height))
                        Bitmap.createBitmap(bmp, l, t, r - l, b - t) else bmp
                    val f = FileUtil.tempFile(this, "capture", "png")
                    f.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (crop !== bmp) crop.recycle()
                    f
                } finally {
                    bmp.recycle()
                }
            }
            main.post {
                file.onFailure { toast("화면을 가져오지 못했습니다.\n${it.message ?: it.javaClass.simpleName}") }
                finishCapture(file.getOrNull(), cancelled = file.isFailure)
            }
        }, SHOT_DELAY_MS)
    }

    /** RGBA 버퍼 → 비트맵 (줄 끝 여백을 떼어 낸다) */
    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val ps = plane.pixelStride
        val rowW = plane.rowStride / ps
        val full = Bitmap.createBitmap(rowW, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer.rewind())
        if (rowW == img.width) return full
        return Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
    }

    /** 화면 전송을 끝내고 뷰어로 돌아간다. [file]이 있으면 새 쪽으로 넣게 한다 */
    private fun finishCapture(file: File?, cancelled: Boolean = false) {
        release()
        if (file != null || cancelled) {
            val back = Intent(this, ViewerActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (file != null) back.putExtra(ViewerActivity.EXTRA_CAPTURE, file.path)
            runCatching { startActivity(back) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        display?.release()
        display = null
        projection?.let { p -> projection = null; runCatching { p.stop() } }
        val r = reader
        reader = null
        bg.post {
            last?.close()
            last = null
            r?.close()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // 알림창에서 단추가 바로 보이도록 기본 중요도 (소리는 내지 않는다)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "다른 앱 화면 가져오기", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val cancel = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        // 알림에서 활동을 띄워야 알림창이 저절로 닫힌다
        val shot = PendingIntent.getActivity(this, 1,
            Intent(this, ShotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("화면 가져오기 준비됨")
            .setContentText("가져올 화면을 띄우고 알림창에서 '이 화면 가져오기'를 누르세요.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(shot)
            .addAction(Notification.Action.Builder(null, "이 화면 가져오기", shot).build())
            .addAction(Notification.Action.Builder(null, "그만두기", cancel).build())
            .build()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        /** 예전 낮은 중요도 채널(capture)과 따로: 채널 중요도는 만든 뒤 바꿀 수 없다 */
        private const val CHANNEL = "capture_shot"
        private const val NOTI_ID = 7301
        private const val ACTION_CANCEL = "com.dsviewer.app.CAPTURE_CANCEL"
        private const val ACTION_SHOT = "com.dsviewer.app.CAPTURE_SHOT"
        /** 알림창이 접히고 그 아래 화면이 다시 전송될 때까지 기다리는 시간 */
        private const val SHOT_DELAY_MS = 700L
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        /** 서비스가 떠 있는 동안 true (뷰어에서 두 번 시작하지 않게) */
        @Volatile var running = false
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            running = true
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }

        /** [ShotActivity]에서: 상태 표시줄·내비게이션 줄 두께([bars])를 넘기며 찍게 한다 */
        fun shoot(ctx: Context, bars: Rect) {
            if (!running) return
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_SHOT)
                .putExtra("l", bars.left).putExtra("t", bars.top).putExtra("r", bars.right).putExtra("b", bars.bottom))
        }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
