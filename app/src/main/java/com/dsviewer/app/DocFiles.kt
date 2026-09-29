package com.dsviewer.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.text.Collator
import java.util.Locale

/** 파일 탐색기 한 줄 (폴더 또는 문서) */
data class Entry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val time: Long = 0,
    val size: Long = 0,
    /** 저장소 이름처럼 경로 대신 보여 줄 설명 */
    val label: String? = null,
)

/** 기기 저장소에서 문서 찾기 ('모든 파일 접근' 권한 필요) */
object DocFiles {

    private val EXTS = listOf("pdf", "hwp", "hwpx")

    fun isDoc(name: String) = name.substringAfterLast('.', "").lowercase() in EXTS

    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** 저장소 목록 (내장 메모리, SD카드, USB) */
    fun roots(ctx: Context): List<Entry> {
        if (Build.VERSION.SDK_INT >= 30) {
            val sm = ctx.getSystemService(StorageManager::class.java)
            val list = sm.storageVolumes.mapNotNull { v ->
                val dir = v.directory ?: return@mapNotNull null
                if (!dir.canRead()) return@mapNotNull null
                Entry(dir, v.getDescription(ctx), isDir = true, label = v.getDescription(ctx))
            }
            if (list.isNotEmpty()) return list
        }
        val dir = Environment.getExternalStorageDirectory()
        return listOf(Entry(dir, "내장 메모리", isDir = true, label = "내장 메모리"))
    }

    /** 폴더 안의 하위 폴더와 문서 (숨김 파일 제외, 폴더 먼저, 이름순) */
    fun list(dir: File): List<Entry> {
        val files = dir.listFiles() ?: return emptyList()
        val collator = Collator.getInstance(Locale.KOREAN)
        return files.asSequence()
            .filter { !it.name.startsWith(".") && (it.isDirectory || isDoc(it.name)) }
            .map { Entry(it, it.name, it.isDirectory, it.lastModified(), if (it.isDirectory) 0 else it.length()) }
            .sortedWith(compareBy<Entry> { !it.isDir }.thenComparator { a, b -> collator.compare(a.name, b.name) })
            .toList()
    }

    /** 기기 전체의 PDF/HWP/HWPX (최근 수정순). MediaStore 색인을 쓰므로 빠르다. */
    fun scanAll(ctx: Context): List<Entry> {
        val nameCol = MediaStore.MediaColumns.DISPLAY_NAME
        @Suppress("DEPRECATION") val dataCol = MediaStore.MediaColumns.DATA
        val proj = arrayOf(dataCol, nameCol, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
        val sel = EXTS.joinToString(" OR ") { "$nameCol LIKE ?" }
        val args = EXTS.map { "%.$it" }.toTypedArray()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            proj, sel, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (!seen.add(path) || path.contains("/.")) continue
                val name = c.getString(1) ?: path.substringAfterLast('/')
                if (!isDoc(name)) continue
                val f = File(path)
                if (!f.isFile) continue
                out += Entry(f, name, isDir = false, time = c.getLong(2) * 1000, size = c.getLong(3))
            }
        }
        return out
    }
}
