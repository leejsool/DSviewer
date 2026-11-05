package com.dsviewer.app

/** 목록 정렬 기준. [descFirst]면 처음 고를 때 큰 것(최근 것)부터 */
internal enum class SortKey(val label: String, val descFirst: Boolean) {
    OPENED("연 날짜", true), MODIFIED("수정한 날짜", true), NAME("이름", false), SIZE("크기", true), TYPE("종류", false);

    /** (오름차순, 내림차순) 이름 */
    val dirLabels get() = when (this) {
        OPENED, MODIFIED -> "오래된 것 먼저" to "최근 것 먼저"
        NAME -> "가나다순" to "가나다 거꾸로"
        SIZE -> "작은 것 먼저" to "큰 것 먼저"
        TYPE -> "PDF 먼저" to "그림 먼저"
    }
}

/**
 * 문서 목록을 어떤 순서로 보여 줄지의 계산: 정렬, 이 탭에서 고를 수 있는 기준·형식, 저장해 둔 값 읽기, 이름 검색, 칸 수.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object ListSort {

    /** 정렬에 필요한 것만 꺼내 주는 창구 (목록 줄 클래스가 구현한다) */
    interface Fields<T> {
        fun title(r: T): String
        /** 폴더·저장소 줄인가 (문서보다 앞에 둔다) */
        fun isFolder(r: T): Boolean
        /** 저장소(드라이브) 줄인가 (하나라도 있으면 순서를 건드리지 않는다) */
        fun isDrive(r: T): Boolean
        fun opened(r: T): Long
        fun modified(r: T): Long
        fun size(r: T): Long
        /** '종류' 정렬 순서 (이름으로 형식을 알아내 순위로) */
        fun typeRank(r: T): Int
    }

    /** 이 탭에서 고를 수 있는 정렬 기준. '연 날짜'는 최근 파일·즐겨찾기 탭에서만 의미가 있다 */
    fun keysFor(opensMatter: Boolean): List<SortKey> =
        if (opensMatter) SortKey.entries.toList() else SortKey.entries.filter { it != SortKey.OPENED }

    /**
     * 저장해 둔 정렬 기준 이름 [savedName]을 읽는다: 모르는 이름이거나 이 탭에서 못 쓰는 기준이면 [default]
     */
    fun resolveKey(savedName: String?, allowed: List<SortKey>, default: SortKey): SortKey {
        val k = savedName?.let { n -> SortKey.entries.firstOrNull { it.name == n } }
        return k?.takeIf { it in allowed } ?: default
    }

    /** 이 탭에서 고를 수 있는 형식. 그림은 기기 사진이 모두 섞이지 않게 '모든 문서'에서는 뺀다 */
    fun groupsFor(isAllDocsTab: Boolean): List<DocGroup> =
        if (isAllDocsTab) DocGroup.entries.filter { it != DocGroup.IMAGE } else DocGroup.entries

    /** 보여 줄 형식: 저장해 둔 이름들 [saved]이 없거나 [all]과 겹치는 것이 없으면 모두 */
    fun shownGroups(all: List<DocGroup>, saved: Set<String>?): List<DocGroup> {
        if (saved == null) return all
        return all.filter { it.name in saved }.ifEmpty { all }
    }

    /**
     * 목록을 정렬한다. 저장소 줄이 있으면 그대로. 폴더는 문서 앞에 ([foldersFirst]가 false면 문서 뒤에), 같은 값이면 이름순.
     * 폴더에는 크기·종류 기준이 의미가 없어 이름순으로 한다
     */
    fun <T> sort(
        rows: List<T>, key: SortKey, desc: Boolean, collator: Comparator<Any>, f: Fields<T>, foldersFirst: Boolean = true,
    ): List<T> {
        if (rows.any { f.isDrive(it) }) return rows
        val byName = Comparator<T> { a, b -> collator.compare(f.title(a), f.title(b)) }
        fun primary(k: SortKey): Comparator<T> = when (k) {
            SortKey.NAME -> byName
            SortKey.OPENED -> compareBy { f.opened(it) }
            SortKey.MODIFIED -> compareBy { f.modified(it) }
            SortKey.SIZE -> compareBy { f.size(it) }
            SortKey.TYPE -> compareBy { f.typeRank(it) }
        }
        fun ordered(k: SortKey) = (if (desc) primary(k).reversed() else primary(k)).then(byName)
        val (folders, docs) = rows.partition { f.isFolder(it) }
        val folderKey = if (key == SortKey.SIZE || key == SortKey.TYPE) null else key
        val sortedFolders = if (folderKey == null) folders.sortedWith(byName) else folders.sortedWith(ordered(folderKey))
        val sortedDocs = docs.sortedWith(ordered(key))
        return if (foldersFirst) sortedFolders + sortedDocs else sortedDocs + sortedFolders
    }

    /**
     * '직접 정한 순서': 폴더는 문서 앞에 ([foldersFirst]가 false면 뒤에), 각각 [order]에 적힌 차례대로. 적혀 있지 않은 줄(새로 즐겨찾기한 문서 등)은 뒤로 가고
     * 그들끼리는 들어온 차례([rows]의 차례)를 지킨다. [key]는 줄의 열쇠 (없으면 적혀 있지 않은 것으로 친다)
     */
    fun <T> sortManual(rows: List<T>, order: List<String>, key: (T) -> String?, f: Fields<T>, foldersFirst: Boolean = true): List<T> {
        val index = HashMap<String, Int>()
        order.forEachIndexed { i, k -> index.putIfAbsent(k, i) }
        val byOrder = compareBy<T> { r -> key(r)?.let { index[it] } ?: Int.MAX_VALUE }
        val (folders, docs) = rows.partition { f.isFolder(it) }
        return if (foldersFirst) folders.sortedWith(byOrder) + docs.sortedWith(byOrder) else docs.sortedWith(byOrder) + folders.sortedWith(byOrder)
    }

    /**
     * 끌어서 바꾼 뒤 보이는 줄들의 차례 [shown]을 전체 순서 [saved]에 합친다.
     * 보이지 않는 줄(다른 폴더 것)의 차례는 그대로 두고, 보이는 줄들은 새 차례로 뒤에 모은다
     */
    fun mergeOrder(saved: List<String>, shown: List<String>): List<String> {
        val now = shown.toSet()
        return saved.filter { it !in now } + shown.distinct()
    }

    /** 이름에 [query](앞뒤 공백 뺀)가 들어 있는 줄만. 비어 있으면 전부. 대소문자는 가리지 않는다 */
    fun <T> filterByTitle(rows: List<T>, query: String, f: Fields<T>): List<T> {
        val q = query.trim()
        return if (q.isEmpty()) rows else rows.filter { f.title(it).contains(q, ignoreCase = true) }
    }

    /**
     * 목록 칸 수. 1줄 보기는 한 칸, 2줄 보기는 두 칸.
     * 큰 썸네일로 볼 때는 가로 [listWidthPx]를 140dp 칸으로 나눈 수(최소 2), 아직 너비를 모르면(0 이하) 3
     */
    fun spanCount(view: ListView, listWidthPx: Int, density: Float): Int = when (view) {
        ListView.ONE -> 1
        ListView.TWO -> 2
        ListView.GRID -> if (listWidthPx <= 0) 3 else (listWidthPx / (140 * density)).toInt().coerceAtLeast(2)
    }
}

