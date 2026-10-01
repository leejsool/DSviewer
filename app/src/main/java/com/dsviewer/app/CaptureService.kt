package com.dsviewer.app

import android.annotation.SuppressLint
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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 다른 앱 화면 가져오기: 화면 전송 허락을 받은 뒤 다른 앱 위에 떠 있는 캡처 단추를 띄운다.
 * 단추를 누르면 그 순간의 화면을 찍어 상태 표시줄·내비게이션 줄을 잘라 내고 뷰어로 돌아가 새 쪽으로 넣게 한다.
 */
class CaptureService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: View? = null
    /** 화면 전체를 덮는 투명한 창: 상태 표시줄·내비게이션 줄의 두께를 읽는 데만 쓴다 (터치는 통과) */
    private var probe: View? = null
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
            finishCapture(null)
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
        showOverlay()
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
        removeOverlay()
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

    // ================= 떠 있는 단추 =================

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).roundToInt()
        val size = dp(56f)

        fun round(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2f), Color.WHITE)
        }
        val shot = ImageView(this).apply {
            setImageResource(R.drawable.ic_screen_capture)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = round(getColor(R.color.brand))
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            contentDescription = "이 화면 가져오기"
            elevation = dp(6f).toFloat()
        }
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = round(0xCC555555.toInt())
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            contentDescription = "가져오기 그만두기"
            elevation = dp(6f).toFloat()
            setOnClickListener { finishCapture(null, cancelled = true) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            addView(close, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { bottomMargin = dp(8f) })
            addView(shot, LinearLayout.LayoutParams(size, size))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (w, h) = screenSize()
            x = w - size - dp(28f)
            y = h / 2 - size
        }

        // 끌어서 옮기고, 거의 안 움직이고 떼면 찍는다
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        shot.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(8f) || abs(dy) > dp(8f)) {
                        moved = true
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(box, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) capture(box)
            }
            true
        }
        runCatching { wm.addView(box, lp) }.onFailure {
            toast("다른 앱 위에 단추를 띄울 수 없습니다.")
            finishCapture(null)
            return
        }
        overlay = box
        addProbe()
    }

    /**
     * 줄 두께는 그 줄과 겹치는 창에만 알려지므로, 작은 단추 창 대신 화면 전체 크기의 빈 창에서 읽는다.
     * 줄을 피해 작아지지 않도록 fitInsetsTypes를 비운다
     */
    private fun addProbe() {
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) probe = v
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeViewImmediate(it) } }
        overlay = null
        probe?.let { runCatching { wm.removeViewImmediate(it) } }
        probe = null
    }

    /**
     * 지금 보이는 상태 표시줄·내비게이션 줄(태블릿의 작업 표시줄 포함)·카메라 홈의 두께
     * (전체 화면 앱이라 숨어 있으면 0)
     */
    private fun barInsets(): Rect {
        val wi = probe?.rootWindowInsets
        if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or
                WindowInsets.Type.tappableElement() or WindowInsets.Type.displayCutout()
            // 빈 창을 못 띄웠으면 화면 크기 정보의 두께를 쓴다
            val i = wi?.getInsets(types) ?: wm.currentWindowMetrics.windowInsets.getInsets(types)
            return Rect(i.left, i.top, i.right, i.bottom).also { Log.d("CaptureService", "bars $it (probe=${wi != null})") }
        }
        wi ?: return Rect()
        @Suppress("DEPRECATION")
        return Rect(wi.systemWindowInsetLeft, wi.systemWindowInsetTop, wi.systemWindowInsetRight, wi.systemWindowInsetBottom)
    }

    // ================= 찍기 =================

    private fun capture(box: View) {
        if (busy) return
        busy = true
        val bars = barInsets()
        val (sw, sh) = screenSize()
        // 단추가 찍히지 않게 숨기고, 숨긴 화면이 전송될 때까지 잠깐 기다린다
        box.visibility = View.INVISIBLE
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
        }, 350)
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
        removeOverlay()
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "다른 앱 화면 가져오기", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_screen_capture)
            .setContentTitle("화면 가져오기 준비됨")
            .setContentText("가져올 화면에서 떠 있는 단추를 누르세요.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "그만두기", cancel).build())
            .build()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTI_ID = 7301
        private const val ACTION_CANCEL = "com.dsviewer.app.CAPTURE_CANCEL"
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

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
