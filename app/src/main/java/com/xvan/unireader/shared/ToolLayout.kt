package com.xvan.unireader.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具栏布局（2026-09-26 用户定：像 macOS 那样自由编组）。**纯数据**，两模式共用一份，记在本机
 * SharedPreferences `toolbars` 的 `layout` 键（不进库、不同步）。
 *
 * - 一颗工具（按钮 / 读数）用 key 标识，**只能在一个地方**：某一组里，或哪组都不在（= 显示在 ⋯ 菜单里）。
 * - 组有名字、有顺序；每组要么并在顶栏里（按组的先后从左往右排），要么浮着（记左上角位置）。
 * - [known] = 布局见过的全部 key：以后新加的工具不在里面，合并时按默认布局放进它的默认组；
 *   用户明确收进 ⋯ 的在里面、不在任何组里，合并时不会被「好心」放回去。
 * - 某个模式没有的 key（如模式1 的「切换工作区」在模式2 里）原样留在布局里，只是那边不显示。
 */
class ToolLayout(val groups: MutableList<Group>, val known: MutableSet<String>) {

    class Group(
        val id: String,
        /** 用户起的名字；空 = 用默认名（默认组按 id 取本地化文案，跟着系统语言走） */
        var name: String,
        val keys: MutableList<String>,
        var docked: Boolean,
        /** 浮着时的左上角（根坐标 px）；-1 = 没挪过，按默认位置摆 */
        var x: Int = -1,
        var y: Int = -1,
        /**
         * 固定（[PIN_NONE] / [PIN_LEFT] / [PIN_RIGHT]）：固定的组不显示把手、不能拖（只能在编辑面板里改）。
         * 并在顶栏里时，固定在左的排在最左、固定在右的排在 ⋯ 左边，都不跟着顶栏横向滚动；浮着的固定组钉在原位。
         */
        var pin: Int = PIN_NONE,
    ) {
        val pinned: Boolean get() = pin != PIN_NONE
    }

    fun groupOf(key: String): Group? = groups.firstOrNull { key in it.keys }

    /**
     * 把 [key] 挪到 [to] 组（[to] = null 表示收进 ⋯）。[index] 是**拿掉 [key] 之后**那一组里的下标，
     * 同组内挪位置也走这里。
     */
    fun move(key: String, to: Group?, index: Int) {
        groupOf(key)?.keys?.remove(key)
        to?.keys?.add(index.coerceIn(0, to.keys.size), key)
        known.add(key)
    }

    fun toJson(): String {
        val gs = JSONArray()
        for (g in groups) {
            gs.put(
                JSONObject()
                    .put("id", g.id).put("name", g.name).put("docked", g.docked)
                    .put("x", g.x).put("y", g.y).put("pin", g.pin)
                    .put("keys", JSONArray(g.keys)),
            )
        }
        return JSONObject().put("v", 1).put("groups", gs).put("known", JSONArray(known.toList())).toString()
    }

    fun copy(): ToolLayout = fromJson(toJson()) ?: this

    companion object {
        const val G_NAV = "nav"
        const val G_PEN = "pen"
        const val G_PAGE = "page"
        const val G_PAD = "pad"
        const val G_STATUS = "status"

        const val PIN_NONE = 0
        const val PIN_LEFT = 1
        const val PIN_RIGHT = 2

        /**
         * 默认布局 = 改之前的样子：顶栏从左到右「导航 / 笔工具 / 页面工具」三组，右侧固定一组「状态」（模式2 的延迟读数 +
         * 页码，原先就钉在 ⋯ 左边），画板 / 草稿纸控制栏浮在顶栏下方，原先 ⋯ 里的那些照旧在 ⋯ 里（不在任何组，但算「见过」）。
         */
        fun defaults(): ToolLayout = ToolLayout(
            mutableListOf(
                Group(G_NAV, "", mutableListOf("toc", "prev", "next"), docked = true),
                Group(
                    G_PEN, "",
                    mutableListOf("mode", "pen", "ruler", "undo", "redo", "clipCut", "clipCopy", "clipPaste"),
                    docked = true,
                ),
                Group(G_PAGE, "", mutableListOf("scratch", "lock", "canvas", "ref"), docked = true),
                Group(G_STATUS, "", mutableListOf("latency", "pageLabel"), docked = true, pin = PIN_RIGHT),
                Group(
                    G_PAD, "",
                    mutableListOf(
                        "padList", "padName", "padPage", "padRecenter", "padFit", "padMap",
                        "padPages", "padPageUnder", "padPaper", "padZoom", "padClose",
                    ),
                    docked = false,
                ),
            ),
            mutableSetOf(),
        ).also { l ->
            for (g in l.groups) l.known.addAll(g.keys)
            l.known.addAll(MENU_KEYS)
        }

        /** 默认收在 ⋯ 里的（原先就只在菜单里的那些） */
        val MENU_KEYS = listOf(
            "boardList", "night", "showPage", "twoFinger", "hLock", "layers", "gotoPage",
            "openDoc", "workspace", "hideBar", "conn",
        )

        fun fromJson(s: String?): ToolLayout? {
            if (s.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(s)
                val gs = o.getJSONArray("groups")
                val groups = ArrayList<Group>()
                for (i in 0 until gs.length()) {
                    val g = gs.getJSONObject(i)
                    val ks = g.getJSONArray("keys")
                    groups.add(
                        Group(
                            g.getString("id"), g.optString("name", ""),
                            MutableList(ks.length()) { ks.getString(it) },
                            g.optBoolean("docked", false), g.optInt("x", -1), g.optInt("y", -1),
                            // 上一版只有「固定 = 靠左」一个布尔（pinned），读到就当固定在左
                            g.optInt("pin", if (g.optBoolean("pinned", false)) PIN_LEFT else PIN_NONE),
                        ),
                    )
                }
                val kn = o.optJSONArray("known") ?: JSONArray()
                ToolLayout(groups, MutableList(kn.length()) { kn.getString(it) }.toMutableSet())
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 读出来的布局补上 [registered] 里**没见过**的 key：放进默认布局里它所在的那一组（按默认顺序插到它前一个
         * 邻居后面）；那一组在老布局里还没有（默认布局后来新加的组，如「状态」）就照默认建一组，
         * 用户自己删掉的默认组也会这样回来——但只在出现没见过的 key 时，平时不会。
         * 默认布局里没有它 → 收进 ⋯。返回是否改动过（改了要存回去）。
         */
        fun mergeNew(l: ToolLayout, registered: Collection<String>): Boolean {
            val def = defaults()
            var changed = false
            for (k in registered) {
                if (k in l.known) continue
                changed = true
                l.known.add(k)
                val dg = def.groupOf(k) ?: continue
                val g = l.groups.firstOrNull { it.id == dg.id }
                    ?: Group(dg.id, "", mutableListOf(), dg.docked, pin = dg.pin).also { l.groups.add(it) }
                val before = dg.keys.subList(0, dg.keys.indexOf(k)).lastOrNull { it in g.keys }
                g.keys.add(if (before == null) 0 else g.keys.indexOf(before) + 1, k)
            }
            return changed
        }
    }
}