/** 탐색기 ⋮ ▸ 옵션 ▸ 목록 표시: 문서 종류를 어떻게 보일지 (예전 버전은 [BOTH]) */
enum class ExtShow(val label: String, val hint: String) {
    ICON("아이콘 표시", "이름 오른쪽의 ‘PDF’ 같은 종류 표시만 두고, 이름에서는 확장자를 뺍니다. (Summer.pdf → Summer)"),
    EXT("확장자 표시", "종류 표시 없이 이름에 확장자를 붙여 보입니다. (Summer.pdf)"),
    BOTH("모두 표시", "종류 표시도 두고 이름에도 확장자를 붙입니다.");

    /** 이름 오른쪽에 종류 표시를 둘지 */
    val badge get() = this != EXT

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ICON
    }
}

/** 탐색기 ⋮ ▸ 옵션 ▸ 목록 표시: 날짜를 어디까지 보일지 */
enum class DateShow(val label: String, val pattern: String) {
    DATE("연월일까지", "yyyy.MM.dd"),
    DATETIME("시간까지", "yyyy.MM.dd HH:mm");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DATETIME
    }
}

/** 폴더 계단(맨 위 폴더 … 지금 폴더)에서 실제로 늘어놓을 부분 */
internal object FolderChain {
    /** 한 번에 보이는 폴더 단계 수 (더 위의 폴더는 '…' 한 줄로 줄인다) */
    const val MAX_LEVELS = 3

    /** [n]단계 계단에서 보이기 시작하는 단계 번호 (그 앞은 '…'로 줄인다. 0이면 모두 보인다) */
    fun start(n: Int, max: Int = MAX_LEVELS): Int = (n - max).coerceAtLeast(0)
}

/** 목록에 보이는 문서 이름의 확장자 (종류 표시에 쓰는 1~4글자, 없으면 빈 글) */
internal object DocNames {
    fun ext(title: String): String = title.substringAfterLast('.', "").takeIf { it.length in 1..4 } ?: ""

