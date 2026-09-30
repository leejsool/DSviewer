package com.dsviewer.app.conv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 작은 XML 나무. 오피스 문서(PPTX·DOCX)는 모양이 여러 곳(슬라이드·레이아웃·마스터, 스타일)에서
 * 이어받아지므로 한 번에 읽어 두고 이리저리 찾아보는 편이 쉽다.
 * 이름·속성은 접두사를 뗀 이름으로 쓴다 (a:t → "t", r:embed → "embed").
 */
class XNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XNode>(2)
    private var textBuf: StringBuilder? = null

    /** 바로 안의 글 (a:t, w:t 등) */
    val text: String get() = textBuf?.toString() ?: ""

    operator fun get(attr: String): String? = attrs[attr]

    fun child(name: String): XNode? = children.firstOrNull { it.name == name }
    fun children(name: String): List<XNode> = children.filter { it.name == name }

    /** 경로를 따라 내려간다: path("spPr", "xfrm", "off") */
    fun path(vararg names: String): XNode? {
        var n: XNode = this
        for (k in names) n = n.child(k) ?: return null
        return n
    }

    fun int(attr: String, def: Int = 0): Int = attrs[attr]?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: def
    fun long(attr: String, def: Long = 0): Long = attrs[attr]?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: def

    /** 켜고 끄는 속성 ("1", "true", "on"). 속성이 없으면 [def] */
    fun flag(attr: String, def: Boolean = false): Boolean = when (attrs[attr]) {
        null -> def
        "1", "true", "on" -> true
        else -> false
    }

    /** 안쪽 전체에서 이름이 [name]인 것들 (깊이 우선) */
    fun descendants(name: String, out: MutableList<XNode> = ArrayList()): List<XNode> {
        for (c in children) {
            if (c.name == name) out.add(c)
            c.descendants(name, out)
        }
        return out
    }

    companion object {
        fun parse(input: InputStream): XNode {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(input, null)
            val root = XNode("#root", emptyMap())
            val stack = ArrayList<XNode>().apply { add(root) }
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        val n = p.attributeCount
                        val attrs = if (n == 0) emptyMap() else HashMap<String, String>(n * 2).also { m ->
                            for (i in 0 until n) m[p.getAttributeName(i)] = p.getAttributeValue(i)
                        }
                        val node = XNode(p.name, attrs)
                        stack.last().children.add(node)
                        stack.add(node)
                    }
                    XmlPullParser.END_TAG -> stack.removeAt(stack.size - 1)
                    XmlPullParser.TEXT -> {
                        val top = stack.last()
                        (top.textBuf ?: StringBuilder().also { top.textBuf = it }).append(p.text)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return root.children.firstOrNull() ?: root
        }
    }
}

/** 오피스 꾸러미(zip) 읽기: 부분 XML과 관계(.rels) */
class OpcPackage(val zip: ZipFile) {
    private val xmlCache = HashMap<String, XNode?>()
    private val relCache = HashMap<String, Map<String, Rel>>()

    class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    fun has(path: String) = zip.getEntry(path) != null

    fun bytes(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun xml(path: String): XNode? = xmlCache.getOrPut(path) {
        zip.getEntry(path)?.let { e -> runCatching { zip.getInputStream(e).use { XNode.parse(it) } }.getOrNull() }
    }

    /** [part]의 관계들 (id → 대상 경로, 대상은 꾸러미 안 절대 경로로 바꿔 둔다) */
    fun rels(part: String): Map<String, Rel> = relCache.getOrPut(part) {
        val dir = part.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(relPath) ?: return@getOrPut emptyMap()
        root.children("Relationship").associate { r ->
            val ext = r["TargetMode"] == "External"
            val target = r["Target"] ?: ""
            r["Id"].orEmpty() to Rel(r["Id"].orEmpty(), r["Type"].orEmpty(), if (ext) target else resolve(dir, target), ext)
        }
    }

    fun relTarget(part: String, id: String?): String? = id?.let { rels(part)[it] }?.takeIf { !it.external }?.target

    /** 관계 종류 이름(끝부분, 예: "slideLayout")으로 첫 대상 */
    fun relByType(part: String, type: String): String? =
        rels(part).values.firstOrNull { !it.external && it.type.endsWith("/$type") }?.target

    companion object {
        fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (seg in target.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }
    }
}
