package com.dsviewer.app

import android.net.Uri
import java.io.File

/** 탭 하나 = 열린 문서 하나. 문서 화면(DocumentView)은 하나를 같이 쓰고 탭을 바꿀 때 갈아 끼운다 */
internal class DocTab(var uri: Uri, var canOverwrite: Boolean, var isNewNote: Boolean) {
    var name = "문서"
    var type = DocType.UNKNOWN
    var pdf: PdfDoc? = null
    var ink: InkDocument? = null
    /** 앱 캐시에 복사한 원본 PDF (저장할 때 이 파일에 필기를 얹는다) */
    var sourcePdf: File? = null
    /** 화면에 그리는 PDF (필기를 뺀 사본이거나 [sourcePdf] 그대로) */
    var renderPdf: File? = null
    /** 빈 쪽 넣기·쪽 지우기로 PDF를 다시 만드는 중 */
    var pagesBusy = false
    /** 다른 탭에 가 있는 동안 기억해 둔 스크롤·확대 위치 */
    var viewState: DocumentView.ViewState? = null
    /** 글자 찾기 (처음 찾을 때 만든다. 쪽을 바꾸면 화면용 PDF가 바뀌어 새로 만든다) */
    var search: DocSearch? = null
    /** PDF 링크 (읽기 모드에서 처음 누를 때 꺼낸다. 쪽을 바꾸면 새로) */
    var links: DocLinks? = null
}

/** 쪽을 넣고 빼기 전·후의 PDF 파일과 그때 보던 쪽 (실행 취소하면 이 상태로 돌아간다) */
internal class PageFiles(val source: File, val render: File, var page: Int)

/** 링크로 옮겨 가기 전·후의 자리 (쪽, 그 쪽 안 높이). 실행 취소 기록에 담는다 */
internal class Spot(val page: Int, val y: Float)
