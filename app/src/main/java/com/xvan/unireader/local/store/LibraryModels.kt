package com.xvan.unireader.local.store

import com.xvan.unireader.shared.Layer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * `library.sqlite`（schema v7）的行模型，逐字对应 Mac 端 `Sources/Store/LibraryModels.swift`。
 *
 * **红线**：字段只能与 Mac 端一致，安卓不得新增列/表/payload 键。要加字段 = 先改
 * `REQUIREMENTS.md §8` + Mac 端 + 升 `schema_version`，两端一起动。
 */

/** 逻辑文档「一本书」。可含多个内容版本（variant），笔记全版本共用。 */
data class LibDocument(
    val id: String,
    val title: String,
    val pageCount: Int,
    val addedAt: String,
    val lastOpenedAt: String,
    val sortOrder: Int,
    /** 阅读进度：视口顶部所在页 */
    val readPage: Int,
    /** 阅读进度：页内归一化比例（0 顶 1 底） */
    val readFrac: Double,
    /** 上次缩放倍率（相对 fit-width，1=贴合宽度） */
    val readZoom: Double,
    /** 上次横向滚动比例（offsetX / pageW，缩放态才非 0） */
    val readHFrac: Double,
)

/** 一个内容版本＝一个 content hash（加 TOC 等致 hash 变即新增一个 variant）。 */
data class LibVariant(
    val id: String,
    val documentId: String,
    val contentHash: String,
    val pageCount: Int,
    val addedAt: String,
)

/**
 * 一个物理路径。
 * - `inWorkspace=true` → `path` 是**工作区相对路径**（如 `PDFs/xxx.pdf`），随文件夹搬动仍有效。
 * - `isRelative=true` → `path` 也相对工作区文件夹（可含 `..`），用于「外部文件但与工作区同在一块
 *   移动卷上」——与 `inWorkspace` 同一套拼接方式（见 [Workspace.resolvePdf]）。
 * - 都为 false → 绝对路径（Mac 上的绝对路径搬到安卓必然打不开，按失效处理）。
 */
data class LibLocation(
    val id: String,
    val variantId: String,
    val path: String,
    val isValid: Boolean,
    val lastValidatedAt: String?,
    val inWorkspace: Boolean,
    val isRelative: Boolean,
)

/** 笔记（挂逻辑文档，全版本共用）。payload 是 JSON 字节（列类型 BLOB）。 */
data class LibNote(
    val id: String,
    val documentId: String,
    /** 见 [NoteKind] */
    val kind: Int,
    val page: Int,
    /** 归一化包围盒 0~1，左上原点 */
    val anchorX: Double,
    val anchorY: Double,
    val anchorW: Double,
    val anchorH: Double,
    val payload: ByteArray,
    val createdAt: String,
    val updatedAt: String,
) {
    // payload 是 ByteArray，data class 的默认 equals 会比引用——手写成按内容比，
    // 否则「读出来又写回去」的去重判断会永远认为变了。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LibNote) return false
        return id == other.id && documentId == other.documentId && kind == other.kind &&
            page == other.page && anchorX == other.anchorX && anchorY == other.anchorY &&
            anchorW == other.anchorW && anchorH == other.anchorH &&
            payload.contentEquals(other.payload) &&
            createdAt == other.createdAt && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int = id.hashCode() * 31 + payload.contentHashCode()
}

/**
 * 一条文字注解的**锚定框**（`note` 的四个 anchor 列），框选移动的权威命中判定用。
 *
 * 渲染用的 `shared.TextNote` 只带落点一个坐标（线格式就只有 nx/ny），拿它判命中的话，
 * Mac 建的选区注解只有左上角那一个点落框才算中——所以判定这一步必须回库里取带宽高的 anchor。
 */
