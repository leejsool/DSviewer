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
     * 목록을 정렬한다. 저장소 줄이 있으면 그대로. 폴더는 문서 앞에, 같은 값이면 이름순.
     * 폴더에는 크기·종류 기준이 의미가 없어 이름순으로 한다
     */
    fun <T> sort(rows: List<T>, key: SortKey, desc: Boolean, collator: Comparator<Any>, f: Fields<T>): List<T> {
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
        return sortedFolders + docs.sortedWith(ordered(key))
    }

    /** 이름에 [query](앞뒤 공백 뺀)가 들어 있는 줄만. 비어 있으면 전부. 대소문자는 가리지 않는다 */
    fun <T> filterByTitle(rows: List<T>, query: String, f: Fields<T>): List<T> {
        val q = query.trim()
        return if (q.isEmpty()) rows else rows.filter { f.title(it).contains(q, ignoreCase = true) }
    }

    /**
     * 목록 칸 수. 목록으로 볼 때는 가로 화면이면 두 칸(오른쪽도 쓴다), 세로면 한 칸.
     * 큰 아이콘으로 볼 때는 가로 [listWidthPx]를 140dp 칸으로 나눈 수(최소 2), 아직 너비를 모르면(0 이하) 3
     */
    fun spanCount(grid: Boolean, landscape: Boolean, listWidthPx: Int, density: Float): Int {
        if (!grid) return if (landscape) 2 else 1
        if (listWidthPx <= 0) return 3
        return (listWidthPx / (140 * density)).toInt().coerceAtLeast(2)
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

    /** 손가락이 위·아래 가장자리에 있어 자동 스크롤을 시작해야 하는가 */
    fun inEdge(y: Float, listHeight: Int, edge: Float): Boolean = y < edge || y > listHeight - edge
}