    /** [show]가 아이콘 표시면 이름에서 확장자를 뺀다 (확장자뿐인 '.pdf' 같은 이름은 그대로) */
    fun shown(title: String, show: ExtShow): String {
        if (show != ExtShow.ICON) return title
        val e = ext(title)
        if (e.isEmpty()) return title
        val base = title.dropLast(e.length + 1)
        return base.ifEmpty { title }
    }
}

/** 탐색기 목록을 보는 방식 (오른쪽 위 보기 단추). 세로 화면·가로 화면마다 따로 고른다 */
enum class ListView(val label: String) {
    ONE("1줄 보기"),
    TWO("2줄 보기"),
    GRID("큰 썸네일로 보기");

    companion object {
        /**
         * 저장해 둔 이름 [name]의 보기. 고른 적이 없으면 예전 모습 그대로:
         * 큰 아이콘을 쓰던 사람([oldGrid])은 큰 썸네일, 아니면 세로 화면 1줄·가로 화면 2줄
         */
        fun of(name: String?, oldGrid: Boolean, landscape: Boolean): ListView =
            entries.firstOrNull { it.name == name } ?: when {
                oldGrid -> GRID
                landscape -> TWO
                else -> ONE
            }
    }
}

/**
 * 목록에서 꾹 눌러 끌어 여러 개 고를 때의 계산. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object DragSelect {

    /** 목록 위·아래 가장자리 이 높이(dp) 안에 손가락이 있으면 저절로 넘어간다 */
    const val EDGE_DP = 72f
    /** 가장자리 맨 끝에서의 최대 스크롤 속도 (dp / 프레임) */
    const val MAX_SPEED_DP = 28f

    /**
     * 누른 칸 [anchor]부터 지금 칸 [pos]까지(방향 무관)를 [base](누르기 전에 골라 둔 것)에 더해 고른다.
     * [keyAt]은 칸 번호로 고르는 열쇠를 돌려준다 (고를 수 없는 칸이나 범위 밖이면 null)
     */
    fun select(base: Set<String>, anchor: Int, pos: Int, keyAt: (Int) -> String?): Set<String> {
        val out = LinkedHashSet(base)
        for (i in minOf(anchor, pos)..maxOf(anchor, pos)) keyAt(i)?.let { out.add(it) }
        return out
    }

    /**
     * 손가락 높이 [y]에서의 자동 스크롤 거리 (px/프레임, 위쪽이면 음수). 가장자리([edge]px) 안쪽에서만, 끝에 가까울수록 빠르게.
     * 가운데면 0 (스크롤하지 않는다). [listHeight]는 목록 높이
     */
    fun autoScroll(y: Float, listHeight: Int, edge: Float, maxSpeed: Float): Float = when {
        y < edge -> -((edge - y) / edge * maxSpeed)
        y > listHeight - edge -> (y - (listHeight - edge)) / edge * maxSpeed
        else -> 0f
    }

    /** 꾹 누른 뒤 손을 떼거나 끌기가 끊겼을 때 할 일 */
    enum class End {
        /** 선택 모드를 켜고 누른 문서를 고른다 (꾹 누르고 끌지 않고 손을 뗐다) */
        ENTER_SELECTION,
        /** 끌어 온 문서(누른 문서 하나)를 폴더로 옮긴다 */
        MOVE_PRESSED,
        /** 선택 모드에서 끌어 온 고른 문서들을 폴더로 옮긴다 */
        MOVE_SELECTED,
        /** 아무 일도 없던 것으로 (끌다가 폴더가 아닌 곳에서 뗐거나, 끊겼다) */
        NOTHING,
    }

    /**
     * 꾹 누른 뒤의 끝에 할 일. [pressed]는 선택 모드를 켜기 전에 문서 하나를 꾹 누른 경우, [dragged]는 그러고 나서 끌기가 시작됐는지,
     * [up]은 손을 뗀 것(아니면 끊김), [overFolder]는 뗀 자리가 옮길 수 있는 폴더인지
     */
    fun end(pressed: Boolean, dragged: Boolean, up: Boolean, overFolder: Boolean): End = when {
        pressed && !dragged -> if (up) End.ENTER_SELECTION else End.NOTHING
        pressed -> if (up && overFolder) End.MOVE_PRESSED else End.NOTHING
        up && overFolder -> End.MOVE_SELECTED
        else -> End.NOTHING
    }

    /** 손가락이 위·아래 가장자리에 있어 자동 스크롤을 시작해야 하는가 */
    fun inEdge(y: Float, listHeight: Int, edge: Float): Boolean = y < edge || y > listHeight - edge
}