data class NoteAnchor(val id: String, val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** 一次擦除对齐（[LibraryStore.reconcileStrokes]）改动了多少行，只用来打点/决定要不要回推 */
data class InkDiff(val deleted: Int, val updated: Int, val inserted: Int) {
    val changed: Boolean get() = deleted + updated + inserted > 0
}

/** `note.kind`（Mac 端 `LibNote.kind` 的取值） */
object NoteKind {
    const val TEXT = 0
    const val CHAT = 1
    const val INK = 2
    const val HIGHLIGHT = 3
    /** 草稿纸上的笔迹（v8）。与页内笔迹（kind=2）分开，读取时一个 `kind ==` 就筛干净 */
    const val SCRATCH_INK = 4
}

/**
 * 一张**草稿纸**（`scratch_pad` 表，v8 建表 / v9 加 `pattern` 列）：盖在 PDF 之上的无限白板，
 * 不改 PDF 原文、也不属于任何一页。逐字对应 Mac 端 `LibScratchPad`（时间戳按本端惯例存 ISO 串）。
 *
 * - `anchorPage`/`anchorX`/`anchorY` = 创建时所在页 + 页内归一化点（0~1）——页面上那枚图钉的位置，
 *   **不是**纸的内容位置（纸上的笔迹是画布坐标，见 `note.payload` 的 `padId` 键与 handoff §1）。
 * - `bg` 是自由 CSS rgba 串（默认 `rgba(255,255,255,1.0)` 纯白），`pattern` 是
 *   `plain`/`dots`/`grid`（老库/老行缺省 → 读取侧兜底 `dots`）。
 * - 列表一律 `ORDER BY created_at ASC`（照抄 Mac，两端顺序不一致「第 2 张纸」就不是同一张）。
 */
data class ScratchPad(
    val id: String,
    val documentId: String,
    /** 标题（空 = 界面按创建序显示「草稿纸 N」，由 UI 兜底） */
    val title: String,
    val anchorPage: Int,
    val anchorX: Double,
    val anchorY: Double,
    val bg: String,
    val pattern: String,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        const val DEFAULT_BG = "rgba(255,255,255,1.0)"
        const val DEFAULT_PATTERN = "dots"

        /** 底纹取值（Mac `ScratchPattern.rawValue`）；线上是 u8 `0=plain 1=dots 2=grid` */
        val PATTERNS = listOf("plain", "dots", "grid")

        /** 未知/缺失的底纹串兜底 dots（同 Mac `ScratchPattern(rawValue:) ?? .dots`） */
        fun patternOrDefault(s: String): String = if (s in PATTERNS) s else DEFAULT_PATTERN
    }
}

/** 一个笔迹图层（`ink_layer` 表，v7）。`colorKey` 是色板 key，不是 RGB——见 [Palette]。 */
data class LibInkLayer(
    val id: String,
    val documentId: String,
    val name: String,
    val colorKey: String,
    val sortOrder: Int,
    val visible: Boolean,
    val createdAt: String,
) {
    companion object {
        /**
         * 旧笔迹的隐式归属层：payload 里没有 `layerId` 键时兜底到这个固定 id
         * （Mac 端 `InkLayer.defaultID`）。
         */
        const val DEFAULT_ID = "00000000-0000-0000-0000-000000000001"
    }
}

/**
 * 图层表 → 面板用的中立模型（`shared.Layer`，色点用 [Palette] 换算）。
 *
 * 面板（`shared/PadPanels.showLayerPanel`）是**按下标**交互的——线格式就是按下标发的，模式1
 * 也照这个接口用。所以给面板的列表与宿主用来换 id 的列表**必须是同一个列表映射出来的**；
 * 各自查一次库看着等价，实则给了两条能分叉的路，而错位的表现是「点了图层 2 改到了图层 3」。
 */
fun List<LibInkLayer>.toUiLayers(): List<Layer> = map {
    val rgb = Palette.rgb(it.colorKey)
    Layer(rgb[0], rgb[1], rgb[2], it.visible, it.name)
}

/**
 * 图层色点的色板：`ink_layer.color_key` 存的是 `"red"` 这种**字符串键**，而线格式的 `Layer`
 * 原语是 u8 r/g/b。这张表是 Mac 端 `NoteType.palette` 的搬运件——两边必须一致，否则同一个
 * 工作区在 Mac 与平板上图层列表的色点不是一个颜色。
 */
object Palette {
    private val map = linkedMapOf(
        "red" to intArrayOf(255, 59, 48),
        "orange" to intArrayOf(255, 149, 0),
        "yellow" to intArrayOf(255, 204, 0),
        "green" to intArrayOf(52, 199, 89),
        "blue" to intArrayOf(0, 122, 255),
        "purple" to intArrayOf(175, 82, 222),
        "pink" to intArrayOf(255, 45, 85),
        "gray" to intArrayOf(142, 142, 147),
    )

    val keys: List<String> get() = map.keys.toList()

    /** 未知 key（手改坏/跨端未同步）回落 gray，与 Mac 端 `paletteRGB` 同款兜底。 */
    fun rgb(key: String): IntArray = map[key] ?: map.getValue("gray")

    /** 新建图层的默认色：按已有图层数量轮换（同 Mac `InkLayer.rotatingColorKey`）。 */
    fun rotatingKey(existingCount: Int): String = keys[existingCount % keys.size]
}

/**
 * 时间戳格式。
 *
 * **必须带三位毫秒**：Mac 端的 `ISO8601DateFormatter` 开了 `.withFractionalSeconds`，
 * 没有小数秒的字符串它**解析不出来**（`ISO.date()` 返回 nil → 行映射里静默回落成「现在」）。
 * 所以安卓写库时不能用 `Instant.toString()`——它在毫秒为 0 时会省掉小数部分。
 */
object Iso {
    private val fmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun now(): String = string(Instant.now())

    fun string(t: Instant): String = fmt.format(t)

    /** 解析失败返回 null（坏数据不该让整库打不开） */
    fun instant(s: String?): Instant? = try {
        if (s.isNullOrEmpty()) null else Instant.parse(s)
    } catch (e: DateTimeParseException) {
        null
    }
}
