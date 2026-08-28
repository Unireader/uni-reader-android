package com.xvan.unireader.shared

/**
 * 笔迹/工具的**中立模型**：模式1（本地开工作区）与模式2（连 Mac 当输入板）共用同一批类型，
 * 这样几何、输入、渲染、擦除命中、框选判定那一整套代码两种模式一份就够
 * （ANDROID-STANDALONE-PLAN.md §5.1）。
 *
 * 这些类型原先嵌在 `pad/WireCodec` 里，于是「绘制」被绑在了「线格式」上；模式1 根本不连 Mac，
 * 却要 import 一个协议编解码器才能画线。搬到 `shared` 后依赖方向理顺：`pad`/`local` → `shared`，
 * **`shared` 不许反向依赖任何一边**。
 *
 * 字段的字节布局仍由 `PROTOCOL.md §2` 规定（`WireCodec` 负责搬字节），本文件只管对象形状。
 * 坐标一律**页内归一化 [0,1]、左上原点**，与线格式、与 Mac、与 SQLite 里存的完全同系。
 */

// ---------- 工具模式（值 = 线格式 mode，PROTOCOL.md §2） ----------

const val MODE_NOTE = 0
const val MODE_ERASE = 1
const val MODE_PAGE = 2
const val MODE_LASSO = 3

// ---------- 环形选笔盘的扇区 kind（值 = 线格式 radial.kind，PROTOCOL.md §4.2） ----------

const val RK_PEN = 0
const val RK_ERASE = 1
const val RK_PAGE = 2
const val RK_SCRATCH = 3   // 新建草稿纸
const val RK_TEXT = 4      // 新建文字笔记

// ---------- 笔型编号 ↔ 名字：全 App 唯一转换点 ----------

/**
 * 线格式里笔型是 u8 编号（`PROTOCOL.md §2`），SQLite 的 `note.payload.type` 里是字符串
 * （Swift `PenBrushType` 的 rawValue）。**两者的映射只准在这里做一次**——散开就会出现
 * 「某处 2 是 marker、另一处 2 是 pencil」的错位，且笔迹落库后才发现。
 */
val BRUSH_NAMES = listOf("ballpoint", "fountain", "marker", "pencil")

/** 越界回退 0（同 Swift/JS 两端） */
fun brushName(code: Int): String = BRUSH_NAMES.getOrElse(code) { BRUSH_NAMES[0] }

/** 名字 → 编号（模式1 从库里读到的是名字），未知回退 0 */
fun brushCode(name: String): Int = BRUSH_NAMES.indexOf(name).let { if (it < 0) 0 else it }

// ---------- 数据模型 ----------

/** 一支笔：r/g/b 是 0~255，a 是 0~1，w 是 dp（≈ 网页 CSS px），brush 见 BRUSH_NAMES */
data class Pen(val r: Int, val g: Int, val b: Int, val a: Float, val w: Float, val brush: Int)

/** 带压感的点（页内归一化 + pressure 0~1） */
data class Pt3(val x: Float, val y: Float, val p: Float)

/** 无压感的点（擦除轨迹） */
data class Pt2(val x: Float, val y: Float)

/**
 * 一条成形笔迹。
 *
 * `id`/`layerId` 只有模式1（本地开工作区）会填：擦除要靠 `note.id` 一一映射删除、图层显隐要靠
 * `layerId` 过滤。模式2 里笔迹的真源在 Mac、删除也由 Mac 执行，线格式不传这两个字段，
 * 因此保持默认空串——**判空即「本端不掌握这条笔迹的身份」**，别拿空串当有效 id 用。
 *
 * 一个例外：模式2 里**刚收笔、还没等到 Mac 回推**的乐观笔迹带一个 `opt:` 开头的本地 id
 * （见 `PageCanvasView.pendingInk`）。它只用来在下一次全量回推时认领自己，不是真源身份——
 * 凡是要拿 id 去对库/对 Mac 的地方（如 `LocalCanvasView.onEraseEnd`）都只跑在模式1，见不到它。
 *
 * `padId` 非空时这一笔**画在草稿纸上**（`scratch_pad.id`，落库为 `note` kind=4 而非 kind=2），此时：
 *  · `pts` 是**画布坐标**（逻辑点/dp，可负无界），不是页内 0~1 归一化；
 *  · `page` 无意义（落库固定 0），`layerId` 也不参与（草稿纸不分图层）。
 * 坐标系契约见 Mac 端 `ScratchPadModel.swift` 文件头（三端一致，别改）。除此之外与页内笔迹
 * 完全同构——擦除（[InkEdit.splitStroke]）、四种笔型渲染原样复用。
 */
data class Stroke(
    val page: Long,
    val pen: Pen,
    val pts: List<Pt3>,
    val id: String = "",
    val layerId: String = "",
    /** 所属草稿纸 id；空串 = 画在 PDF 页面上（同 Mac `padId: UUID?` 的 nil） */
    val padId: String = "",
)

/**
 * 一条文字笔记（坐标与笔迹同系＝页内归一化）。
 *
 * `nx`/`ny` 是 **anchor 的左上角**，不是「图钉画在哪」——Mac 的 `broadcastNotes` 就是这么发的
 * （`anchor.minX/minY`），模式1 从库里读也取同两列，两模式的标记因此落在同一处。
 * Mac 自己的阅读区会把选区注解的图钉挪到行末右侧，那是 Mac 的显示偏好，不进这个模型。
 */
data class TextNote(
    val id: String,
    val page: Long,
    val nx: Float,
    val ny: Float,
    val text: String,
    /**
     * 展开方式（每条笔记自己的属性，与 Mac `NoteDisplay` 同值）：
     * `0`=点击展开 `1`=悬停展开（笔悬停；手指没有悬停 → 降级为点击）`2`=始终展开。
     * 线上是 `notes`/`textNote` 尾部的 u8，模式1 从 payload 的 `display` 键读；缺省一律 0。
     */
    val display: Int = NOTE_TAP,
)

const val NOTE_TAP = 0
const val NOTE_HOVER = 1
const val NOTE_ALWAYS = 2

/**
 * 一片**文字铺色**：kind=3 高亮的荧光底，或 kind=0 选区注解的类型色底（`PageCellView` 的最底两层）。
 *
 * `rects` 是页内归一化的逐行框 `[x, y, w, h]`（与 payload 里的存法一致，不转对角点——转来转去
 * 迟早有一处漏乘）；`a` 是**最终绘制透明度**，已经含了 Mac 的 `Highlight.fillOpacity`/注解 0.32
 * 那层口径（见 `PadConst.FILL`），绘制方直接用即可。
 *
 * 模式2 目前不用它（Mac 的页图是另一条管线，高亮画在 Mac 那边），列表恒空；放在这里是因为
 * 「页面上铺一层色」属于渲染层能力，不属于某一种模式。
 */
data class TextFill(
    val page: Long,
    val rects: List<FloatArray>,
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Float,
    /**
     * 这片色属于哪条 `note`。**只有 kind=0 的选区注解填**（高亮不参与框选移动，同 Mac
     * `applyLassoMove` 只动 strokes 与 textNotes）——框选拖动时靠它把底色和图钉一起挪，
     * 否则拖走的是图钉、底色留在原地。
     */
    val noteId: String = "",
)

/** 一个笔迹图层（r/g/b 只是列表里的色点标识，与笔画墨色无关） */
data class Layer(val r: Int, val g: Int, val b: Int, val visible: Boolean, val name: String)

/** 环形选笔盘的一个扇区（kind 见 RK_*；kind≠RK_PEN 时 pen 是占位值） */
data class RadialItem(val kind: Int, val pen: Pen)
