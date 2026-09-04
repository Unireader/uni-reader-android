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
 * 不许手写 XML。所以这张表只在**已经有对应图标**时才映射，其余一律回落通用
 * （画布上画成 `PadOverlays` 里那个手绘的笔记图标，等同 Mac 的 `note.text`）。
 *
 * 还没有对应图标的 SF Symbol（`exclamationmark.triangle` / `questionmark.circle` / `flag` /
 * `star` / `play.rectangle` / `lightbulb` / `flame` / `quote.bubble` / `tag`）记在 `../TODO.md`：
 * 它们只影响**在 Mac 上给笔记设过自定义类型**的那些图钉长什么样（底色仍然按类型走，是对的），
 * 平板上新建的笔记一律是通用类型，看不到差别。
 */
object NoteTypeIcons {

    /** SF Symbol 名 → 本端 drawable。表里没有的返回 null = 用通用图标 */
    private val MAP = mapOf(
        "note.text" to null,                       // 通用：画布自己画（与 Mac 的默认图标同形）
        "bookmark" to R.drawable.ic_bookmark,
        "book" to R.drawable.ic_book,
        "eye" to R.drawable.ic_eye,
        "checkmark.circle" to R.drawable.ic_check,
        "pencil.line" to R.drawable.ic_pen,
        "xmark.octagon" to R.drawable.ic_close,
    )

    /**
     * `type_id（大写） → SF Symbol 名` 转成 `type_id（大写） → Drawable`。
     * 映射不到的**不进结果**，画布那边取不到就用通用图标。
     */
    fun resolve(ctx: Context, byType: Map<String, String>): Map<String, Drawable> {
        if (byType.isEmpty()) return emptyMap()
        val out = HashMap<String, Drawable>(byType.size)
        for ((typeId, symbol) in byType) {
            val res = MAP[symbol] ?: continue
            // mutate()：图钉画之前会 setTint，不 mutate 会把同一份共享 ConstantState 染给别处
            val d = ContextCompat.getDrawable(ctx, res)?.mutate() ?: continue
            out[typeId] = d
        }
        return out
    }
}
