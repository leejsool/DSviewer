package com.dsviewer.app

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.util.Base64
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPatternContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.util.DateConverter
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import java.util.Calendar
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 필기를 PDF 표준 잉크 주석(/Ink)으로 저장하고 다시 읽는다.
 * 다른 PDF 앱에서도 보이도록 외형(appearance stream)을 함께 넣고,
 * 이 앱에서 다시 편집할 수 있도록 원본 획 데이터를 전용 키에 보관한다.
 */
object PdfInk {
    private const val KEY = "DSViewerStroke"
    private val KEY_NAME: COSName = COSName.getPDFName(KEY)
    /** 북마크한 쪽: 쪽 사전에 이 키를 true로 */
    private val MARK_NAME: COSName = COSName.getPDFName("DSViewerMark")
    /** 오답노트 쪽: 쪽 사전에 이 키로 그 쪽의 오답 항목들을 한 줄 글로 ([WrongNote.encode]) */
    private val WRONG_NAME: COSName = COSName.getPDFName("DSViewerWrong")
    /** 이 앱이 만든 오답노트 목차 항목: 목차 맨 위 단계에 이 키를 true로 (다시 저장할 때 이것만 갈아 끼운다) */
    private val WRONG_OUTLINE: COSName = COSName.getPDFName("DSViewerWrongOutline")
    /** 글에 단 링크 주소: 글 주석 사전에 이 키로. 다른 앱에서도 눌리도록 따로 넣는 /Link 주석에는 [KEY_NAME]을 [LINK_MARK]로 */
    private val LINK_NAME: COSName = COSName.getPDFName("DSViewerLink")
    private const val LINK_MARK = "link"
    const val HL_ALPHA = 0.45f

    /**
     * 이 앱이 저장한 필기나 북마크가 들어 있는지 빠르게 확인 (PDFBox는 주석·쪽 사전을 압축하지 않고 쓴다).
     * 두 키 모두 /DSViewer로 시작한다
     */
    fun containsInk(file: File): Boolean = InkSniffer.containsInk(file)

    /**
     * PDFBox 클래스를 미리 불러 둔다 (처음 쓸 때 클래스를 읽느라 열기·저장이 0.1초 넘게 느려지지 않게).
     * 앱이 켜질 때 뒤에서 한 번 부른다. 실패해도 상관없다
     */
    fun warmUp() {
        runCatching {
            val bytes = ByteArrayOutputStream()
            PDDocument().use { doc ->
                doc.addPage(PDPage(PDRectangle.LETTER))
                doc.save(bytes)
            }
            PDDocument.load(bytes.toByteArray()).use { doc -> doc.pages.forEach { it.annotations } }
            decode(encode(Stroke(Tool.PEN, 0xFF000000.toInt(), 1f)))
        }
    }

