package com.dsviewer.app

/**
 * 터치가 들어왔을 때 '펜으로 쓸지, 지울지, 손가락 이동으로 볼지'를 가르는 규칙.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다 (액션 번호는 [android.view.MotionEvent]의 값을 그대로 받는다).
 */
internal object PenInputRules {

    /** 구형 삼성 S펜: 버튼을 누른 채 그리면 별도 액션 코드(211~213)로 들어온다 */
    const val SPEN_DOWN = 211
    const val SPEN_UP = 212
    const val SPEN_MOVE = 213

    /** 표준 액션 번호 (MotionEvent와 같은 값) */
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_POINTER_DOWN = 5

    /** 번역한 액션: 표준 액션 번호와 삼성 버튼을 눌렀는지 */
    class Action(val action: Int, val samsungButton: Boolean)

    /** 삼성 S펜 전용 코드(211~213)를 표준 액션(DOWN·UP·MOVE)과 '버튼 누름'으로 바꾼다. 다른 코드는 그대로 */
    fun translate(raw: Int): Action = when (raw) {
        SPEN_DOWN -> Action(ACTION_DOWN, true)
        SPEN_UP -> Action(ACTION_UP, true)
        SPEN_MOVE -> Action(ACTION_MOVE, true)
        else -> Action(raw, false)
    }

    /**
     * 지우개로 쓰는 입력인가: 삼성 버튼을 누른 채거나, 지우개 쪽 끝([isEraserTool])이거나,
     * 스타일러스 버튼([buttonState]에 [buttonMask] 중 하나가 있음)을 누른 채
     */
    fun isEraserInput(samsungButton: Boolean, isEraserTool: Boolean, buttonState: Int, buttonMask: Int): Boolean =
        samsungButton || isEraserTool || (buttonState and buttonMask != 0)

    /** 펜 옆 버튼을 누른 채 쓸 때 하는 일 (옵션에서 고른다). 지우개는 지금까지의 동작 */
    enum class ButtonAction(val label: String, val hint: String) {
        ERASER("지우개", "버튼을 누른 채 문지르면 지워집니다."),
        LASSO("올가미 선택", "버튼을 누른 채 끌면 선택합니다. 선택을 마치고 다른 곳을 쓰면 원래 도구로 돌아옵니다."),
        LASER("레이저", "버튼을 누른 채 끌면 레이저 포인터로 보입니다. 떼면 원래 도구로 돌아옵니다."),
        SWAP("직전 도구로 전환", "버튼을 누른 채 톡 치면 직전에 쓰던 도구와 번갈아 바뀝니다.");

        companion object {
            fun named(n: String?) = entries.firstOrNull { it.name == n } ?: ERASER
        }
    }

    /** 옆 버튼을 누른 채인가: 삼성 S펜 버튼 코드, 또는 [buttonState]에 [buttonMask] 중 하나가 있음 (지우개 쪽 끝은 따로 본다) */
    fun buttonDown(samsungButton: Boolean, buttonState: Int, buttonMask: Int): Boolean =
        samsungButton || (buttonState and buttonMask != 0)

    /** 이번 획을 지우개로 다루게 하는 펜 입력인가: 지우개 쪽 끝, 또는 버튼을 누른 채이고 버튼 동작이 지우개일 때 */
    fun eraserInput(eraserTip: Boolean, button: Boolean, action: ButtonAction): Boolean =
        eraserTip || (button && action == ButtonAction.ERASER)

    /** 버튼을 누른 채 톡 쳤는가: 전환 동작 중이고 끝까지 갔고 많이 움직이지 않았고 [tapMs] 안에 뗐을 때 */
    fun isSwapTap(swapping: Boolean, commit: Boolean, tapCandidate: Boolean, elapsedMs: Long, tapMs: Long): Boolean =
        swapping && commit && tapCandidate && elapsedMs in 0 until tapMs

    /**
     * 펜 입력을 새로 시작할 수 있는 때인가: 읽기 모드가 아니고, 이미 쓰는 중이 아니고, 첫 손가락·펜이 닿거나 다음 손가락이 닿을 때
     */
    fun mayStartPen(readOnly: Boolean, penActive: Boolean, action: Int): Boolean =
        !readOnly && !penActive && (action == ACTION_DOWN || action == ACTION_POINTER_DOWN)

    /**
     * 손가락으로 쓰는 입력인가: 스타일러스가 아니고, 첫 손가락이 닿은 것이고, 손가락 필기를 켜 두었거나
     * 선택한 부분 위를 눌렀을 때 (선택한 부분은 손가락 필기를 꺼 두어도 손가락으로 끌어 옮길 수 있다)
     */
    fun isFingerPen(stylus: Boolean, action: Int, isFinger: Boolean, fingerDrawing: Boolean, onSelection: Boolean): Boolean =
        !stylus && action == ACTION_DOWN && isFinger && (fingerDrawing || onSelection)

    /**
     * 이번 획을 지우개로 다룰지: 지우개 도구, 지우개로 켜 둔 테이프·채우기 도구, 또는 스타일러스의 지우개 입력
     */
    fun penErasing(tool: Tool, tapeErasing: Boolean, fillErasing: Boolean, stylus: Boolean, eraserInput: Boolean): Boolean =
        tool == Tool.ERASER || (tool == Tool.TAPE && tapeErasing) || (tool == Tool.FILL && fillErasing) || (stylus && eraserInput)

    /** 손바닥 지우기를 쓰는 도구 (펜·형광펜·보정 펜·지우개)인가: 손바닥 지우기를 켜 두었고 읽기 모드가 아닐 때 */
    fun palmToolOk(palmErase: Boolean, readOnly: Boolean, tool: Tool): Boolean =
        palmErase && !readOnly && (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.SHAPE || tool == Tool.ERASER)

    /** 지금 손가락 필기 중이라 손바닥으로 바꿀 수 있는가 */
    fun palmReady(fingerDrawing: Boolean, penIsFinger: Boolean, palmErasing: Boolean, palmToolOk: Boolean): Boolean =
        fingerDrawing && penIsFinger && !palmErasing && palmToolOk

    /** 압력: 스타일러스가 아니면(손가락) 0.6 고정, 스타일러스면 [raw]를 0~1로 */
    fun pressure(stylus: Boolean, raw: Float): Float = if (!stylus) 0.6f else raw.coerceIn(0f, 1f)
}
