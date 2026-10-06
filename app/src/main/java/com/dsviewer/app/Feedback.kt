package com.dsviewer.app

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 건의·오류 신고를 받는 곳: 구글 설문(Form)의 '응답 제출 주소'. 보내는 사람은 로그인도 메일 앱도 필요 없다.
 * 설문에는 짧은 답변 칸 네 개(종류 · 내용 · 연락처 · 앱·기기 정보)를 만들고, 각 칸의 entry 번호를 아래에 적는다.
 * [FORM_ID]가 비어 있으면 아직 받는 곳이 없는 것이라 '보내기'가 꺼진다.
 */
internal object FeedbackConfig {
    /** 설문 주소 https://docs.google.com/forms/d/e/<여기>/viewform 의 가운데 부분 */
    const val FORM_ID = ""
    const val ENTRY_KIND = "entry.0"
    const val ENTRY_MESSAGE = "entry.0"
    const val ENTRY_CONTACT = "entry.0"
    const val ENTRY_INFO = "entry.0"

    val ready get() = FORM_ID.isNotBlank()
}

/** 보낼 글을 설문 제출 형식(x-www-form-urlencoded)으로 만드는 순수 계산 */
internal object FeedbackForm {
    const val KIND_IDEA = "기능 건의"
    const val KIND_BUG = "오류 신고"
    const val KIND_ETC = "기타"

    /** [fields] (칸 이름 → 값)를 제출 본문으로. 값이 비어 있어도 칸은 보낸다 */
    fun encode(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    /** 설문 제출 주소 */
    fun submitUrl(formId: String) = "https://docs.google.com/forms/d/e/$formId/formResponse"

    /** 앱 버전·기기 정보 한 줄 (문서 내용·파일 이름은 담지 않는다) */
    fun info(versionName: String, versionCode: Long, model: String, android: String, crash: String?): String =
        buildString {
            append("DSnote $versionName ($versionCode) / $model / Android $android")
            if (!crash.isNullOrBlank()) append("\n\n[최근 오류 기록]\n").append(crash.trim())
        }

    /** 너무 길면 설문 칸에 못 들어가므로 앞쪽만 */
    fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max) + "…"
}

/** 앱이 비정상 종료될 때 오류 기록을 파일에 남긴다 (오류 신고에 함께 보낼 수 있게). 다른 내용은 남기지 않는다 */
internal object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX = 6000

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = ctx.filesDir
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                val trace = android.util.Log.getStackTraceString(e)
                File(dir, FILE).writeText("$stamp\n${trace.take(MAX)}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}

/**
 * 건의·오류 신고 창. 종류를 고르고 글을 쓴 뒤 '보내기'만 누르면 된다 (앱이 직접 보낸다: 로그인·메일 앱 필요 없음).
 * 앱 버전·기기 정보와 (있으면) 최근 오류 기록을 함께 보낼지 고를 수 있고, 문서 내용·파일 이름은 보내지 않는다.
 */
class Feedback(private val activity: AppCompatActivity) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    fun show() {
        val crash = CrashLog.read(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val kinds = listOf(FeedbackForm.KIND_IDEA, FeedbackForm.KIND_BUG, FeedbackForm.KIND_ETC)
        val group = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        kinds.forEachIndexed { i, k ->
            group.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                text = k
                isChecked = i == (if (crash != null) 1 else 0)
            })
        }
        box.addView(group)
        val message = EditText(activity).apply {
            hint = "자세히 적어 주세요. (어느 화면에서 무엇을 눌렀더니 어떻게 됐는지, 어떤 기능이 있으면 좋겠는지)"
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val contact = EditText(activity).apply {
            hint = "답을 받고 싶으면 연락처 (메일·전화, 안 적어도 됩니다)"
            isSingleLine = true
        }
        box.addView(contact, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val withInfo = CheckBox(activity).apply {
            text = "앱 버전·기기 정보 함께 보내기"
            isChecked = true
        }
        box.addView(withInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val withCrash = CheckBox(activity).apply {
            text = "최근 오류 기록 함께 보내기"
            isChecked = true
            visibility = if (crash != null) View.VISIBLE else View.GONE
        }
        box.addView(withCrash)
        val note = TextView(activity).apply {
            textSize = 12f
            setTextColor(MaterialColors.getColor(activity.window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant))
            text = if (FeedbackConfig.ready) "문서 내용과 파일 이름은 보내지 않습니다." else "건의를 받는 곳이 아직 준비되지 않았습니다."
            setPadding(0, dp(8), 0, dp(4))
        }
        box.addView(note)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("건의 · 오류 신고")
            .setView(ScrollView(activity).apply { addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
            .setPositiveButton("보내기", null)
            .setNegativeButton("닫기", null)
            .create()
        dialog.show()
        val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        send.isEnabled = FeedbackConfig.ready
        send.setOnClickListener {
            val text = message.text.toString().trim()
            if (text.length < 5) {
                message.error = "내용을 조금 더 적어 주세요."
                return@setOnClickListener
            }
            val kind = kinds[group.indexOfChild(group.findViewById(group.checkedRadioButtonId)).coerceAtLeast(0)]
            val info = if (withInfo.isChecked || withCrash.isChecked) FeedbackForm.info(
                versionName(), versionCode(), "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE,
                if (withCrash.isChecked && crash != null) FeedbackForm.clip(crash, 4000) else null,
            ) else ""
            val body = FeedbackForm.encode(
                listOf(
                    FeedbackConfig.ENTRY_KIND to kind,
                    FeedbackConfig.ENTRY_MESSAGE to FeedbackForm.clip(text, 4000),
                    FeedbackConfig.ENTRY_CONTACT to contact.text.toString().trim(),
                    FeedbackConfig.ENTRY_INFO to info,
                )
            )
            send.isEnabled = false
            note.text = "보내는 중…"
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { post(body) }
                if (ok) {
                    if (withCrash.isChecked) CrashLog.clear(activity)
                    Toast.makeText(activity, "보냈습니다. 고맙습니다!", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    note.text = "보내지 못했습니다. 인터넷 연결을 확인하고 다시 눌러 주세요. (쓴 글은 그대로 있습니다)"
                    send.isEnabled = true
                }
            }
        }
    }

    /** 설문 제출 주소로 보낸다. 성공하면 true */
    private fun post(body: String): Boolean = try {
        val c = URL(FeedbackForm.submitUrl(FeedbackConfig.FORM_ID)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", "DSnote")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..399
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        false
    }

    private fun pkg() = activity.packageManager.getPackageInfo(activity.packageName, 0)
    private fun versionName() = pkg().versionName ?: "?"
    private fun versionCode(): Long =
        if (Build.VERSION.SDK_INT >= 28) pkg().longVersionCode else @Suppress("DEPRECATION") pkg().versionCode.toLong()
}
