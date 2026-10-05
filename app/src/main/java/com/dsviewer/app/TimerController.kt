package com.dsviewer.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 시험·풀이 타이머. ⋮ 메뉴의 '타이머'로 시작하면 문서 위에 작은 칩이 떠서 시간을 보여 준다
 * (끌어서 옮기고, 시간을 누르면 기록·조절 창). 탭을 바꿔도 칩은 그대로다.
 *
 * - 시험: 정한 시간에서 줄어든다. 10분·1분 남았을 때와 시간이 다 됐을 때 알리고, 다 된 뒤에도 초과 시간을 센다.
 * - 풀이: 0에서 늘어나는 초시계.
 * - 둘 다 '다음 문제' 단추로 문제별 풀이 시간을 적을 수 있다.
 *
 * 타이머가 도는 동안 화면을 켜 둔다. 상태는 설정에 적어 두므로 뷰어를 닫았다 열어도 이어지고,
 * 앱이 뒤에 가 있어도 시간은 흐른다 (알림은 앱으로 돌아왔을 때 나온다).
 */
internal class TimerController(
    private val activity: AppCompatActivity,
    private val frame: FrameLayout,
    private val prefs: SharedPreferences,
    private val toast: (String) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val handler = Handler(Looper.getMainLooper())

    private var timer: StudyTimer? = null
    private var chip: TimerChip? = null
    private lateinit var timeText: TextView
    private lateinit var subText: TextView
    private lateinit var pauseButton: ImageButton
    private lateinit var nextButton: ImageButton

    /** 기록 창이 떠 있는 동안 그 안의 글을 같이 갱신하는 함수 */
    private var dialogRefresh: (() -> Unit)? = null
    private var timeUpDialog: AlertDialog? = null
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            refresh()
            scheduleTick()
        }
    }

    // ================= 생명주기 =================

    /** 뷰어를 열 때: 지난번에 돌던 타이머가 있으면 칩을 다시 띄운다 */
    fun restore() {
        val t = StudyTimer.parse(prefs.getString(KEY_STATE, null))
        if (t == null || !t.started || t.elapsed(System.currentTimeMillis()) > STALE_MS) {
            prefs.edit().remove(KEY_STATE).apply()
            return
        }
        timer = t
        showChip()
        scheduleTick()
    }

    fun onResume() {
        if (timer != null) {
            refresh()
            scheduleTick()
        }
    }

    fun onPause() {
        handler.removeCallbacks(tick)
        ticking = false
        save()
    }

    // ================= 메뉴에서 =================

    /** ⋮ 메뉴 '타이머': 없으면 새로 정하는 창, 돌고 있으면 기록·조절 창 */
    fun open() {
        if (timer == null) showSetup() else showControl()
    }

    // ================= 새로 시작 =================

    private fun showSetup() {
        var countdown = true
        val minutes = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "직접 입력 (분)"
            setText(prefs.getInt(KEY_MINUTES, 60).toString())
            setSelectAllOnFocus(true)
            maxLines = 1
        }
        val laps = CheckBox(activity).apply {
            text = "문제별 풀이 시간 기록 (‘다음 문제’ 단추)"
            isChecked = prefs.getBoolean(KEY_LAPS, true)
        }
        val minutesBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply { text = "시험 시간"; textSize = 13f })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                for (m in StudyTimer.PRESET_MINUTES) {
                    addView(smallButton("${m}분") { minutes.setText(m.toString()) }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) }
                    })
                }
            })
            addView(minutes)
        }
        val modes = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            val a = modeButton("시험 (남은 시간)")
            val b = modeButton("풀이 (경과 시간)")
            addView(a)
            addView(b)
            check(a.id)
            addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                countdown = id == a.id
                minutesBox.visibility = if (countdown) View.VISIBLE else View.GONE
            }
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(modes, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            addView(minutesBox)
            addView(laps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("타이머")
            .setView(body)
            .setPositiveButton("시작", null)
            .setNegativeButton("취소", null)
            .show()
        // 시간을 잘못 쳤을 때 창이 닫히지 않게 직접 처리한다
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var target = 0L
            if (countdown) {
                val m = StudyTimer.parseMinutes(minutes.text.toString())
                if (m == null) {
                    toast("시험 시간을 분 단위 숫자로 적어 주세요")
                    return@setOnClickListener
                }
                target = m * 60_000L
                prefs.edit().putInt(KEY_MINUTES, m).apply()
            }
            prefs.edit().putBoolean(KEY_LAPS, laps.isChecked).apply()
            start(StudyTimer(if (countdown) TimerMode.COUNTDOWN else TimerMode.STOPWATCH, target, laps.isChecked))
            dialog.dismiss()
        }
    }

    private fun start(t: StudyTimer) {
        t.start(System.currentTimeMillis())
        timer = t
        showChip()
        refresh()
        scheduleTick()
        save()
        toast(if (t.mode == TimerMode.COUNTDOWN) "시험 타이머를 시작했어요" else "풀이 시간을 재기 시작했어요")
    }

    // ================= 칩 =================

    private fun showChip() {
        val t = timer ?: return
        if (chip == null) {
            val c = TimerChip()
            timeText = TextView(activity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                fontFeatureSettings = "tnum"  // 숫자 폭을 같게 해서 시간이 바뀌어도 칩이 흔들리지 않게
            }
            subText = TextView(activity).apply {
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                fontFeatureSettings = "tnum"
            }
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
                addView(timeText)
                addView(subText)
                setOnClickListener { showControl() }
            }
            pauseButton = iconButton(R.drawable.ic_timer_play, "일시정지·계속") { timer?.let { it.toggle(System.currentTimeMillis()); changed() } }
            nextButton = iconButton(R.drawable.ic_timer_next, "다음 문제") { nextProblem() }
            c.addView(texts)
            c.addView(pauseButton)
            c.addView(nextButton)
            frame.addView(c, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(16)
                topMargin = dp(12)
            })
            chip = c
        }
        nextButton.visibility = if (t.trackLaps) View.VISIBLE else View.GONE
        chip?.visibility = View.VISIBLE
        refresh()
    }

    private fun hideChip() {
        chip?.let { frame.removeView(it) }
        chip = null
        handler.removeCallbacks(tick)
        ticking = false
    }

    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = desc
        val out = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        setBackgroundResource(out.resourceId)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    /** 칩. 끌어서 옮기고, 안의 단추는 그대로 눌린다 */
    @SuppressLint("ViewConstructor")
    private inner class TimerChip : LinearLayout(activity) {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var startTy = 0f
        private var dragging = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(COLOR_NORMAL) }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(e)
                MotionEvent.ACTION_MOVE -> if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(e)
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
                    if (dragging) moveTo(startTx + e.rawX - downX, startTy + e.rawY - downY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        private fun begin(e: MotionEvent) {
            downX = e.rawX
            downY = e.rawY
            startTx = translationX
            startTy = translationY
            dragging = false
        }

        /** 문서 칸 밖으로 나가지 않게 (오른쪽 위 모서리가 기준 자리) */
        private fun moveTo(tx: Float, ty: Float) {
            val lp = layoutParams as FrameLayout.LayoutParams
            translationX = tx.coerceIn(-(frame.width - width - lp.marginEnd - dp(4)).toFloat().coerceAtLeast(0f), lp.marginEnd.toFloat())
            translationY = ty.coerceIn(-lp.topMargin.toFloat(), (frame.height - height - lp.topMargin - dp(4)).toFloat().coerceAtLeast(0f))
        }
    }

    // ================= 갱신 =================

    private fun scheduleTick() {
        if (ticking || timer == null) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    /** 칩의 글·색을 지금 시각으로 고치고, 알릴 때가 됐으면 알린다 */
    private fun refresh() {
        val t = timer ?: return
        val n = System.currentTimeMillis()
        val countdown = t.mode == TimerMode.COUNTDOWN
        timeText.text = TimerFormat.clock(t.shown(n), countdown)
        val over = countdown && t.remaining(n) <= 0
        subText.text = when {
            !t.isRunning -> "일시정지"
            over -> "시간 초과"
            t.trackLaps -> "${t.currentProblem}번 · ${TimerFormat.clock(t.currentLap(n), false)}"
            countdown -> "남은 시간"
            else -> "풀이 시간"
        }
        pauseButton.setImageResource(if (t.isRunning) R.drawable.ic_timer_pause else R.drawable.ic_timer_play)
        val color = when {
            !t.isRunning -> COLOR_PAUSED
            t.urgency(n) == TimerUrgency.WARN -> COLOR_WARN
            t.urgency(n) == TimerUrgency.DANGER || t.urgency(n) == TimerUrgency.OVER -> COLOR_DANGER
            else -> COLOR_NORMAL
        }
        ((chip?.background) as? GradientDrawable)?.setColor(color)
        chip?.keepScreenOn = t.isRunning
        dialogRefresh?.invoke()
        when (t.pollNotice(n)) {
            TimerNotice.TEN_MINUTES -> { toast("10분 남았습니다"); buzz(false); save() }
            TimerNotice.ONE_MINUTE -> { toast("1분 남았습니다"); buzz(false); save() }
            TimerNotice.TIME_UP -> { save(); timeUp() }
            null -> {}
        }
    }

    /** 사용자가 단추로 상태를 바꾼 뒤 */
    private fun changed() {
        save()
        refresh()
    }

    private fun save() {
        val t = timer
        val e = prefs.edit()
        if (t == null) e.remove(KEY_STATE) else e.putString(KEY_STATE, t.serialize())
        e.apply()
    }

    private fun nextProblem() {
        val t = timer ?: return
        val n = System.currentTimeMillis()
        val no = t.currentProblem
        val took = t.lap(n) ?: return
        toast("${no}번 ${TimerFormat.clock(took, false)}")
        changed()
    }

    // ================= 시간 종료 =================

    private fun timeUp() {
        buzz(true)
        if (timeUpDialog?.isShowing == true) return
        timeUpDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("시간이 끝났습니다")
            .setMessage("계속 풀면 초과 시간이 세어집니다.")
            .setPositiveButton("계속 풀기", null)
            .setNeutralButton("5분 연장") { _, _ -> extend(5) }
            .setNegativeButton("끝내기") { _, _ -> finishTimer() }
            .show()
    }

    private fun extend(minutes: Int) {
        val t = timer ?: return
        t.extend(minutes * 60_000L, System.currentTimeMillis())
        toast("${minutes}분 연장했습니다")
        changed()
    }

    /** 진동 + (시간 종료일 때) 알람 소리. 권한·기기가 없어도 조용히 넘어간다 */
    private fun buzz(long: Boolean) {
        try {
            val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = if (long) longArrayOf(0, 500, 200, 500, 200, 500) else longArrayOf(0, 250)
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: RuntimeException) {
        }
        if (!long) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
            handler.postDelayed({ tone.release() }, 1200)
        } catch (_: RuntimeException) {
        }
    }

    // ================= 기록·조절 창 =================

    private fun showControl() {
        val t = timer ?: return
        val countdown = t.mode == TimerMode.COUNTDOWN
        val big = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        val state = TextView(activity).apply { gravity = Gravity.CENTER; textSize = 13f }
        val list = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setLineSpacing(0f, 1.15f)
        }
        val scroll = ScrollView(activity).apply { addView(list) }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val toggle = smallButton("") { t.toggle(System.currentTimeMillis()); changed() }
        actions.addView(toggle)
        if (countdown) actions.addView(smallButton("+5분") { extend(5) })
        if (t.trackLaps) {
            actions.addView(smallButton("직전 문제 취소") {
                if (t.undoLap()) changed() else toast("취소할 기록이 없어요")
            })
        }

        fun fill() {
            val n = System.currentTimeMillis()
            big.text = TimerFormat.clock(t.shown(n), countdown)
            state.text = buildString {
                append(if (countdown) "시험 · 주어진 시간 ${TimerFormat.clock(t.targetMs, false)}" else "풀이")
                append(" · 경과 ${TimerFormat.clock(t.elapsed(n), false)}")
                if (!t.isRunning) append(" · 일시정지")
            }
            toggle.text = if (t.isRunning) "일시정지" else "계속"
            list.text = lapText(t, n)
        }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(big)
            addView(state, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            if (t.trackLaps) addView(scroll, LinearLayout.LayoutParams(-1, dp(220)))
        }
        fill()
        dialogRefresh = ::fill
        // 문제별 기록이 아래로 쌓이므로 열 때 맨 아래(지금 푸는 문제)가 보이게
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        MaterialAlertDialogBuilder(activity)
            .setTitle("타이머")
            .setView(body)
            .setPositiveButton("닫기", null)
            .setNeutralButton("기록 복사") { _, _ -> copyReport(t) }
            .setNegativeButton("끝내기") { _, _ -> finishTimer() }
            .setOnDismissListener { dialogRefresh = null }
            .show()
    }

    /** 문제별 시간 목록 (맨 아래가 지금 푸는 문제) */
    private fun lapText(t: StudyTimer, now: Long): String {
        if (!t.trackLaps) return ""
        val sb = StringBuilder()
        for ((i, ms) in t.laps.withIndex()) sb.append("%2d번  %s\n".format(i + 1, TimerFormat.clock(ms, false)))
        sb.append("%2d번  %s  ◀ 푸는 중\n".format(t.currentProblem, TimerFormat.clock(t.currentLap(now), false)))
        TimerFormat.lapSummary(t.laps)?.let {
            sb.append("\n평균 ${TimerFormat.clock(it.averageMs, false)}")
            sb.append(" · 가장 오래 ${it.longestProblem}번 · 가장 빨리 ${it.shortestProblem}번")
        }
        return sb.toString()
    }

    private fun copyReport(t: StudyTimer) {
        val text = TimerFormat.report(t.mode, t.elapsed(System.currentTimeMillis()), t.targetMs, t.laps)
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("타이머 기록", text))
        toast("기록을 복사했어요")
    }

    /** 타이머를 끝낸다. 문제별 기록이 있으면 마지막으로 한 번 보여 준다 */
    private fun finishTimer() {
        val t = timer ?: return
        val n = System.currentTimeMillis()
        // 마지막 문제도 기록에 넣는다 (달리는 중이었고 시간이 조금이라도 지났으면)
        if (t.trackLaps && t.isRunning && t.currentLap(n) > 0) t.lap(n)
        val total = t.elapsed(n)
        val report = TimerFormat.report(t.mode, total, t.targetMs, t.laps)
        val hasLaps = t.laps.isNotEmpty()
        timer = null
        timeUpDialog?.dismiss()
        hideChip()
        save()
        if (!hasLaps) {
            toast("타이머를 끝냈어요 (${TimerFormat.clock(total, false)})")
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("타이머 기록")
            .setMessage(report)
            .setPositiveButton("닫기", null)
            .setNeutralButton("복사") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("타이머 기록", report))
                toast("기록을 복사했어요")
            }
            .show()
    }

    // ================= 작은 부품 =================

    private fun smallButton(text: String, onClick: () -> Unit) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun modeButton(text: String) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            this.text = text
            isAllCaps = false
            textSize = 14f
        }

    companion object {
        private const val KEY_STATE = "timerState"
        private const val KEY_MINUTES = "timerMinutes"
        private const val KEY_LAPS = "timerLaps"
        private const val TICK_MS = 250L

        /** 이보다 오래 돈 타이머는 잊고 간 것으로 보고 다시 열 때 버린다 */
        private const val STALE_MS = 24 * 60 * 60_000L

        private val COLOR_NORMAL = 0xE6263238.toInt()
        private val COLOR_PAUSED = 0xE6616161.toInt()
        private val COLOR_WARN = 0xF2E65100.toInt()
        private val COLOR_DANGER = 0xF2C62828.toInt()
    }
}
