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

/** 一条成形笔迹 */
data class Stroke(val page: Long, val pen: Pen, val pts: List<Pt3>)

/** 一条自由文字笔记（坐标与笔迹同系＝页内归一化） */
data class TextNote(val id: String, val page: Long, val nx: Float, val ny: Float, val text: String)

/** 一个笔迹图层（r/g/b 只是列表里的色点标识，与笔画墨色无关） */
data class Layer(val r: Int, val g: Int, val b: Int, val visible: Boolean, val name: String)

/** 环形选笔盘的一个扇区（kind 见 RK_*；kind≠RK_PEN 时 pen 是占位值） */
data class RadialItem(val kind: Int, val pen: Pen)
