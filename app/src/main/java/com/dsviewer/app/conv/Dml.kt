package com.dsviewer.app.conv

import android.graphics.Color
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** DrawingML(오피스 그림 언어) 공통: 테마 색·글꼴, 색 변환, 도형 모양 */
class DmlTheme(root: XNode?) {
    /** 테마 색 (dk1, lt1, accent1 …) */
    val colors = HashMap<String, Int>()
    var majorLatin = ""
    var majorEa = ""
    var minorLatin = ""
    var minorEa = ""
    /** 선 모양 목록 (lnRef idx 1부터) 의 굵기 (EMU) */
    val lineWidths = ArrayList<Int>()

    init {
        val el = root?.child("themeElements")
        el?.child("clrScheme")?.children?.forEach { c ->
            Dml.baseColor(c.children.firstOrNull(), null, emptyMap())?.let { colors[c.name] = it }
        }
        el?.child("fontScheme")?.let { fs ->
            fun ea(f: XNode?): String {
                val e = f?.child("ea")?.get("typeface").orEmpty()
                if (e.isNotBlank()) return e
                return f?.children("font")?.firstOrNull { it["script"] == "Hang" }?.get("typeface").orEmpty()
            }
            majorLatin = fs.path("majorFont", "latin")?.get("typeface").orEmpty()
            minorLatin = fs.path("minorFont", "latin")?.get("typeface").orEmpty()
            majorEa = ea(fs.child("majorFont"))
            minorEa = ea(fs.child("minorFont"))
        }
        el?.path("fmtScheme", "lnStyleLst")?.children("ln")?.forEach { lineWidths.add(it.int("w", 9525)) }
    }

    /** "+mn-lt" 같은 테마 글꼴 이름을 실제 이름으로 */
    fun font(face: String?): String = when (face) {
        null -> ""
        "+mn-lt" -> minorLatin
        "+mn-ea" -> minorEa.ifBlank { minorLatin }
        "+mj-lt" -> majorLatin
        "+mj-ea" -> majorEa.ifBlank { majorLatin }
        "+mn-cs" -> minorLatin
        "+mj-cs" -> majorLatin
        else -> face
    }
}

