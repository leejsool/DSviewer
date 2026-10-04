package com.dsviewer.app

import android.content.DialogInterface
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 쪽 범위 입력: [ n ] 쪽부터 [ m ] 쪽까지 (처음 값은 [first]~[last], 0부터 센 쪽).
 * [check]가 문구를 돌려주면 그 문구를 띄우고 창을 닫지 않는다. [onOk]는 1부터 센 n, m을 받는다
 */
internal fun askPageRange(
    activity: AppCompatActivity, count: Int, first: Int, last: Int, title: String, okLabel: String,
    check: ((Int, Int) -> String?)? = null, onOk: (Int, Int) -> Unit,
) {
    val d = activity.resources.displayMetrics.density
    fun numberField(value: Int, action: Int) = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = action
        isSingleLine = true
        gravity = android.view.Gravity.CENTER
        minEms = 3
        setText("$value")
        setSelectAllOnFocus(true)
    }
    fun label(text: String) = TextView(activity).apply {
        this.text = text
        setPadding((6 * d).toInt(), 0, (14 * d).toInt(), 0)
    }
    val from = numberField(first + 1, EditorInfo.IME_ACTION_NEXT)
    val to = numberField(last + 1, EditorInfo.IME_ACTION_DONE)
    val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
        addView(from)
        addView(label("쪽부터"))
        addView(to)
        addView(label("쪽까지"))
    }
    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setMessage("전체 ${count}쪽")
        .setView(row)
        .setPositiveButton(okLabel, null)
        .setNegativeButton("취소", null)
        .create()
    fun go() {
        val n = from.text.toString().toIntOrNull()
        val m = to.text.toString().toIntOrNull()
        when {
            n == null || n !in 1..count -> from.error = "1부터 ${count} 사이로 입력해 주세요"
            m == null || m !in 1..count -> to.error = "1부터 ${count} 사이로 입력해 주세요"
            n > m -> to.error = "시작 쪽(${n}쪽)보다 작을 수 없습니다"
            else -> {
                val problem = check?.invoke(n, m)
                if (problem != null) {
                    to.error = problem
                    return
                }
                dialog.dismiss()
                onOk(n, m)
            }
        }
    }
    // 실물 키보드 Enter나 화면 키보드 '완료'로 바로 실행
    to.setOnEditorActionListener { _, id, ev ->
        val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
        if (id == EditorInfo.IME_ACTION_DONE || enter) { go(); true } else false
    }
    dialog.setOnShowListener {
        // 잘못된 번호면 창을 닫지 않는다
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { go() }
        from.requestFocus()
        dialog.window?.let { WindowCompat.getInsetsController(it, from).show(WindowInsetsCompat.Type.ime()) }
    }
    dialog.show()
}