    /**
     * 이 앱의 필기를 꺼내고, 필기를 뺀 PDF를 [clean]에 저장한다 (화면 렌더링용).
     * [marks]를 주면 북마크한 쪽 번호(0부터)를, [wrongs]를 주면 쪽 번호별 오답 정보 글을 담는다
     */
    fun extract(src: File, clean: File, marks: MutableSet<Int>? = null, wrongs: MutableMap<Int, String>? = null): List<List<Stroke>> {
        val result = ArrayList<MutableList<Stroke>>()
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for ((pi, page) in doc.pages.withIndex()) {
                if (page.cosObject.getBoolean(MARK_NAME, false)) marks?.add(pi)
                page.cosObject.getString(WRONG_NAME)?.let { wrongs?.put(pi, it) }
                val strokes = mutableListOf<Stroke>()
                val annots = page.annotations
                val keep = ArrayList<PDAnnotation>()
                for (a in annots) {
                    val s = a.cosObject.getString(KEY_NAME)
                    if (s == null) {
                        keep.add(a)
                        continue
                    }
                    // 이 앱이 넣은 /Link 주석([LINK_MARK])은 decode가 null이라 버린다 (글의 링크로 다시 만든다)
                    val st = decode(s) ?: continue
                    // 그림: 외형 안의 그림을 꺼낸다 (못 꺼내면 버림)
                    if (s.startsWith("1|I|")) st.image = readImage(a) ?: continue
                    if (st.text != null) st.link = a.cosObject.getString(LINK_NAME)
                    strokes.add(st)
                }
                if (keep.size != annots.size) page.annotations = keep
                result.add(strokes)
            }
            doc.save(clean)
        }
        return result
    }

    /** [page]쪽의 이 앱 필기만 꺼낸다 (썸네일용. PdfRenderer는 주석을 그리지 않으므로 원본 위에 얹으면 된다) */
    fun pageStrokes(src: File, page: Int): List<Stroke> {
        PDDocument.load(src).use { doc ->
            if (page !in 0 until doc.numberOfPages) return emptyList()
            return doc.getPage(page).annotations.mapNotNull { a ->
                val s = a.cosObject.getString(KEY_NAME) ?: return@mapNotNull null
                val st = decode(s) ?: return@mapNotNull null
                if (s.startsWith("1|I|")) st.image = readImage(a) ?: return@mapNotNull null
                if (st.text != null) st.link = a.cosObject.getString(LINK_NAME)
                st
            }
        }
    }

    /**
     * 원본 [src]에 필기를 주석으로 넣고 [marks] 쪽(0부터)에 북마크를 달아 [out]에 저장.
     * [wrongs]를 주면 오답 쪽 정보를 쪽 사전에 넣고 PDF 목차에 '오답노트' 항목을 새로 쓴다
     */
    fun save(
        src: File, out: File, pages: List<List<Stroke>>, marks: Set<Int> = emptySet(), wrongs: WrongSave? = null,
        /** 단계가 끝날 때마다 단계 이름을 알린다 (걸린 시간 기록용) */
        step: ((String) -> Unit)? = null,
    ) {
        // 글 외형을 그린 임시 PDF (다 저장한 뒤에 닫는다)
        var texts: TextForms? = null
        val ink = InkWriter()
        savesRunning.incrementAndGet()
        try {
            PDDocument.load(src).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                step?.invoke("PDF읽기")
                texts = renderTexts(doc, pages)
                step?.invoke("글그리기")
                for ((i, page) in doc.pages.withIndex()) {
                    val list: MutableList<PDAnnotation> =
                        page.annotations.filterTo(ArrayList()) { !it.cosObject.containsKey(KEY_NAME) }
                    if (i < pages.size && pages[i].isNotEmpty()) {
                        val frame = PageFrame(page)
                        // 그림, 채우기를 먼저 (다른 앱에서도 필기가 그 위에 보이게)
                        for (s in pages[i].sortedBy { inkLayer(it) }) {
                            if (s.count == 0) continue
                            list.add(
                                when {
                                    s.note != null -> makeNoteAnnotation(doc, page, s)
                                    s.image != null -> makeImageAnnotation(doc, page, s)
                                    s.text != null -> makeTextAnnotation(doc, page, s, texts!!)
                                    s.table != null -> makeTableAnnotation(doc, page, s)
                                    s.tape != null -> makeTapeAnnotation(doc, page, s)
                                    s.fill != null -> makeFillAnnotation(doc, page, s)
                                    else -> ink.annotation(doc, page, frame, s)
                                }
                            )
                            if (s.text != null) s.link?.let { list.add(makeLinkAnnotation(page, s, it)) }
                        }
                    }
                    page.annotations = list
                    if (i in marks) page.cosObject.setBoolean(MARK_NAME, true)
                    else page.cosObject.removeItem(MARK_NAME)
                    if (wrongs != null) {
                        val meta = wrongs.metas[i]
                        if (meta != null) page.cosObject.setString(WRONG_NAME, meta) else page.cosObject.removeItem(WRONG_NAME)
                    }
                }
                // 목차가 잘못돼도 필기 저장은 막지 않는다
                if (wrongs != null) runCatching { writeWrongOutline(doc, wrongs.outline) }
                step?.invoke("주석만들기")
                doc.save(out)
                step?.invoke("PDF쓰기")
            }
        } finally {
            texts?.src?.close()
            ink.close()
            savesRunning.decrementAndGet()
        }
    }

    /** 지금 돌고 있는 [save] 수 (미리 만들기는 저장이 돌면 비켜선다) */
    private val savesRunning = AtomicInteger()

    /**
     * 저장 준비: 펜·형광펜 획의 주석 재료를 미리 만들어 보관해 둔다 (문서를 연 뒤 한가할 때, 첫 저장이 빨라지게).
     * [base]는 저장할 때 바탕으로 쓸 PDF (쪽 상자·회전만 읽는다). 파일은 쓰지 않고 주석도 만들지 않는다.
     * 저장과 같은 조건(획 [Stroke.version], 쪽 상자·회전)으로 보관하므로, 그새 획이 바뀌거나 저장 바탕이 달라지면
     * 저장할 때 그 획만 다시 만든다. [active]가 false가 되거나 저장이 돌기 시작하면 그만둔다. 새로 만든 재료 수를 돌려준다
     */
    fun prewarm(base: File, pages: List<List<Stroke>>, active: () -> Boolean): Int {
        var built = 0
        val ink = InkWriter()
        try {
            PDDocument.load(base).use { doc ->
                for ((i, page) in doc.pages.withIndex()) {
                    if (i >= pages.size) break
                    if (pages[i].none(::isPenStroke)) continue
                    val frame = PageFrame(page)
                    for (s in pages[i]) {
                        if (!isPenStroke(s) || ink.ready(frame, s)) continue
                        if (!active() || savesRunning.get() > 0) return built
                        ink.part(doc, frame, s)
                        built++
                    }
                }
            }
        } finally {
            ink.close()
        }
        return built
    }

    /** [save]에서 [InkWriter]로 가는 획 (펜·형광펜): 그림·글·표·테이프·채우기·메모가 아닌 것 */
    internal fun isPenStroke(s: Stroke) = s.count > 0 && s.note == null && s.image == null && s.text == null &&
        s.table == null && s.tape == null && s.fill == null

    /**
     * 자동 저장용 가벼운 필기 파일: 원본 PDF를 열지 않고 쪽 수만큼의 빈 쪽에 필기 정보만 담아 [out]에 쓴다.
     * 획은 [encode] 글만 주석에 달고 외형(다른 앱에서 보이는 그림)은 만들지 않으며, 그림만 외형 속에 담는다.
     * 쓰는 시간이 PDF 크기와 상관없이 필기 양에만 따른다. 읽을 때는 [extract]에 그대로 넣으면 되고
     * (북마크·오답 정보 포함), 쪽의 크기·회전은 담지 않는다 (좌표는 모두 쪽 기준 값이라 필요 없다)
     */
    fun saveLite(out: File, pages: List<List<Stroke>>, marks: Set<Int> = emptySet(), wrongs: WrongSave? = null) {
        PDDocument().use { doc ->
            for (i in pages.indices) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                val list = ArrayList<PDAnnotation>(pages[i].size)
                for (s in pages[i]) {
                    if (s.count == 0) continue
                    list.add(if (s.image != null) makeImageAnnotation(doc, page, s) else makeLiteAnnotation(s))
                }
                page.annotations = list
                if (i in marks) page.cosObject.setBoolean(MARK_NAME, true)
                wrongs?.metas?.get(i)?.let { page.cosObject.setString(WRONG_NAME, it) }
            }
            doc.save(out)
        }
    }

    /** 자동 저장용 주석: 필기 정보 글([encode])과 글에 단 링크만 */
    private fun makeLiteAnnotation(s: Stroke): PDAnnotation {
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = PDRectangle(0f, 0f, 1f, 1f)
        dict.setString(KEY_NAME, encode(s))
        if (s.text != null) s.link?.let { dict.setString(LINK_NAME, it) }
        return annot
    }

    /**
     * 오답노트 목차: 맨 위 단계에 '오답노트' 하나, 그 아래 기호별 묶음(별 5개 → 1개, 세모, 엑스, 네모, 분류 없음),
     * 그 아래 '#번호 제목 #태그' 항목이 그 오답 쪽(반 쪽이면 그 칸 위)으로 간다.
     * 원래 있던 다른 목차 항목은 그대로 두고, 이 앱이 전에 만든 '오답노트' 항목만 갈아 끼운다
     */
    private fun writeWrongOutline(doc: PDDocument, items: List<WrongOutlineItem>) {
        val catalog = doc.documentCatalog
        val old = catalog.documentOutline
        val keep = ArrayList<PDOutlineItem>()
        var hadOurs = false
        if (old != null) for (item in old.children()) {
            if (item.cosObject.getBoolean(WRONG_OUTLINE, false)) hadOurs = true else keep.add(item)
        }
        if (items.isEmpty() && !hadOurs) return
        if (items.isEmpty() && keep.isEmpty()) {
            catalog.documentOutline = null
            return
        }
        val root = PDDocumentOutline()
        // 남길 항목들을 이웃 관계에서 떼어 새 목차에 차례로 붙인다
        for (item in keep) {
            item.cosObject.removeItem(COSName.NEXT)
            item.cosObject.removeItem(COSName.PREV)
            item.cosObject.removeItem(COSName.PARENT)
            root.addLast(item)
        }
        if (items.isNotEmpty()) {
            val top = PDOutlineItem()
            top.title = "스크랩 (${items.size})"
            top.cosObject.setBoolean(WRONG_OUTLINE, true)
            root.addLast(top)
            val groups = items.groupBy { it.entry.symbol }.toSortedMap(compareBy { WrongSymbol.order(it) })
            for ((symbol, list) in groups) {
                val group = PDOutlineItem()
                group.title = "${WrongSymbol.label(symbol)} (${list.size})"
                top.addLast(group)
                for (item in list.sortedBy { it.entry.number }) {
                    val page = doc.getPage(item.page.coerceIn(0, doc.numberOfPages - 1))
                    val dest = PDPageXYZDestination()
                    dest.page = page
                    dest.top = (page.cropBox.upperRightY - item.top).roundToInt()
                    val node = PDOutlineItem()
                    node.title = WrongNote.outlineTitle(item.entry)
                    node.destination = dest
                    group.addLast(node)
                }
                group.openNode()
            }
            top.openNode()
        }
        root.openNode()
        catalog.documentOutline = root
    }

    /** 글 상자 외형들: 한 임시 PDF의 쪽들 ([index]는 글 획 → 쪽 번호) */
    private class TextForms(val src: PDDocument?, val index: java.util.IdentityHashMap<Stroke, Int>, val layer: LayerUtility)

    /**
     * 모든 글 상자를 안드로이드 PDF 하나에 한 쪽씩 그린다. 한 문서 안에서는 글꼴이 한 번만 담기고
     * (쓴 글자만 모아서), 같은 LayerUtility로 가져오면 그 글꼴을 모든 글 상자가 같이 쓴다.
     * 글 상자마다 PDF를 따로 만들면 같은 글꼴이 글 상자 수만큼 들어가 파일이 커진다
     */
    private fun renderTexts(doc: PDDocument, pages: List<List<Stroke>>): TextForms {
        val index = java.util.IdentityHashMap<Stroke, Int>()
        val layer = LayerUtility(doc)
        val all = pages.flatten().filter { it.text != null && it.count > 0 }
        if (all.isEmpty()) return TextForms(null, index, layer)
        val bytes = ByteArrayOutputStream().use { out ->
            val pd = PdfDocument()
            try {
                for ((n, s) in all.withIndex()) {
                    val t = s.text!!
                    val pg = pd.startPage(PdfDocument.PageInfo.Builder(t.boxW, t.boxH, n + 1).create())
                    t.draw(pg.canvas, s.color)
                    pd.finishPage(pg)
                    index[s] = n
                }
                pd.writeTo(out)
            } finally {
                pd.close()
            }
            out.toByteArray()
        }
        return TextForms(PDDocument.load(bytes), index, layer)
    }

    /**
     * 펜·형광펜 획의 주석 재료. 저장할 때마다 새로 만들지 않고 획이 그대로면 다시 쓴다
     * (만든 때의 [version]과 쪽 상자·회전이 같을 때). 여기 담긴 COS 객체는 만든 뒤로 고치지 않으므로
     * 여러 저장이 같이 써도 된다. 쪽 참조(/P)·외형 스트림·주석 사전은 저장할 때마다 그 문서 것으로 새로 만든다
     */
    private class InkPart(
        val version: Int,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rot: Int,
        val rect: COSArray,
        val color: COSArray,
        val name: COSString,
        val inkList: COSArray,
        val bs: COSDictionary,
        val alpha: COSFloat?,
        val data: COSString,
        val resources: COSDictionary,
        /** 외형 그리기 명령 (Flate로 압축한 바이트) */
        val look: ByteArray,
    )

    /** 획 → 그 획의 주석 재료. 획이 사라지면 같이 정리된다 (약한 참조). 저장 스레드 여럿이 같이 쓰므로 잠가서 쓴다 */
    private val inkParts = WeakHashMap<Stroke, InkPart>()

    /** 주석 재료를 새로 만든 횟수 (시험에서 다시 썼는지 본다) */
    private val partBuilds = AtomicInteger()
    internal val inkPartBuilds get() = partBuilds.get()

    /** 보관한 주석 재료를 모두 버린다 (시험용) */
    internal fun clearInkParts() {
        synchronized(inkParts) { inkParts.clear() }
    }

    /** 주석 이름(/NM): 앱을 켤 때 정한 머리말 + 차례 번호 (UUID보다 싸고, 한 쪽 안에서 겹치지 않으면 된다) */
    private val namePrefix = "dsv" + java.lang.Long.toHexString(kotlin.random.Random.nextLong() and Long.MAX_VALUE) + "-"
    private val nameCount = AtomicLong()

    /** 쪽의 자르기 상자와 회전 (화면 좌표 → PDF 좌표가 이것에만 따른다). 쪽마다 한 번만 읽는다 */
    private class PageFrame(page: PDPage) {
        val x0: Float
        val y0: Float
        val x1: Float
        val y1: Float
        val rot = ((page.rotation % 360) + 360) % 360

        init {
            val b = page.cropBox
            x0 = b.lowerLeftX
            y0 = b.lowerLeftY
            x1 = b.upperRightX
            y1 = b.upperRightY
        }

        fun fits(p: InkPart) = p.x0 == x0 && p.y0 == y0 && p.x1 == x1 && p.y1 == y1 && p.rot == rot

        fun toUser(x: Float, y: Float, ux: FloatArray, uy: FloatArray, i: Int) = PdfInk.toUser(x, y, x0, y0, x1, y1, rot, ux, uy, i)
    }

    /**
     * 저장 한 번 동안 펜·형광펜 획 주석을 만든다. 외형 그리기 버퍼·압축기·고친 날짜는 획마다 새로 만들지 않고 같이 쓴다.
     * 한 저장 스레드 안에서만 쓴다
     */
    private class InkWriter {
        /** 외형 그리기 명령을 받는 버퍼 (그리기 도구는 숫자 하나씩 잘게 쓰므로 압축기에 바로 흘리지 않는다) */
        val plain = ByteArrayOutputStream(4096)
        private var deflater: Deflater? = null
        private val chunk = ByteArray(4096)
        private val packed = ByteArrayOutputStream(1024)
        private val date = COSString(DateConverter.toString(Calendar.getInstance()))

        fun annotation(doc: PDDocument, page: PDPage, frame: PageFrame, s: Stroke): PDAnnotation =
            inkAnnotation(doc, page, part(doc, frame, s), date)

        /** 보관한 재료가 지금 획·쪽에 맞는지 */
        fun ready(frame: PageFrame, s: Stroke): Boolean {
            val version = s.version
            return synchronized(inkParts) { inkParts[s] }?.let { it.version == version && frame.fits(it) } == true
        }

        /** 이 획의 재료: 보관한 것이 맞으면 그것, 아니면 새로 만들어 보관한다 */
        fun part(doc: PDDocument, frame: PageFrame, s: Stroke): InkPart {
            // version을 만들기 전에 읽어 둔다: 만드는 동안 획이 바뀌면 version이 올라 다음 저장에서 다시 만든다
            // ([Stroke.version]은 @Volatile이고 점을 다 바꾼 뒤에 올리므로, 읽은 version까지의 점은 다 보인다)
            val version = s.version
            return synchronized(inkParts) { inkParts[s] }?.takeIf { it.version == version && frame.fits(it) }
                ?: makeInkPart(doc, frame, s, version, this).also {
                    synchronized(inkParts) { inkParts[s] = it }
                }
        }

        /** [plain]에 받은 그리기 명령을 Flate(zlib)로 한 번에 압축한다 (PDFBox의 FlateFilter와 같은 기본 압축 수준) */
        fun pack(): ByteArray {
            val d = deflater ?: Deflater().also { deflater = it }
            d.reset()
            d.setInput(plain.toByteArray())
            d.finish()
            packed.reset()
            while (!d.finished()) packed.write(chunk, 0, d.deflate(chunk))
            return packed.toByteArray()
        }

        fun close() {
            deflater?.end()
        }
    }

    /**
     * 주석 재료로 이 문서에 넣을 /Ink 주석을 만든다. 주석 사전과 외형 스트림은 문서마다 새로 만들고
     * 그 안의 값들(상자, 색, 점, 획 글 등)은 재료의 것을 그대로 넣는다
     */
    private fun inkAnnotation(doc: PDDocument, page: PDPage, p: InkPart, date: COSString): PDAnnotation {
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"))
        val annot = PDAnnotation.createAnnotation(dict)
        dict.setItem(COSName.RECT, p.rect)
        dict.setItem(COSName.C, p.color)
        annot.isPrinted = true
        dict.setItem(COSName.NM, p.name)
        dict.setItem(COSName.M, date)
        annot.page = page
        dict.setItem(COSName.getPDFName("InkList"), p.inkList)
        dict.setItem(COSName.BS, p.bs)
        p.alpha?.let { dict.setItem(COSName.CA, it) }
        dict.setItem(KEY_NAME, p.data)

        // 외형: 미리 압축해 둔 그리기 명령을 그대로 넣는다
        val ap = PDAppearanceStream(doc)
        val stream = ap.cosObject
        stream.setItem(COSName.BBOX, p.rect)
        stream.setItem(COSName.RESOURCES, p.resources)
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE)
        stream.createRawOutputStream().use { it.write(p.look) }
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /** 펜·형광펜 획 하나의 주석 재료를 만든다 ([doc]은 그리기 도구에 넘길 뿐 재료에 남지 않는다) */
    private fun makeInkPart(doc: PDDocument, frame: PageFrame, s: Stroke, version: Int, w: InkWriter): InkPart {
        partBuilds.incrementAndGet()
        val n = s.count
        val ux = FloatArray(n)
        val uy = FloatArray(n)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            frame.toUser(s.x(i), s.y(i), ux, uy, i)
            minX = min(minX, ux[i]); maxX = max(maxX, ux[i])
            minY = min(minY, uy[i]); maxY = max(maxY, uy[i])
        }
        val pad = s.halfWidth * 2f + 2f
        val rect = PDRectangle(minX - pad, minY - pad, (maxX - minX) + pad * 2, (maxY - minY) + pad * 2)
        val color = PDColor(
            floatArrayOf(Color.red(s.color) / 255f, Color.green(s.color) / 255f, Color.blue(s.color) / 255f),
            PDDeviceRGB.INSTANCE
        )

        // 외형: 필압 구간마다 선 굵기를 바꿔 그린다. 이 스트림은 자원(투명도 설정)을 받을 자리로만 쓰고,
        // 그리기 명령은 바이트로 받아 한 번에 압축해 둔다 (굵기가 바뀌는 펜은 구간이 많다)
        val ap = PDAppearanceStream(doc)
        ap.resources = PDResources()
        val plain = w.plain
        plain.reset()
        PDPageContentStream(doc, ap, plain).use { cs ->
            if (s.tool == Tool.HIGHLIGHTER) {
                val gs = PDExtendedGraphicsState()
                gs.strokingAlphaConstant = HL_ALPHA
                gs.blendMode = BlendMode.MULTIPLY
                cs.setGraphicsStateParameters(gs)
            }
            if (s.pen == PenStyle.CALLIGRAPHY && s.outlined) {
                // 캘리그래피: 화면과 같은 외곽선(펜촉 조각들)을 채운다
                cs.setNonStrokingColor(color)
                val tx = FloatArray(1)
                val ty = FloatArray(1)
                PenOutline.build(s, object : OutlineSink {
                    override fun moveTo(x: Float, y: Float) {
                        frame.toUser(x, y, tx, ty, 0); cs.moveTo(tx[0], ty[0])
                    }
                    override fun lineTo(x: Float, y: Float) {
                        frame.toUser(x, y, tx, ty, 0); cs.lineTo(tx[0], ty[0])
                    }
                    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = lineTo(x3, y3)
                    override fun close() = cs.closePath()
                })
                cs.fill()
            } else if (s.outlined) {
                // 굵기가 바뀌는 둥근 펜: 굵기를 잘게 나눈 구간마다 둥근 선으로 (화면의 외곽선과 거의 같고 파일이 작다).
                // 연필의 옅기는 주석 전체의 /CA로 (구간이 겹친 곳이 진해지지 않게). 결무늬는 이 앱에서만
                cs.setStrokingColor(color)
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                PenOutline.forEachWidthGroup(s) { w, from, to ->
                    cs.setLineWidth(w)
                    cs.moveTo(ux[from], uy[from])
                    if (from == to) cs.lineTo(ux[from] + 0.01f, uy[from])
                    else for (k in from + 1..to) cs.lineTo(ux[k], uy[k])
                    cs.stroke()
                }
            } else {
                cs.setStrokingColor(color)
                if (s.dashed) cs.setLineDashPattern(s.dashIntervals(), 0f)
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                s.forEachGroup { w, from, to ->
                    cs.setLineWidth(w)
                    cs.moveTo(ux[from], uy[from])
                    if (from == to) cs.lineTo(ux[from] + 0.01f, uy[from])
                    else for (k in from + 1..to) cs.lineTo(ux[k], uy[k])
                    cs.stroke()
                }
            }
        }
        val look = w.pack()

        // 점 목록: 점마다 COSFloat를 만들지 않고 PDF 글로 한 번에 ([RawNumbers])
        val inkList = COSArray()
        inkList.add(RawNumbers(ux, uy, n))
        val bs = COSDictionary()
        bs.setFloat(COSName.W, s.width)
        if (s.dashed) {
            bs.setName(COSName.S, "D")
            bs.setItem(COSName.D, COSArray().apply { s.dashIntervals().forEach { add(COSFloat(it)) } })
        }
        val alpha = when {
            s.outlined && s.pen == PenStyle.PENCIL -> COSFloat(PenStyle.PENCIL_ALPHA)
            s.tool == Tool.HIGHLIGHTER -> COSFloat(HL_ALPHA)
            else -> null
        }
        return InkPart(
            version, frame.x0, frame.y0, frame.x1, frame.y1, frame.rot,
            rect.cosArray, color.toCOSArray(), COSString(namePrefix + nameCount.incrementAndGet()),
            inkList, bs, alpha, COSString(encode(s)), ap.resources.cosObject, look,
        )
    }

    /**
     * 점 목록 하나를 미리 PDF 글 "[x y x y ...]"로 만들어 둔 것. 파일에 쓸 때 그 바이트를 그대로 내보낸다.
     * 점마다 COSFloat(속에 BigDecimal과 글이 든다)를 두면 만들기도 느리고, 획 수천 개를 보관하면 수십 MB가 된다.
     * PDFBox의 COSWriter는 숫자를 writePDF로 쓰므로 배열 속 숫자 자리에 넣으면 배열 하나가 그대로 들어간다.
     * 파일에 쓰는 데에만 쓴다 (값으로 읽으면 0)
     */
    private class RawNumbers(ux: FloatArray, uy: FloatArray, n: Int) : COSFloat(0f) {
        private val bytes: ByteArray

        init {
            val sb = StringBuilder(n * 16 + 2)
            sb.append('[')
            for (i in 0 until n * 2) {
                // COSWriter처럼 숫자 10개마다 줄을 바꾼다
                if (i > 0) sb.append(if (i % 10 == 0) '\n' else ' ')
                sb.append(pdfNumber(if (i % 2 == 0) ux[i / 2] else uy[i / 2]))
            }
            sb.append(']')
            bytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        override fun writePDF(output: OutputStream) = output.write(bytes)
    }

    /**
     * COSFloat(v)가 파일에 쓰는 것과 같은 글 (String.valueOf → BigDecimal 소수 표기, ".0"이 아닌 끝의 0은 뺀다).
     * 무한·NaN은 PDF에 쓸 수 없으므로 COSFloat처럼 예외를 낸다
     */
    internal fun pdfNumber(v: Float): String {
        if (!v.isFinite()) throw NumberFormatException("PDF에 쓸 수 없는 수: $v")
        if (v == 0f) return "0.0"  // -0도 0.0으로
        val s = v.toString()
        if (s.indexOf('E') < 0) return s
        var plain = BigDecimal(s).toPlainString()
        if (plain.indexOf('.') >= 0) while (plain.endsWith("0") && !plain.endsWith(".0")) plain = plain.dropLast(1)
        return plain
    }

    /** 그림 주석(Stamp): 외형에 그림을 네 모서리에 맞춰 그려 넣는다 */
    private fun makeImageAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val img = s.image!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val rect = PDRectangle(ux.min(), uy.min(), ux.max() - ux.min(), uy.max() - uy.min())
        val x: PDImageXObject = if (img.bytes != null) JPEGFactory.createFromByteArray(doc, img.bytes)
        else LosslessFactory.createFromImage(doc, img.bitmap)

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            // 그림 공간의 (1,0)은 오른쪽 아래, (0,1)은 왼쪽 위, 원점은 왼쪽 아래 모서리
            cs.drawImage(x, Matrix(ux[2] - ux[3], uy[2] - uy[3], ux[0] - ux[3], uy[0] - uy[3], ux[3], uy[3]))
        }
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /**
     * 표 주석(Stamp): 외형에 제목 칸 색과 선을 벡터로 그려 넣는다 (다른 앱에서도 표가 그대로 보인다).
     * 이 앱에서 다시 고칠 수 있게 표 모양은 [KEY_NAME]에 담는다
     */
    private fun makeTableAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val t = s.table!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val pad = s.width * TABLE_HEAVY / 2f + 1f
        val rect = PDRectangle(ux.min() - pad, uy.min() - pad, ux.max() - ux.min() + pad * 2, uy.max() - uy.min() + pad * 2)
        // 표 좌표 → 사용자 좌표: 원점은 모서리 0, 가로는 모서리 1 쪽, 세로는 모서리 3 쪽
        val ax = (ux[1] - ux[0]) / t.width
        val ay = (uy[1] - uy[0]) / t.width
        val bx = (ux[3] - ux[0]) / t.height
        val by = (uy[3] - uy[0]) / t.height
        fun px(x: Float, y: Float) = ux[0] + ax * x + bx * y
        fun py(x: Float, y: Float) = uy[0] + ay * x + by * y
        val color = PDColor(
            floatArrayOf(Color.red(s.color) / 255f, Color.green(s.color) / 255f, Color.blue(s.color) / 255f),
            PDDeviceRGB.INSTANCE
        )
        val geo = t.geometry()
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            if (geo.shades.isNotEmpty()) {
                cs.saveGraphicsState()
                val gs = PDExtendedGraphicsState()
                gs.nonStrokingAlphaConstant = TABLE_SHADE_ALPHA
                cs.setGraphicsStateParameters(gs)
                cs.setNonStrokingColor(color)
                for (sh in geo.shades) {
                    cs.moveTo(px(sh.l, sh.t), py(sh.l, sh.t))
                    cs.lineTo(px(sh.r, sh.t), py(sh.r, sh.t))
                    cs.lineTo(px(sh.r, sh.b), py(sh.r, sh.b))
                    cs.lineTo(px(sh.l, sh.b), py(sh.l, sh.b))
                    cs.closePath()
                    cs.fill()
                }
                cs.restoreGraphicsState()
            }
            cs.setStrokingColor(color)
            cs.setLineCapStyle(2)
            cs.setLineJoinStyle(0)
            val w = s.width.coerceAtLeast(0.3f)
            for (heavy in booleanArrayOf(false, true)) {
                cs.setLineWidth(if (heavy) w * TABLE_HEAVY else w)
                for (l in geo.lines) {
                    if (l.heavy != heavy) continue
                    cs.moveTo(px(l.x0, l.y0), py(l.x0, l.y0))
                    cs.lineTo(px(l.x1, l.y1), py(l.x1, l.y1))
                    cs.stroke()
                }
            }
            cs.setLineWidth(w)
            cs.moveTo(px(0f, 0f), py(0f, 0f))
            cs.lineTo(px(t.width, 0f), py(t.width, 0f))
            cs.lineTo(px(t.width, t.height), py(t.width, t.height))
            cs.lineTo(px(0f, t.height), py(0f, t.height))
            cs.closePath()
            cs.stroke()
        }
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /**
     * 글 주석(Stamp). 글을 안드로이드 PDF 한 쪽(상자 크기)으로 그려 글꼴째 담고 ([renderTexts]),
     * 그 쪽을 폼으로 가져와 네 모서리에 맞춰 외형에 넣는다 (다른 앱에서도 글자가 그대로, 벡터로 보인다)
     */
    private fun makeTextAnnotation(doc: PDDocument, page: PDPage, s: Stroke, texts: TextForms): PDAnnotation {
        val t = s.text!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val rect = PDRectangle(ux.min(), uy.min(), ux.max() - ux.min(), uy.max() - uy.min())

        val form = texts.layer.importPageAsForm(texts.src!!, texts.index.getValue(s))

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        val w = t.boxW.toFloat()
        val h = t.boxH.toFloat()
        PDPageContentStream(doc, ap).use { cs ->
            // 폼 공간: 원점은 왼쪽 아래 모서리(3), 가로는 오른쪽 아래(2) 쪽, 세로는 왼쪽 위(0) 쪽
            cs.saveGraphicsState()
            cs.transform(Matrix((ux[2] - ux[3]) / w, (uy[2] - uy[3]) / w, (ux[0] - ux[3]) / h, (uy[0] - uy[3]) / h, ux[3], uy[3]))
            cs.drawForm(form)
            cs.restoreGraphicsState()
        }
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        s.link?.let { dict.setString(LINK_NAME, it) }
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /**
     * 포스트잇 메모: PDF 표준 메모 주석(/Text). 다른 PDF 앱에서도 메모 아이콘이 보이고 누르면 글(/Contents)이 열린다.
     * 외형은 접힌 포스트잇 (이 앱에서 펼치고 접은 상태·자리는 [KEY_NAME]에)
     */
    private fun makeNoteAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val n = s.note!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(2)
        val uy = FloatArray(2)
        toUser(s.x(0), s.y(0), box, rot, ux, uy, 0)
        toUser(s.x(0) + StickyNote.ICON, s.y(0) + StickyNote.ICON, box, rot, ux, uy, 1)
        val rect = PDRectangle(min(ux[0], ux[1]), min(uy[0], uy[1]), kotlin.math.abs(ux[1] - ux[0]), kotlin.math.abs(uy[1] - uy[0]))
        val c = s.color
        val rgb = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

        // 외형: 오른쪽 아래가 접힌 작은 포스트잇
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        val l = rect.lowerLeftX; val b = rect.lowerLeftY; val w = rect.width; val h = rect.height
        val fold = min(w, h) * 0.32f
        PDPageContentStream(doc, ap).use { cs ->
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
            cs.moveTo(l, b + h); cs.lineTo(l + w, b + h); cs.lineTo(l + w, b + fold); cs.lineTo(l + w - fold, b); cs.lineTo(l, b)
            cs.closePath()
            cs.fill()
            cs.setNonStrokingColor(rgb[0] * 0.85f, rgb[1] * 0.85f, rgb[2] * 0.85f)
            cs.moveTo(l + w, b + fold); cs.lineTo(l + w - fold, b + fold); cs.lineTo(l + w - fold, b)
            cs.closePath()
            cs.fill()
            cs.setStrokingColor(rgb[0] * 0.72f, rgb[1] * 0.72f, rgb[2] * 0.72f)
            cs.setLineWidth(0.8f)
            cs.addRect(l, b + fold, w, h - fold)
            cs.stroke()
            if (n.text.isNotEmpty()) {
                cs.setStrokingColor(rgb[0] * 0.4f, rgb[1] * 0.4f, rgb[2] * 0.4f)
                cs.setLineWidth(1.4f)
                cs.setLineCapStyle(1)
                for (k in 0 until 3) {
                    val y = b + h - h * (0.28f + 0.18f * k)
                    cs.moveTo(l + w * 0.2f, y)
                    cs.lineTo(l + w - w * (if (k == 2) 0.45f else 0.2f), y)
                }
                cs.stroke()
            }
        }
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Text"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.color = PDColor(rgb, PDDeviceRGB.INSTANCE)
        annot.contents = n.text
        annot.isPrinted = true
        annot.isNoZoom = true
        annot.isNoRotate = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setName(COSName.NAME, "Note")
        dict.setBoolean(COSName.getPDFName("Open"), false)
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /** 링크 단 글 자리에 웹 주소 링크 주석(/Link): 다른 PDF 앱에서도 누르면 열린다 */
    private fun makeLinkAnnotation(page: PDPage, s: Stroke, url: String): PDAnnotation {
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val link = PDAnnotationLink()
        link.rectangle = PDRectangle(ux.min(), uy.min(), ux.max() - ux.min(), uy.max() - uy.min())
        link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
        link.action = PDActionURI().apply { uri = url }
        link.isPrinted = true
        link.page = page
        link.cosObject.setString(KEY_NAME, LINK_MARK)
        return link
    }

    /**
     * 테이프 주석(Stamp): 외형에 테이프 영역을 바탕색으로 칠하고 그 위를 무늬(타일 무늬)로 한 번 더 칠한다.
     * 보이게 해 둔 테이프도 늘 가린 모습으로 저장한다 (파일을 다시 열면 가린 상태로 시작)
     */
    private fun makeTapeAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val style = s.tape!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        // 테이프 영역을 다각형들로 (사용자 좌표)
        val polys = ArrayList<FloatArray>()
        val tmpX = FloatArray(1)
        val tmpY = FloatArray(1)
        fun addPoly(xs: List<Float>) {
            if (xs.size < 6) return
            val out = FloatArray(xs.size)
            for (i in 0 until xs.size / 2) {
                toUser(xs[i * 2], xs[i * 2 + 1], box, rot, tmpX, tmpY, 0)
                out[i * 2] = tmpX[0]
                out[i * 2 + 1] = tmpY[0]
            }
            polys.add(out)
        }
        val outline = s.tapeOutline()
        if (style.rect && s.holes.isEmpty()) {
            addPoly((0 until s.count).flatMap { listOf(s.x(it), s.y(it)) })
        } else {
            // 곡선을 잘게 나눈 점들 (분수, x, y). 윤곽이 끊기는 곳은 같은 분수가 두 번 나온다
            val a = outline.approximate(0.2f)
            val cur = ArrayList<Float>()
            var i = 0
            while (i + 2 < a.size) {
                if (i > 0 && a[i] == a[i - 3] && (a[i + 1] != a[i - 2] || a[i + 2] != a[i - 1])) {
                    addPoly(cur)
                    cur.clear()
                }
                cur.add(a[i + 1]); cur.add(a[i + 2])
                i += 3
            }
            addPoly(cur)
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (pl in polys) for (k in 0 until pl.size / 2) {
            minX = min(minX, pl[k * 2]); maxX = max(maxX, pl[k * 2])
            minY = min(minY, pl[k * 2 + 1]); maxY = max(maxY, pl[k * 2 + 1])
        }
        if (polys.isEmpty()) { minX = 0f; minY = 0f; maxX = 1f; maxY = 1f }
        val rect = PDRectangle(minX - 1f, minY - 1f, maxX - minX + 2f, maxY - minY + 2f)

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        val evenOdd = (!style.rect || s.holes.isNotEmpty()) && (outline.fillType == android.graphics.Path.FillType.EVEN_ODD ||
            outline.fillType == android.graphics.Path.FillType.INVERSE_EVEN_ODD)
        val sb = StringBuilder()
        fun n(v: Float) = String.format(java.util.Locale.US, "%.3f", v)
        fun pathOps() {
            for (pl in polys) {
                sb.append(n(pl[0])).append(' ').append(n(pl[1])).append(" m\n")
                for (k in 1 until pl.size / 2) sb.append(n(pl[k * 2])).append(' ').append(n(pl[k * 2 + 1])).append(" l\n")
                sb.append("h\n")
            }
            sb.append(if (evenOdd) "f*\n" else "f\n")
        }
        val c = s.color
        sb.append("q\n")
        sb.append(n(Color.red(c) / 255f)).append(' ').append(n(Color.green(c) / 255f)).append(' ')
            .append(n(Color.blue(c) / 255f)).append(" rg\n")
        pathOps()
        if (style.pattern != TapePattern.SOLID) {
            val anchorX = FloatArray(1)
            val anchorY = FloatArray(1)
            toUser(s.x(0), s.y(0), box, rot, anchorX, anchorY, 0)
            val name = ap.resources.add(tilePattern(style.pattern, c, anchorX[0], anchorY[0]))
            sb.append("/Pattern cs /").append(name.name).append(" scn\n")
            pathOps()
        }
        sb.append("Q\n")
        ap.cosObject.createOutputStream().use { it.write(sb.toString().toByteArray(Charsets.US_ASCII)) }

        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /** 경로를 잘게 나눈 다각형들 (사용자 좌표) */
    private fun pathPolys(path: android.graphics.Path, box: PDRectangle, rot: Int): List<FloatArray> {
        val polys = ArrayList<FloatArray>()
        val tmpX = FloatArray(1)
        val tmpY = FloatArray(1)
        fun addPoly(xs: List<Float>) {
            if (xs.size < 6) return
            val out = FloatArray(xs.size)
            for (i in 0 until xs.size / 2) {
                toUser(xs[i * 2], xs[i * 2 + 1], box, rot, tmpX, tmpY, 0)
                out[i * 2] = tmpX[0]
                out[i * 2 + 1] = tmpY[0]
            }
            polys.add(out)
        }
        // 곡선을 잘게 나눈 점들 (분수, x, y). 윤곽이 끊기는 곳은 같은 분수가 두 번 나온다
        val a = path.approximate(0.2f)
        val cur = ArrayList<Float>()
        var i = 0
        while (i + 2 < a.size) {
            if (i > 0 && a[i] == a[i - 3] && (a[i + 1] != a[i - 2] || a[i + 2] != a[i - 1])) {
                addPoly(cur)
                cur.clear()
            }
            cur.add(a[i + 1]); cur.add(a[i + 2])
            i += 3
        }
        addPoly(cur)
        return polys
    }

    /**
     * 채우기 주석(Stamp): 외형에 영역을 곱하기로 칠한다 (아래 PDF의 선·글자가 그대로 보이게).
     * 무늬는 바탕 없이 무늬만 (타일 무늬)
     */
    private fun makeFillAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val style = s.fill!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val polys = ArrayList<FloatArray>()
        if (s.holes.isEmpty()) {
            // 윤곽 그대로 (짝홀 규칙)
            val tx = FloatArray(1)
            val ty = FloatArray(1)
            s.forEachContour { from, to ->
                val out = FloatArray((to - from + 1) * 2)
                for (k in from..to) {
                    toUser(s.x(k), s.y(k), box, rot, tx, ty, 0)
                    out[(k - from) * 2] = tx[0]
                    out[(k - from) * 2 + 1] = ty[0]
                }
                polys.add(out)
            }
        } else polys.addAll(pathPolys(s.fillShape(), box, rot))
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (pl in polys) for (k in 0 until pl.size / 2) {
            minX = min(minX, pl[k * 2]); maxX = max(maxX, pl[k * 2])
            minY = min(minY, pl[k * 2 + 1]); maxY = max(maxY, pl[k * 2 + 1])
        }
        if (polys.isEmpty()) { minX = 0f; minY = 0f; maxX = 1f; maxY = 1f }
        val rect = PDRectangle(minX - 1f, minY - 1f, maxX - minX + 2f, maxY - minY + 2f)

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        val gs = PDExtendedGraphicsState()
        gs.blendMode = if (style.opaque) BlendMode.NORMAL else BlendMode.MULTIPLY
        val gsName = ap.resources.add(gs)
        val sb = StringBuilder()
        fun n(v: Float) = String.format(java.util.Locale.US, "%.3f", v)
        val c = s.color
        sb.append("q\n/").append(gsName.name).append(" gs\n")
        if (style.pattern == FillPattern.SOLID) {
            sb.append(n(Color.red(c) / 255f)).append(' ').append(n(Color.green(c) / 255f)).append(' ')
                .append(n(Color.blue(c) / 255f)).append(" rg\n")
        } else {
            val anchorX = FloatArray(1)
            val anchorY = FloatArray(1)
            toUser(s.x(0), s.y(0), box, rot, anchorX, anchorY, 0)
            val pat = tilePattern(FillTile.shapes(style.pattern), FillTile.PERIOD, FillTile.inkColor(c), anchorX[0], anchorY[0])
            val name = ap.resources.add(pat)
            sb.append("/Pattern cs /").append(name.name).append(" scn\n")
        }
        for (pl in polys) {
            sb.append(n(pl[0])).append(' ').append(n(pl[1])).append(" m\n")
            for (k in 1 until pl.size / 2) sb.append(n(pl[k * 2])).append(' ').append(n(pl[k * 2 + 1])).append(" l\n")
            sb.append("h\n")
        }
        sb.append("f*\nQ\n")
        ap.cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(sb.toString().toByteArray(Charsets.US_ASCII)) }

        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /** 테이프 무늬 한 칸 (TapeTile.shapes)을 PDF 타일 무늬로. 무늬는 테이프의 첫 점 (x0, y0)에 붙인다 */
    private fun tilePattern(p: TapePattern, base: Int, x0: Float, y0: Float): PDTilingPattern =
        tilePattern(TapeTile.shapes(p), TapeTile.PERIOD, TapeTile.patternColor(base), x0, y0)

    /** 무늬 한 칸 ([shapes], 한 변 [s] pt)을 [c] 색의 PDF 타일 무늬로. 무늬는 (x0, y0)에 붙인다 */
    private fun tilePattern(shapes: List<TapeTile.Shape>, s: Float, c: Int, x0: Float, y0: Float): PDTilingPattern {
        val pat = PDTilingPattern()
        pat.paintType = PDTilingPattern.PAINT_COLORED
        pat.tilingType = PDTilingPattern.TILING_CONSTANT_SPACING
        pat.bBox = PDRectangle(0f, 0f, s, s)
        pat.xStep = s
        pat.yStep = s
        pat.cosObject.setItem(COSName.MATRIX, COSArray().apply {
            floatArrayOf(1f, 0f, 0f, 1f, x0, y0).forEach { add(COSFloat(it)) }
        })
        PDPatternContentStream(pat).use { cs ->
            cs.setNonStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
            // 칸 좌표는 아래로 갈수록 y가 커지므로 위아래를 뒤집어 넣는다
            for (sh in shapes) when (sh) {
                is TapeTile.Box -> cs.addRect(sh.l, s - sh.b, sh.r - sh.l, sh.b - sh.t)
                is TapeTile.Dot -> {
                    // 원: 베지어 네 개
                    val r = sh.r
                    val k = 0.5523f * r
                    val cx = sh.cx
                    val cy = s - sh.cy
                    cs.moveTo(cx + r, cy)
                    cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
                    cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
                    cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
                    cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
                    cs.closePath()
                }
                is TapeTile.Poly -> {
                    cs.moveTo(sh.pts[0], s - sh.pts[1])
                    for (i in 1 until sh.pts.size / 2) cs.lineTo(sh.pts[i * 2], s - sh.pts[i * 2 + 1])
                    cs.closePath()
                }
            }
            cs.fill()
        }
        return pat
    }

    /** 이 앱이 넣은 그림 주석에서 그림을 꺼낸다. JPEG면 원래 바이트도 같이 (다시 저장할 때 화질 그대로) */
    private fun readImage(a: PDAnnotation): InkImage? = try {
        val res = a.normalAppearanceStream?.resources
        val x = res?.xObjectNames?.firstNotNullOfOrNull { res.getXObject(it) as? PDImageXObject }
        if (x == null) null
        else {
            val bytes = if (x.suffix == "jpg") x.cosObject.createRawInputStream().use { it.readBytes() } else null
            InkImage(x.image, bytes)
        }
    } catch (e: Exception) {
        null
    }

    /** 화면 좌표(보이는 페이지의 왼쪽 위 원점) → PDF 사용자 좌표 (페이지 회전 고려) */
    private fun toUser(x: Float, y: Float, b: PDRectangle, rot: Int, ux: FloatArray, uy: FloatArray, i: Int) =
        toUser(x, y, b.lowerLeftX, b.lowerLeftY, b.upperRightX, b.upperRightY, rot, ux, uy, i)

    /** 자르기 상자 (x0, y0)~(x1, y1)인 쪽에서 화면 좌표 → PDF 사용자 좌표 */
    private fun toUser(x: Float, y: Float, x0: Float, y0: Float, x1: Float, y1: Float, rot: Int, ux: FloatArray, uy: FloatArray, i: Int) {
        when (rot) {
            90 -> { ux[i] = x0 + y; uy[i] = y0 + x }
            180 -> { ux[i] = x1 - x; uy[i] = y0 + y }
            270 -> { ux[i] = x1 - y; uy[i] = y1 - x }
            else -> { ux[i] = x0 + x; uy[i] = y1 - y }
        }
    }

    // 형식: 1|P|ff000000|1.2|x,y,p;x,y,p;...  (도구: P 펜(사인펜), H 형광펜, D 점선 펜, I 그림, T 글, K 테이프 — 그림·글은 네 모서리)
    // 다른 펜 종류는 PenStyle.code: B 볼펜, F 만년필, R 붓펜, C 연필, G 캘리그래피 (예전 버전은 모르는 글자를 사인펜으로 읽는다)
    // 그림은 끝에 오답노트 이름표(|WH번호 · |WS번호)가 붙을 수 있다
    // 글은 굵기 자리에 글자 크기, 끝에 |글(UTF-8 Base64)|줄 바꾸는 폭|서식(JSON, UTF-8 Base64)을 붙인다
    // 테이프는 끝에 |R(네모) 또는 P(펜)|무늬 이름, 지우개로 뚫은 구멍이 있으면 |x,y,r;x,y,r;... 을 붙인다
    // 채우기(A)는 점들이 윤곽들 (필압 1이 윤곽의 첫 점), 끝에 |무늬 이름, 구멍이 있으면 |x,y,r;... 을 붙인다
    // 표(X)는 점이 네 모서리, 굵기 자리에 선 굵기, 끝에 |표 모양 ([InkTable.encode])
    // 포스트잇 메모(N)는 점 두 개(접힌 자리, 펼친 자리), 끝에 |글(UTF-8 Base64)|가로,세로|C(접힘) 또는 O(펼침)
    /** 보관함([InkCache])이 쓰는 획 글 ↔ 획 (그림 바이트와 글에 단 링크는 따로 다룬다) */
    internal fun encodeStroke(s: Stroke): String = encode(s)

    internal fun decodeStroke(str: String): Stroke? = decode(str)

    private fun encode(s: Stroke): String {
        val sb = StringBuilder(s.count * 16 + 32)
        val kind = when {
            s.note != null -> 'N'
            s.image != null -> 'I'
            s.text != null -> 'T'
            s.table != null -> 'X'
            s.tape != null -> 'K'
            s.fill != null -> 'A'
            s.tool == Tool.HIGHLIGHTER -> 'H'
            s.dashed -> 'D'
            else -> s.pen.code
        }
        sb.append("1|").append(kind).append('|')
        sb.append(Integer.toHexString(s.color)).append('|').append(r2(s.text?.size ?: s.width)).append('|')
        for (i in 0 until s.count) {
            if (i > 0) sb.append(';')
            sb.append(r2(s.x(i))).append(',').append(r2(s.y(i))).append(',').append(r2(s.p(i)))
        }
        if (s.image != null) s.role?.let { sb.append('|').append(it) }
        s.table?.let { sb.append('|').append(it.encode()) }
        s.text?.let {
            sb.append('|').append(Base64.encodeToString(it.text.toByteArray(), Base64.NO_WRAP))
            val rich = !it.rich.isPlain
            if (it.wrap < InkText.NO_WRAP || rich) sb.append('|').append(r2(it.wrap))
            if (rich) sb.append('|').append(Base64.encodeToString(it.rich.toJson().toByteArray(), Base64.NO_WRAP))
        }
        fun holes() {
            if (s.holes.isEmpty()) return
            sb.append('|')
            for (i in s.holes.indices step 3) {
                if (i > 0) sb.append(';')
                sb.append(r2(s.holes[i])).append(',').append(r2(s.holes[i + 1])).append(',').append(r2(s.holes[i + 2]))
            }
        }
        s.tape?.let {
            sb.append('|').append(if (it.rect) 'R' else 'P').append('|').append(it.pattern.name)
            holes()
        }
        s.fill?.let {
            // 덮는 채우기는 이름 뒤에 '!'
            sb.append('|').append(it.pattern.name).append(if (it.opaque) "!" else "")
            holes()
        }
        s.note?.let {
            sb.append('|').append(Base64.encodeToString(it.text.toByteArray(), Base64.NO_WRAP))
            sb.append('|').append(r2(it.w)).append(',').append(r2(it.h))
            sb.append('|').append(if (it.collapsed) 'C' else 'O')
        }
        return sb.toString()
    }

    private fun decode(str: String): Stroke? = try {
        val parts = str.split('|')
        if (parts.size < 5 || parts[0] != "1") null
        else {
            val tool = when (parts[1]) {
                "H" -> Tool.HIGHLIGHTER
                "K" -> Tool.TAPE
                "A" -> Tool.FILL
                else -> Tool.PEN
            }
            val color = parts[2].toLong(16).toInt()
            val isText = parts[1] == "T"
            val isNote = parts[1] == "N"
            val pen = if (tool == Tool.PEN && !isNote && parts[1].length == 1) PenStyle.of(parts[1][0]) ?: PenStyle.FELT else PenStyle.FELT
            val s = Stroke(tool, color, if (isText) 0f else parts[3].toFloat(), dashed = parts[1] == "D", pen = pen)
            val fast = PointText.parse(parts[4])
            if (fast != null) s.adopt(fast)
            else for (pt in parts[4].split(';')) {
                val v = pt.split(',')
                if (v.size == 3) s.add(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
            }
            if (tool == Tool.TAPE) {
                s.tape = TapeStyle(TapePattern.of(parts.getOrNull(6)), parts.getOrNull(5) == "R")
                parts.getOrNull(7)?.split(';')?.forEach { h ->
                    val v = h.split(',')
                    if (v.size == 3) s.withHole(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())?.let { s.copyHolesFrom(it) }
                }
            }
            if (tool == Tool.FILL) {
                val fillName = parts.getOrNull(5)
                s.fill = FillStyle(FillPattern.of(fillName?.removeSuffix("!")), fillName?.endsWith("!") == true)
                parts.getOrNull(6)?.split(';')?.forEach { h ->
                    val v = h.split(',')
                    if (v.size == 3) s.withHole(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())?.let { s.copyHolesFrom(it) }
                }
            }
            if (parts[1] == "I") s.role = parts.getOrNull(5)?.takeIf { it.isNotEmpty() }
            val isTable = parts[1] == "X"
            if (isTable) s.table = parts.getOrNull(5)?.let { InkTable.decode(it) }
            if (isText) {
                val txt = String(Base64.decode(parts[5], Base64.NO_WRAP))
                val rich = parts.getOrNull(7)?.let { RichDoc.fromJson(txt, String(Base64.decode(it, Base64.NO_WRAP))) }
                s.text = InkText(rich ?: RichDoc.plain(txt), parts[3].toFloat(), parts.getOrNull(6)?.toFloatOrNull() ?: InkText.NO_WRAP)
            }
            if (isNote) {
                val txt = String(Base64.decode(parts[5], Base64.NO_WRAP))
                val size = parts.getOrNull(6)?.split(',')
                val w = size?.getOrNull(0)?.toFloatOrNull() ?: StickyNote.DEFAULT_W
                val h = size?.getOrNull(1)?.toFloatOrNull() ?: StickyNote.DEFAULT_H
                s.note = StickyNote(txt, w, h).also { it.collapsed = parts.getOrNull(7) == "C" }
            }
            s.trim()
            if (s.count > 0 && (!isText || s.count >= 4) && (!isNote || s.count >= 2) && (!isTable || (s.count >= 4 && s.table != null))) s else null
        }
    } catch (e: Exception) {
        null
    }

    private fun r2(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()
}