object Dml {
    private val PRESET = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "gray" to 0x808080, "grey" to 0x808080, "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3,
        "orange" to 0xFFA500, "purple" to 0x800080, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "navy" to 0x000080,
        "darkBlue" to 0x00008B, "darkRed" to 0x8B0000, "darkGreen" to 0x006400, "lime" to 0x00FF00,
    )

    /** 색 요소(srgbClr, schemeClr …) → ARGB. [phClr]는 테마 모양의 자리 색, [scheme]은 이름 → 색 */
    fun baseColor(c: XNode?, phClr: Int?, scheme: Map<String, Int>): Int? {
        c ?: return null
        var argb: Int = when (c.name) {
            "srgbClr" -> parseHex(c["val"]) ?: return null
            "sysClr" -> parseHex(c["lastClr"]) ?: if (c["val"] == "window") Color.WHITE else Color.BLACK
            "schemeClr" -> if (c["val"] == "phClr") phClr ?: return null else scheme[c["val"]] ?: return null
            "prstClr" -> PRESET[c["val"]]?.let { it or 0xFF000000.toInt() } ?: Color.BLACK
            "scrgbClr" -> Color.rgb(
                (c.int("r") / 100000f * 255).roundToInt().coerceIn(0, 255),
                (c.int("g") / 100000f * 255).roundToInt().coerceIn(0, 255),
                (c.int("b") / 100000f * 255).roundToInt().coerceIn(0, 255),
            )
            "hslClr" -> hsl(c.int("hue") / 60000f, c.int("sat") / 100000f, c.int("lum") / 100000f)
            else -> return null
        }
        for (m in c.children) argb = modify(argb, m)
        return argb
    }

    /** 색을 담은 요소(solidFill 등) 안의 첫 색 */
    fun color(holder: XNode?, phClr: Int?, scheme: Map<String, Int>): Int? =
        holder?.children?.firstNotNullOfOrNull { baseColor(it, phClr, scheme) }

    fun parseHex(v: String?): Int? {
        if (v == null || v.length != 6) return null
        return v.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    private fun modify(argb: Int, m: XNode): Int {
        val v = m.int("val") / 100000f
        val a = Color.alpha(argb)
        var r = Color.red(argb) / 255f
        var g = Color.green(argb) / 255f
        var b = Color.blue(argb) / 255f
        when (m.name) {
            "alpha" -> return (argb and 0x00FFFFFF) or ((v * 255).roundToInt().coerceIn(0, 255) shl 24)
            "alphaMod" -> return (argb and 0x00FFFFFF) or ((a * v).roundToInt().coerceIn(0, 255) shl 24)
            "tint" -> { r += (1 - r) * (1 - v); g += (1 - g) * (1 - v); b += (1 - b) * (1 - v) }
            "shade" -> { r *= v; g *= v; b *= v }
            "lumMod", "lumOff", "satMod" -> {
                val hsl = toHsl(r, g, b)
                when (m.name) {
                    "lumMod" -> hsl[2] *= v
                    "lumOff" -> hsl[2] += v
                    "satMod" -> hsl[1] *= v
                }
                val c = hsl(hsl[0], hsl[1].coerceIn(0f, 1f), hsl[2].coerceIn(0f, 1f))
                return (c and 0x00FFFFFF) or (a shl 24)
            }
            "gray" -> { val l = r * 0.3f + g * 0.59f + b * 0.11f; r = l; g = l; b = l }
            "inv" -> { r = 1 - r; g = 1 - g; b = 1 - b }
            else -> return argb
        }
        return Color.argb(a, (r * 255).roundToInt().coerceIn(0, 255), (g * 255).roundToInt().coerceIn(0, 255), (b * 255).roundToInt().coerceIn(0, 255))
    }

    private fun toHsl(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0))
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return floatArrayOf(h, s, l)
    }

    /** h: 0~6 (60° 단위), s·l: 0~1 */
    private fun hsl(h: Float, s: Float, l: Float): Int {
        if (s <= 0f) {
            val v = (l * 255).roundToInt().coerceIn(0, 255)
            return Color.rgb(v, v, v)
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 6
            if (t >= 6) t -= 6
            return when {
                t < 1 -> p + (q - p) * t
                t < 3 -> q
                t < 4 -> p + (q - p) * (4 - t)
                else -> p
            }
        }
        return Color.rgb(
            (hue(h + 2) * 255).roundToInt().coerceIn(0, 255),
            (hue(h) * 255).roundToInt().coerceIn(0, 255),
            (hue(h - 2) * 255).roundToInt().coerceIn(0, 255),
        )
    }

    // ================= 도형 모양 =================

    /**
     * 미리 정한 도형(prstGeom)의 윤곽. 좌표는 (0,0)~(w,h). [adj]는 조절값(adj, adj1 …; 100000 = 100%).
     * 모르는 모양은 null (호출하는 쪽에서 네모로 그린다)
     */
    fun presetPath(prst: String, w: Float, h: Float, adj: Map<String, Int>): Path? {
        val ss = min(w, h)
        fun a(name: String, def: Int) = (adj[name] ?: (if (name == "adj1") adj["adj"] else null) ?: def) / 100000f
        fun poly(vararg pts: Float): Path = Path().apply {
            moveTo(pts[0] * w, pts[1] * h)
            var i = 2
            while (i + 1 < pts.size) { lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
            close()
        }
        fun absPoly(vararg pts: Float): Path = Path().apply {
            moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
            close()
        }
        return when (prst) {
            "triangle", "flowChartExtract" -> {
                val t = if (prst == "triangle") a("adj", 50000) else 0.5f
                poly(t, 0f, 1f, 1f, 0f, 1f)
            }
            "flowChartMerge" -> poly(0f, 0f, 1f, 0f, 0.5f, 1f)
            "rtTriangle" -> poly(0f, 0f, 1f, 1f, 0f, 1f)
            "diamond", "flowChartDecision" -> poly(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)
            "parallelogram", "flowChartInputOutput" -> {
                val o = (if (prst == "parallelogram") a("adj", 25000) else 0.2f) * ss
                absPoly(o, 0f, w, 0f, w - o, h, 0f, h)
            }
            "trapezoid", "flowChartManualOperation" -> {
                val o = (if (prst == "trapezoid") a("adj", 25000) else 0.2f) * ss
                if (prst == "trapezoid") absPoly(o, 0f, w - o, 0f, w, h, 0f, h) else absPoly(0f, 0f, w, 0f, w - o, h, o, h)
            }
            "pentagon" -> regular(5, w, h, -90f)
            "hexagon" -> {
                val o = a("adj", 25000) * ss
                absPoly(o, 0f, w - o, 0f, w, h / 2, w - o, h, o, h, 0f, h / 2)
            }
            "heptagon" -> regular(7, w, h, -90f)
            "octagon" -> {
                val o = a("adj", 29289) * ss
                absPoly(o, 0f, w - o, 0f, w, o, w, h - o, w - o, h, o, h, 0f, h - o, 0f, o)
            }
            "decagon" -> regular(10, w, h, 0f)
            "dodecagon" -> regular(12, w, h, 0f)
            "star4" -> star(4, w, h, a("adj", 12500) * 2)
            "star5" -> star(5, w, h, a("adj", 19098) * 2)
            "star6" -> star(6, w, h, a("adj", 28868) * 2)
            "star8" -> star(8, w, h, a("adj", 38250) * 2)
            "star10" -> star(10, w, h, a("adj", 42533) * 2)
            "star12" -> star(12, w, h, a("adj", 37500) * 2)
            "rightArrow", "leftArrow", "upArrow", "downArrow" -> {
                val body = a("adj1", 50000)
                val head = a("adj2", 50000)
                arrow(prst, w, h, body, head * ss)
            }
            "leftRightArrow" -> {
                val body = a("adj1", 50000)
                val head = min(a("adj2", 50000) * ss, w / 2)
                val t = h * (1 - body) / 2
                absPoly(0f, h / 2, head, 0f, head, t, w - head, t, w - head, 0f, w, h / 2, w - head, h, w - head, h - t, head, h - t, head, h)
            }
            "upDownArrow" -> {
                val body = a("adj1", 50000)
                val head = min(a("adj2", 50000) * ss, h / 2)
                val t = w * (1 - body) / 2
                absPoly(w / 2, 0f, w, head, w - t, head, w - t, h - head, w, h - head, w / 2, h, 0f, h - head, t, h - head, t, head, 0f, head)
            }
            "chevron" -> {
                val o = min(a("adj", 50000) * ss, w)
                absPoly(0f, 0f, w - o, 0f, w, h / 2, w - o, h, 0f, h, o, h / 2)
            }
            "homePlate", "flowChartOffpageConnector" -> {
                val o = min(a("adj", 50000) * ss, w)
                if (prst == "homePlate") absPoly(0f, 0f, w - o, 0f, w, h / 2, w - o, h, 0f, h)
                else poly(0f, 0f, 1f, 0f, 1f, 0.8f, 0.5f, 1f, 0f, 0.8f)
            }
            "plus", "flowChartSummingJunction" -> {
                val o = a("adj", 25000) * ss
                absPoly(o, 0f, w - o, 0f, w - o, o, w, o, w, h - o, w - o, h - o, w - o, h, o, h, o, h - o, 0f, h - o, 0f, o, o, o)
            }
            "mathPlus" -> plusThin(w, h, a("adj1", 23520))
            "mathMinus" -> {
                val t = a("adj1", 23520) * h / 2
                absPoly(w * 0.1f, h / 2 - t, w * 0.9f, h / 2 - t, w * 0.9f, h / 2 + t, w * 0.1f, h / 2 + t)
            }
            "flowChartPunchedCard" -> poly(0.2f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f, 0.2f)
            "snip1Rect" -> {
                val o = a("adj", 16667) * ss
                absPoly(0f, 0f, w - o, 0f, w, o, w, h, 0f, h)
            }
            "snip2SameRect" -> {
                val o = a("adj1", 16667) * ss
                absPoly(o, 0f, w - o, 0f, w, o, w, h, 0f, h, 0f, o)
            }
            "flowChartAlternateProcess", "round1Rect", "round2SameRect", "roundRect" -> null
            "flowChartTerminator" -> Path().apply {
                val r = min(h / 2, w / 2)
                addRoundRect(android.graphics.RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            "can", "flowChartMagneticDisk" -> Path().apply {
                val ry = min(h * 0.1f, h / 4) * (if (prst == "can") a("adj", 25000) / 0.25f else 1f)
                val top = android.graphics.RectF(0f, 0f, w, ry * 2)
                val bottom = android.graphics.RectF(0f, h - ry * 2, w, h)
                moveTo(0f, ry)
                arcTo(top, 180f, 180f)
                lineTo(w, h - ry)
                arcTo(bottom, 0f, 180f)
                close()
                addOval(top, Path.Direction.CW)
            }
            "donut" -> Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addOval(android.graphics.RectF(0f, 0f, w, h), Path.Direction.CW)
                val t = a("adj", 25000) * ss
                addOval(android.graphics.RectF(t, t, w - t, h - t), Path.Direction.CW)
            }
            "frame" -> Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, w, h, Path.Direction.CW)
                val t = a("adj1", 12500) * ss
                addRect(t, t, w - t, h - t, Path.Direction.CW)
            }
            "wedgeRectCallout", "wedgeRoundRectCallout" -> {
                // 말풍선: 몸통 + 꼬리
                val tx = w / 2 + a("adj1", -20833) * w
                val ty = h / 2 + a("adj2", 62500) * h
                Path().apply {
                    if (prst == "wedgeRoundRectCallout") {
                        val r = ss * 0.16667f
                        addRoundRect(android.graphics.RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
                    } else addRect(0f, 0f, w, h, Path.Direction.CW)
                    val bx = w * 0.5f
                    moveTo(bx - w * 0.1f, if (ty > h) h else 0f)
                    lineTo(tx, ty)
                    lineTo(bx + w * 0.1f, if (ty > h) h else 0f)
                    close()
                }
            }
            "wedgeEllipseCallout" -> {
                val tx = w / 2 + a("adj1", -20833) * w
                val ty = h / 2 + a("adj2", 62500) * h
                Path().apply {
                    addOval(android.graphics.RectF(0f, 0f, w, h), Path.Direction.CW)
                    moveTo(w * 0.4f, h * 0.5f)
                    lineTo(tx, ty)
                    lineTo(w * 0.6f, h * 0.5f)
                    close()
                }
            }
            "cloud", "cloudCallout" -> Path().apply {
                // 구름: 겹친 원들
                val cs = listOf(0.3f to 0.35f, 0.55f to 0.25f, 0.78f to 0.4f, 0.7f to 0.7f, 0.42f to 0.75f, 0.2f to 0.6f)
                for ((cx, cy) in cs) addOval(android.graphics.RectF((cx - 0.22f) * w, (cy - 0.25f) * h, (cx + 0.22f) * w, (cy + 0.25f) * h), Path.Direction.CW)
            }
            "heart" -> Path().apply {
                moveTo(w / 2, h * 0.25f)
                cubicTo(w * 0.5f, 0f, 0f, 0f, 0f, h * 0.3f)
                cubicTo(0f, h * 0.6f, w * 0.4f, h * 0.8f, w / 2, h)
                cubicTo(w * 0.6f, h * 0.8f, w, h * 0.6f, w, h * 0.3f)
                cubicTo(w, 0f, w * 0.5f, 0f, w / 2, h * 0.25f)
                close()
            }
            "pie", "chord", "arc", "blockArc" -> null
            else -> null
        }
    }

    private fun plusThin(w: Float, h: Float, t0: Float): Path {
        val t = t0 * min(w, h) / 2
        val cx = w / 2
        val cy = h / 2
        val e = min(w, h) * 0.4f
        return Path().apply {
            moveTo(cx - t, cy - e); lineTo(cx + t, cy - e); lineTo(cx + t, cy - t); lineTo(cx + e, cy - t)
            lineTo(cx + e, cy + t); lineTo(cx + t, cy + t); lineTo(cx + t, cy + e); lineTo(cx - t, cy + e)
            lineTo(cx - t, cy + t); lineTo(cx - e, cy + t); lineTo(cx - e, cy - t); lineTo(cx - t, cy - t); close()
        }
    }

    private fun regular(n: Int, w: Float, h: Float, startDeg: Float): Path = Path().apply {
        for (i in 0 until n) {
            val t = Math.toRadians((startDeg + 360.0 * i / n))
            val x = w / 2 + w / 2 * cos(t).toFloat()
            val y = h / 2 + h / 2 * sin(t).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun star(n: Int, w: Float, h: Float, inner: Float): Path = Path().apply {
        val k = inner.coerceIn(0.05f, 1f)
        for (i in 0 until n * 2) {
            val t = Math.toRadians(-90.0 + 180.0 * i / n)
            val rr = if (i % 2 == 0) 1f else k
            val x = w / 2 + w / 2 * rr * cos(t).toFloat()
            val y = h / 2 + h / 2 * rr * sin(t).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun arrow(kind: String, w: Float, h: Float, body: Float, head0: Float): Path {
        // 오른쪽 화살표를 기준으로 만들고 돌린다
        val horizontal = kind == "rightArrow" || kind == "leftArrow"
        val len = if (horizontal) w else h
        val thick = if (horizontal) h else w
        val head = min(head0, len)
        val t = thick * (1 - body) / 2
        val pts = floatArrayOf(0f, t, len - head, t, len - head, 0f, len, thick / 2, len - head, thick, len - head, thick - t, 0f, thick - t)
        val p = Path()
        for (i in 0 until pts.size / 2) {
            var x = pts[i * 2]
            var y = pts[i * 2 + 1]
            when (kind) {
                "leftArrow" -> x = w - x
                "downArrow" -> { val ox = x; x = y; y = ox }
                "upArrow" -> { val ox = x; x = y; y = h - ox }
            }
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    /**
     * 자유 도형(custGeom)의 윤곽. 경로의 좌표계(w, h)를 도형 크기로 늘린다.
     * 곡선은 그대로, 호(arcTo)는 타원 호로 그린다.
     */
    fun customPath(geom: XNode, w: Float, h: Float): Path? {
        val lst = geom.child("pathLst") ?: return null
        val out = Path()
        var any = false
        for (p in lst.children("path")) {
            val pw = p.long("w").toFloat()
            val ph = p.long("h").toFloat()
            val kx = if (pw > 0) w / pw else 1f / 127f
            val ky = if (ph > 0) h / ph else 1f / 127f
            var cx = 0f
            var cy = 0f
            fun pt(n: XNode?): Pair<Float, Float> = (n?.long("x")?.toFloat() ?: 0f) * kx to (n?.long("y")?.toFloat() ?: 0f) * ky
            for (cmd in p.children) {
                val pts = cmd.children("pt").map { pt(it) }
                when (cmd.name) {
                    "moveTo" -> pts.firstOrNull()?.let { (x, y) -> out.moveTo(x, y); cx = x; cy = y; any = true }
                    "lnTo" -> pts.firstOrNull()?.let { (x, y) -> out.lineTo(x, y); cx = x; cy = y }
                    "cubicBezTo" -> if (pts.size == 3) {
                        out.cubicTo(pts[0].first, pts[0].second, pts[1].first, pts[1].second, pts[2].first, pts[2].second)
                        cx = pts[2].first; cy = pts[2].second
                    }
                    "quadBezTo" -> if (pts.size == 2) {
                        out.quadTo(pts[0].first, pts[0].second, pts[1].first, pts[1].second)
                        cx = pts[1].first; cy = pts[1].second
                    }
                    "arcTo" -> {
                        val wr = cmd.long("wR").toFloat() * kx
                        val hr = cmd.long("hR").toFloat() * ky
                        val st = cmd.long("stAng") / 60000f
                        val sw = cmd.long("swAng") / 60000f
                        // 지금 점이 타원 위 stAng 자리에 오도록 중심을 잡는다
                        val t = Math.toRadians(st.toDouble())
                        val ocx = cx - wr * cos(t).toFloat()
                        val ocy = cy - hr * sin(t).toFloat()
                        out.arcTo(android.graphics.RectF(ocx - wr, ocy - hr, ocx + wr, ocy + hr), st, sw)
                        val e = Math.toRadians((st + sw).toDouble())
                        cx = ocx + wr * cos(e).toFloat()
                        cy = ocy + hr * sin(e).toFloat()
                    }
                    "close" -> out.close()
                }
            }
        }
        return if (any) out else null
    }

    /** 조절값 목록 (avLst의 gd: name → "val 12345") */
    fun adjusts(prstGeom: XNode?): Map<String, Int> {
        val list = prstGeom?.child("avLst")?.children("gd") ?: return emptyMap()
        val m = HashMap<String, Int>()
        for (gd in list) {
            val f = gd["fmla"] ?: continue
            if (f.startsWith("val ")) f.removePrefix("val ").trim().toIntOrNull()?.let { m[gd["name"].orEmpty()] = it }
        }
        return m
    }

}
