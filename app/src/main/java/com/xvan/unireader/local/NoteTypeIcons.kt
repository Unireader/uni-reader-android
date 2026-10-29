package com.xvan.unireader.local

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.xvan.unireader.R

/**
 * 笔记类型的图标：Mac 存的是 **SF Symbol 名**（`meta.note_types` 的 `icon_name`，
 * 候选集见 Mac `NoteType.iconCandidates`），本端把它映射成自己的 `ic_*`。
 *
 * 🔴 **图标是生成物**：本端的 `ic_*` 全部由 `tools/icons/gen.py` 一份几何源码生成，
 * 不许手写 XML。16 个候选（`NoteTypeDef.ICONS`）2026-10-29 起全有对应图标。
 * 图钉上的通用类型仍由画布自己画（`PadOverlays` 那个手绘的笔记图标，等同 Mac 的 `note.text`），
 * 类型管理面板里的通用图标用 `ic_note`（[drawableFor]）。
 */
object NoteTypeIcons {

    /** SF Symbol 名 → 本端 drawable（顺序同 `NoteTypeDef.ICONS`） */
    private val MAP = mapOf(
        "note.text" to R.drawable.ic_note,
        "exclamationmark.triangle" to R.drawable.ic_warn,
        "questionmark.circle" to R.drawable.ic_question,
        "bookmark" to R.drawable.ic_bookmark,
        "flag" to R.drawable.ic_flag,
        "star" to R.drawable.ic_star,
        "play.rectangle" to R.drawable.ic_play_rect,
        "lightbulb" to R.drawable.ic_bulb,
        "flame" to R.drawable.ic_flame,
        "checkmark.circle" to R.drawable.ic_check,
        "xmark.octagon" to R.drawable.ic_close,
        "quote.bubble" to R.drawable.ic_quote,
        "book" to R.drawable.ic_book,
        "tag" to R.drawable.ic_tag,
        "pencil.line" to R.drawable.ic_pen,
        "eye" to R.drawable.ic_eye,
    )

    /** 列表 / 网格里画一个类型图标用（未知名回落通用 `ic_note`，同 Mac `NoteType.icon`） */
    fun drawableFor(symbol: String): Int = MAP[symbol] ?: R.drawable.ic_note

    /**
     * `type_id（大写） → SF Symbol 名` 转成 `type_id（大写） → Drawable`。
     * 映射不到的**不进结果**，画布那边取不到就用通用图标。
     */
    fun resolve(ctx: Context, byType: Map<String, String>): Map<String, Drawable> {
        if (byType.isEmpty()) return emptyMap()
        val out = HashMap<String, Drawable>(byType.size)
        for ((typeId, symbol) in byType) {
            // 通用图标不进结果：图钉上的通用图标由画布自己画（与没设类型的笔记长得一样）
            if (symbol == "note.text") continue
            val res = MAP[symbol] ?: continue
            // mutate()：图钉画之前会 setTint，不 mutate 会把同一份共享 ConstantState 染给别处
            val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: continue
            out[typeId] = d
        }
        return out
    }
}
