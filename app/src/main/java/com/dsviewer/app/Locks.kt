package com.dsviewer.app

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 문서 잠금 (삼성노트처럼 앱 안의 잠금): 앱 전체에 암호 하나, 잠근 문서는 썸네일을 가리고 열 때 암호를 묻는다.
 * 파일 자체를 암호화하지는 않는다 (다른 앱에서는 그대로 열림)
 */
object Locks {
    private const val PREFS = "locks"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun locked(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString("uris", null) ?: return emptySet()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    }

    fun isLocked(ctx: Context, uri: String) = uri in locked(ctx)

    fun setLocked(ctx: Context, uris: Collection<String>, lock: Boolean) =
        save(ctx, if (lock) locked(ctx) + uris else locked(ctx) - uris.toSet())

    fun forget(ctx: Context, uri: String) = save(ctx, locked(ctx) - uri)

    fun relink(ctx: Context, old: String, new: String) {
        val s = locked(ctx)
        if (old in s) save(ctx, s - old + new)
    }

    private fun save(ctx: Context, s: Set<String>) {
        val a = JSONArray()
        s.forEach { a.put(it) }
        prefs(ctx).edit().putString("uris", a.toString()).apply()
    }

    fun hasPassword(ctx: Context) = prefs(ctx).getString("hash", null) != null

    fun check(ctx: Context, pw: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("salt", null) ?: return false
        return hash(salt, pw) == p.getString("hash", null)
    }

    fun setPassword(ctx: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("salt", salt).putString("hash", hash(salt, pw)).apply()
    }

    private fun hash(salt: String, pw: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pw).toByteArray()).joinToString("") { "%02x".format(it) }

    // ================= 암호 창 =================

    private fun field(a: Activity, hint: String): Pair<TextInputLayout, EditText> {
        val layout = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        layout.addView(edit)
        return layout to edit
    }

    private fun box(a: Activity, vararg views: TextInputLayout) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val d = a.resources.displayMetrics.density
        setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = (8 * d).toInt()
            })
        }
    }

    /** 확인 단추를 눌러도 [ok]가 false면 창을 닫지 않는다 */
    private fun showChecked(dialog: androidx.appcompat.app.AlertDialog, last: EditText, ok: () -> Boolean) {
        dialog.setOnShowListener {
            val b = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            b.setOnClickListener { if (ok()) dialog.dismiss() }
            last.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { b.performClick(); true } else false
            }
            last.requestFocus()
        }
        dialog.show()
    }

    /** 새 암호 정하기 (두 번 입력) */
    fun askNewPassword(a: Activity, title: String, onDone: () -> Unit) {
        val (l1, e1) = field(a, "새 암호 (4자 이상)")
        val (l2, e2) = field(a, "한 번 더")
        e1.imeOptions = EditorInfo.IME_ACTION_NEXT
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setMessage("잠근 문서를 열 때 쓰는 암호입니다. 잊으면 되찾을 수 없으니 꼭 기억해 두세요.")
            .setView(box(a, l1, l2))
            .setPositiveButton("확인", null)
            .setNegativeButton("취소", null)
            .create()
        showChecked(dialog, e2) {
            val p1 = e1.text.toString()
            val p2 = e2.text.toString()
            l1.error = null; l2.error = null
            when {
                p1.length < 4 -> { l1.error = "4자 이상으로 정해 주세요"; false }
                p1 != p2 -> { l2.error = "두 암호가 다릅니다"; false }
                else -> { setPassword(a, p1); onDone(); true }
            }
        }
    }

    /** 암호 확인 뒤 [onOk] */
    fun askPassword(a: Activity, title: String, onOk: () -> Unit) {
        val (l, e) = field(a, "암호")
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setView(box(a, l))
            .setPositiveButton("확인", null)
            .setNegativeButton("취소", null)
            .create()
        showChecked(dialog, e) {
            if (check(a, e.text.toString())) { onOk(); true }
            else { l.error = "암호가 틀렸습니다"; false }
        }
    }

    fun changePassword(a: Activity) {
        if (!hasPassword(a)) askNewPassword(a, "잠금 암호 정하기") {}
        else askPassword(a, "지금 암호를 넣어 주세요") { askNewPassword(a, "새 잠금 암호") {} }
    }
}
