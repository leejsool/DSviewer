package com.dsviewer.app

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 앱 정보·업데이트: 지금 버전을 보여 주고, GitHub 릴리스의 최신 APK를 받아 설치 화면까지 이어 준다.
 * 눌렀을 때만 인터넷에 접속한다 (저절로 확인하지 않음). 설치는 안드로이드 설치 화면에서 사용자가 '설치'를 눌러야 끝난다.
 */
class Updater(private val activity: AppCompatActivity) {

    /** 릴리스 한 개: 버전 이름, APK 주소·크기·검증값, 바뀐 점 */
    private class Release(val name: String, val url: String, val size: Long, val sha256: String?, val notes: String)

    private val d = activity.resources.displayMetrics.density
    private var dialog: AlertDialog? = null
    private var job: Job? = null
    private var release: Release? = null
    /** 내려받아 둔 APK: '알 수 없는 앱 설치' 허용을 켜러 갔다 돌아오면 이어서 설치한다 */
    private var ready: File? = null
    private var waitingForPermission = false

    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var bar: LinearProgressIndicator

    // ================= 대화 상자 =================

    fun show() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * d).toInt()
            setPadding(p, (8 * d).toInt(), p, 0)
        }
        box.addView(TextView(activity).apply {
            text = "현재 버전  ${currentName()}  (${currentCode()})"
            textSize = 17f
            setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
        })
        status = TextView(activity).apply {
            textSize = 15f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, (14 * d).toInt(), 0, 0)
        }
        box.addView(status)
        bar = LinearProgressIndicator(activity).apply {
            visibility = View.GONE
            max = 1000
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() })
        notes = TextView(activity).apply {
            textSize = 14f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, (12 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(notes) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("앱 정보 · 업데이트")
            .setView(box)
            .setPositiveButton("업데이트 확인", null)
            .setNegativeButton("닫기", null)
            .create()
            .also { dlg ->
                dlg.setOnDismissListener { job?.cancel(); dialog = null }
                dlg.show()
                // 단추를 눌러도 상자가 닫히지 않게 직접 건다
                dlg.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { dlg.dismiss() }
            }
        check()
    }

    private fun setAction(label: String, onClick: () -> Unit) {
        val b = dialog?.getButton(DialogInterface.BUTTON_POSITIVE) ?: return
        b.text = label
        b.visibility = View.VISIBLE
        b.isEnabled = true
        b.setOnClickListener { onClick() }
    }

    private fun hideAction() {
        dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.visibility = View.GONE
    }

    private fun attrColor(attr: Int) = com.google.android.material.color.MaterialColors.getColor(activity.window.decorView, attr)

    // ================= 새 버전 확인 =================

    private fun check() {
        job?.cancel()
        release = null
        ready = null
        notes.visibility = View.GONE
        bar.visibility = View.GONE
        hideAction()
        status.text = "새 버전을 확인하는 중…"
        job = activity.lifecycleScope.launch {
            val r = try {
                withContext(Dispatchers.IO) { fetchLatest() }
            } catch (e: Exception) {
                if (!isActive) return@launch
                status.text = "확인하지 못했습니다. 인터넷 연결을 살펴본 뒤 다시 시도해 주세요."
                setAction("다시 확인") { check() }
                return@launch
            }
            if (!isNewer(r.name, currentName())) {
                status.text = "최신 버전입니다."
                setAction("다시 확인") { check() }
                return@launch
            }
            release = r
            status.text = "새 버전이 있습니다: ${r.name}  (${mb(r.size)})"
            if (r.notes.isNotBlank()) {
                notes.text = r.notes
                notes.visibility = View.VISIBLE
            }
            setAction("업데이트") { download(r) }
        }
    }

    /** GitHub의 최신 릴리스 정보 (APK는 .apk로 끝나는 첫 파일) */
    private fun fetchLatest(): Release {
        val json = JSONObject(httpText("https://api.github.com/repos/leejsool/DSviewer/releases/latest"))
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (!a.getString("name").endsWith(".apk", ignoreCase = true)) continue
            return Release(
                name = json.getString("tag_name").trim().removePrefix("v").removePrefix("V"),
                url = a.getString("browser_download_url"),
                size = a.optLong("size"),
                sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                notes = changes(json.optString("body")),
            )
        }
        throw IllegalStateException("릴리스에 APK가 없음")
    }

    private fun httpText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DSnote")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** 릴리스 글에서 '바뀐 점' 부분만 간단한 글로 (없으면 전체) */
    private fun changes(body: String): String {
        val lines = body.replace("\r", "").lines()
        val start = lines.indexOfFirst { it.trimStart('#', ' ').startsWith("바뀐 점") }
        val part = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.startsWith("#") }
        return part.joinToString("\n") { it.replace("**", "").replace("`", "").replace(Regex("^\\s*[-*]\\s+"), "• ") }
            .trim()
    }

    // ================= 내려받기·설치 =================

    private fun download(r: Release) {
        job?.cancel()
        hideAction()
        notes.visibility = View.GONE
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        status.text = "내려받는 중…"
        setAction("취소") { job?.cancel(); status.text = "취소했습니다."; bar.visibility = View.GONE; setAction("업데이트") { download(r) } }
        job = activity.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { fetchApk(r) }
            } catch (e: Exception) {
                if (!isActive) return@launch
                bar.visibility = View.GONE
                status.text = "내려받지 못했습니다: ${e.message ?: "알 수 없는 오류"}"
                setAction("다시 시도") { download(r) }
                return@launch
            }
            bar.visibility = View.GONE
            ready = file
            install(file)
        }
    }

    private suspend fun fetchApk(r: Release): File {
        val dir = File(activity.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "DSnote-${r.name}.apk")
        val part = File(dir, "download.part")
        val sha = MessageDigest.getInstance("SHA-256")
        val c = open(r.url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("서버 응답 ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                part.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        currentCoroutineContext().ensureActive()
                        os.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 1000 / total).toInt() else 0
                        if (p != shown) {
                            shown = p
                            withContext(Dispatchers.Main) {
                                bar.isIndeterminate = false
                                bar.setProgressCompat(p, false)
                                status.text = "내려받는 중…  ${mb(done)} / ${mb(total)}"
                            }
                        }
                    }
                }
            }
            if (r.size > 0 && done != r.size) throw IllegalStateException("파일이 다 받아지지 않았습니다")
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256 != null && !hex.equals(r.sha256, ignoreCase = true)) throw IllegalStateException("파일 검증에 실패했습니다")
            if (!part.renameTo(out)) throw IllegalStateException("파일을 저장하지 못했습니다")
            return out
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    /** 받은 APK가 이 앱의 새 버전인지 확인한 뒤 설치 화면을 연다 */
    private fun install(file: File) {
        val info = activity.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != activity.packageName) {
            status.text = "받은 파일이 올바른 DSnote 설치 파일이 아닙니다."
            release?.let { r -> setAction("다시 받기") { download(r) } }
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            status.text = "DSnote가 앱을 설치할 수 있도록 허용해야 합니다. 설정 화면에서 '이 출처 허용'을 켜고 돌아와 주세요."
            waitingForPermission = true
            setAction("설정 열기") { openPermissionSettings() }
            return
        }
        waitingForPermission = false
        val open = ViewerActivity.openUris.isNotEmpty()
        status.text = "설치 화면에서 '설치'를 눌러 주세요. 설치하면 앱이 다시 시작됩니다." +
            if (open) "\n열어 둔 문서는 먼저 저장해 두세요." else ""
        setAction("다시 설치") { install(file) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            status.text = "이 기기에서는 설치 화면을 열 수 없습니다."
        }
    }

    private fun openPermissionSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "설정 화면을 열 수 없습니다. 설정 ▸ 앱 ▸ DSnote ▸ 알 수 없는 앱 설치에서 허용해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    /** 설정에서 돌아왔을 때: 허용했으면 받아 둔 APK를 이어서 설치한다 */
    fun onResume() {
        val f = ready ?: return
        if (waitingForPermission && dialog != null && activity.packageManager.canRequestPackageInstalls()) install(f)
    }

    // ================= 버전 =================

    private fun pkg() = activity.packageManager.getPackageInfo(activity.packageName, 0)
    private fun currentName(): String = pkg().versionName ?: "?"
    private fun currentCode(): Long =
        if (Build.VERSION.SDK_INT >= 28) pkg().longVersionCode else @Suppress("DEPRECATION") pkg().versionCode.toLong()

    private fun mb(bytes: Long) = if (bytes <= 0) "?" else "%.1f MB".format(bytes / 1048576.0)

    companion object {
        /** 이전에 받아 둔 설치 파일 지우기 (업데이트를 마친 뒤 용량을 차지하지 않게) */
        fun clearOld(ctx: android.content.Context) {
            File(ctx.cacheDir, "update").deleteRecursively()
        }

        /** 점으로 나뉜 숫자 버전 비교: 1.0.2 > 1.0.1, 1.1 == 1.1.0 */
        fun isNewer(latest: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parts(latest)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
