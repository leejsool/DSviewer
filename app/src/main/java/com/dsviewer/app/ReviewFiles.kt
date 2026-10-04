package com.dsviewer.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 복습 풀이 파일 이름 짓기. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다 */
internal object ReviewFiles {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.US)

    /**
     * 복습을 시작한 때([millis])를 넣은 파일 이름: '원본이름_복습_2026-10-05 19.45.12.pdf'.
     * 이름에 못 쓰는 글자는 바꾸고 (시각의 ':'도 '.'으로), 원본 이름이 아주 길면 앞부분만 쓴다
     */
    fun fileName(sourceName: String, millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val base = FileNames.safe(FileNames.baseName(sourceName)).trim().take(60).ifEmpty { "문서" }
        return "${base}_복습_${STAMP.format(Instant.ofEpochMilli(millis).atZone(zone))}.pdf"
    }

    /** 같은 이름이 이미 있으면 '이름 (2).pdf', '이름 (3).pdf' … 로 비켜 간다 */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val base = FileNames.baseName(name)
        val ext = FileNames.extension(name)
        var n = 2
        while (exists("$base ($n)$ext")) n++
        return "$base ($n)$ext"
    }
}
