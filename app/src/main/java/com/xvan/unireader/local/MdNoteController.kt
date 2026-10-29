package com.xvan.unireader.local

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.NoteNode
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.exercise.MathText
import java.io.File

/**
 * Markdown 笔记标签页的整页（`../MARKDOWN-NOTES-PLAN.md`，模式1；同 [ExerciseController] 那样铺在 chrome 之下）。
 *
 * - **阅读**：Markwon 渲染（公式同气泡 / 习题），`[[…]]` 可点（按名字解析，见 [MdNotes.Index]）、单独一行的
 *   `![[图]]` 画成图；**编辑**：原文输入框，停手 0.8 秒自动保存、切走 / 退到后台立即保存（同 Mac 三条）。
 * - 🔴 **只有用户在编辑框里改的才写回**（红线 9）；显示用的转换只在内存里。文件已不在就**不保存**
 *   （否则被删 / 被改名的笔记会在旧路径上被凭空建回来，同 Mac `MarkdownDocView.save`）。
 * - 每次切到这篇都重读文件（Mac 那边经离线镜像改过也能看到）；本地还有没存的输入时以本地为准。
 * - 目录扫描 + `md_doc` 对账在 [refresh]（后台扫目录，库队列上对账，同 Mac `refreshNotes`）。
 */
class MdNoteController(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/Md"
        private const val SAVE_DELAY_MS = 800L
    }

    val view = FrameLayout(a).apply {
        setBackgroundColor(Ui.surface(a))
        visibility = View.GONE
    }

    private val titleView = Ui.title(a, "", 20f).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val modeButton = Ui.textButton(a, "", primary = true) { toggleEdit() }
    private val readBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val readScroll = ScrollView(a).apply { addView(readBox) }
    private val edit = EditText(a).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP or Gravity.START
        textSize = 16f
        setTextColor(Ui.onSurface(a))
        background = null
        isSingleLine = false
        visibility = View.GONE
    }

    init {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 20)
            setPadding(p, Ui.dp(a, 12), p, 0)
        }
        column.addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
                addView(modeButton)
            },
        )
        column.addView(readScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(edit, LinearLayout.LayoutParams(-1, 0, 1f))
        // 平板上一行太长读起来累：正文栏最宽 760dp，居中
        view.addView(
            column,
            FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL),
        )
        view.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val maxW = Ui.dp(a, 760)
            val w = r - l
            val lp = column.layoutParams as FrameLayout.LayoutParams
            val want = if (w > maxW) maxW else -1
            if (lp.width != want) { lp.width = want; column.layoutParams = lp }
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                dirty = true
                handler.removeCallbacks(saveLater)
                handler.postDelayed(saveLater, SAVE_DELAY_MS)
            }
        })
    }

    private val handler = Handler(Looper.getMainLooper())
    private val saveLater = Runnable { save() }

    private var ws: File? = null
    private var queue: StoreQueue? = null

    /** 最近一次扫描（null = 还没扫 / 没开工作区） */
    var scan: MdNotes.Scan? = null
        private set
    private var index: MdNotes.Index? = null

    /** Mac 上引用的外部目录名（平板上打不开，抽屉里提一句） */
    var referenced: List<String> = emptyList()
        private set

    /** 当前开着的那篇（源内相对路径） */
    var current: String? = null
        private set
    private var dirty = false
    private var loading = false
    private var editing = false

    /** 点 `[[…]]` 解析到一篇笔记：宿主开它（已开着就切过去） */
    var onOpenNote: (String) -> Unit = {}
    /** 目录树变了（扫完一遍）：宿主刷抽屉 / 标签标题 */
    var onTreeChanged: () -> Unit = {}

    fun bind(q: StoreQueue?, dir: File?) {
        flush()
        queue = q
        ws = dir
        scan = null
        index = null
        current = null
        if (dir != null) refresh()
    }

    /** 后台扫一遍 `Notes/`，库队列上与 `md_doc` 对账，回主线程换上新树 */
    fun refresh(then: (() -> Unit)? = null) {
        val dir = ws ?: return
        val q = queue
        Bg.submit("扫 Markdown 笔记") {
            val s = MdNotes.scan(dir)
            val idx = MdNotes.Index(s)
            a.runOnUiThread {
                if (ws !== dir) return@runOnUiThread
                scan = s
                index = idx
                if (q == null) { onTreeChanged(); then?.invoke(); return@runOnUiThread }
                q.submit("md_doc 对账 ${s.notes.size} 篇", { st ->
                    runCatching { st.reconcileMdDocs(s.notes.associate { it.relPath to it.title }) }
                        .onFailure { Log.w(TAG, "md_doc 对账失败", it) }
                    st.meta("note_sources")
                }, { meta ->
                    referenced = MdNotes.referencedNames(meta)
                    onTreeChanged()
                    then?.invoke()
                })
            }
        }
    }

    fun exists(rel: String): Boolean = scan?.notes?.any { it.relPath == rel } == true

    fun titleOf(rel: String): String = MdNotes.Item(rel).title

    /** 抽屉要的先序摊平树（目录在前、笔记在后），[openRels] = 已在标签页里开着的 */
    fun nodes(openRels: Set<String>): List<NoteNode> {
        val root = scan?.root ?: return emptyList()
        val out = ArrayList<NoteNode>()
        fun walk(f: MdNotes.Folder, depth: Int) {
            for (sub in f.folders) {
                out.add(NoteNode(sub.path, sub.name, depth, true, false))
                walk(sub, depth + 1)
            }
            for (n in f.notes) out.add(NoteNode(n.relPath, n.title, depth, false, n.relPath in openRels))
        }
        walk(root, 0)
        return out
    }

    // ---------- 打开 / 关上 ----------

    /** 开一篇（每次都重读文件；本地有没存的输入且是同一篇时以本地为准） */
    fun open(rel: String) {
        if (current != rel) flush()
        val dir = ws ?: return
        val keepLocal = current == rel && dirty
        current = rel
        titleView.text = titleOf(rel)
        view.visibility = View.VISIBLE
        if (keepLocal) { showMode(); return }
        Bg.submit("读笔记 $rel") {
            val f = MdNotes.file(dir, rel)
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            a.runOnUiThread {
                if (current != rel) return@runOnUiThread
                if (text == null) {
                    Toast.makeText(a, a.getString(R.string.md_gone), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                loading = true
                edit.setText(text)
                loading = false
                dirty = false
                if (text.isEmpty()) editing = true   // 空笔记直接进编辑
                showMode()
            }
        }
        queue?.submit("记上次打开 $rel") { s -> runCatching { s.mdDocOpened(rel) } }
    }

    /** 切走 / 关掉：先存，再收起 */
    fun close() {
        flush()
        current = null
        editing = false
        hideKeyboard()
        view.visibility = View.GONE
    }

    /** 有没存的立刻存（切标签 / 退到后台 / 关工作区都走这里） */
    fun flush() {
        handler.removeCallbacks(saveLater)
        if (dirty) save()
    }

    private fun save() {
        val dir = ws ?: return
        val rel = current ?: return
        if (!dirty) return
        val f = MdNotes.file(dir, rel)
        if (!f.isFile) {   // 文件已不在：不在旧路径上把它建回来
            Log.w(TAG, "笔记已不在，不保存：$rel")
            dirty = false
            return
        }
        val text = edit.text.toString()
        runCatching { MdNotes.write(f, text) }
            .onSuccess {
                dirty = false
                queue?.submit("笔记存盘时间戳 $rel") { s -> runCatching { s.touchMdDoc(rel) } }
            }
            .onFailure { Log.e(TAG, "存笔记失败 $rel", it) }
    }

    // ---------- 阅读 / 编辑 ----------

    private fun toggleEdit() {
        if (editing) flush()
        editing = !editing
        showMode()
    }

    private fun showMode() {
        modeButton.text = a.getString(if (editing) R.string.md_done else R.string.md_edit)
        if (editing) {
            readScroll.visibility = View.GONE
            edit.visibility = View.VISIBLE
            edit.requestFocus()
        } else {
            hideKeyboard()
            edit.visibility = View.GONE
            readScroll.visibility = View.VISIBLE
            render(edit.text.toString())
        }
    }

    private fun hideKeyboard() {
        val imm = a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    private fun render(text: String) {
        readBox.removeAllViews()
        if (text.isBlank()) {
            readBox.addView(Ui.body(a, a.getString(R.string.md_empty)), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Ui.dp(a, 16)
            })
            return
        }
        for (seg in MdNotes.segments(text)) {
            when (seg) {
                is MdNotes.Segment.Text -> readBox.addView(
                    TextView(a).apply {
                        textSize = 16f
                        setTextColor(Ui.onSurface(a))
                        setLineSpacing(0f, 1.25f)
                        setTextIsSelectable(false)
                        MathText.bindNote(this, seg.md) { link -> onLink(link) }
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 8) },
                )
                // 图按原像素大小显示、宽过正文栏才缩（小图拉满一栏会糊）
                is MdNotes.Segment.Image -> readBox.addView(imageView(seg.target), LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = Ui.dp(a, 8)
                })
            }
        }
        readBox.addView(View(a), LinearLayout.LayoutParams(-1, Ui.dp(a, 48)))
    }

    /** 单独一行的 `![[图]]`：按附件解析，后台解码（找不到就写一行字） */
    private fun imageView(target: String): View {
        val dir = ws
        val rel = index?.file(target)
        if (dir == null || rel == null) {
            return Ui.body(a, a.getString(R.string.md_not_found, target))
        }
        val iv = ImageView(a).apply {
            adjustViewBounds = true
            maxWidth = Ui.dp(a, 760)
            scaleType = ImageView.ScaleType.FIT_START
            setOnClickListener { showAttachment(rel) }
        }
        Bg.submit("读笔记附件 $rel") {
            val bmp = ImageAssets.load(File(MdNotes.root(dir), rel), 2048)
            a.runOnUiThread { if (bmp != null) iv.setImageBitmap(bmp) }
        }
        return iv
    }

    private fun onLink(link: String) {
        when {
            link.startsWith(MdNotes.WIKI_SCHEME) -> {
                val target = MdNotes.decode(link.removePrefix(MdNotes.WIKI_SCHEME))
                val rel = index?.note(target)
                if (rel == null) Toast.makeText(a, a.getString(R.string.md_not_found, target), Toast.LENGTH_SHORT).show()
                else onOpenNote(rel)
            }
            link.startsWith(MdNotes.IMG_SCHEME) -> {
                val target = MdNotes.decode(link.removePrefix(MdNotes.IMG_SCHEME))
                val rel = index?.file(target)
                if (rel == null) Toast.makeText(a, a.getString(R.string.md_not_found, target), Toast.LENGTH_SHORT).show()
                else showAttachment(rel)
            }
            else -> runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { Log.w(TAG, "打不开链接 $link", it) }
        }
    }

    /** 附件：是图就看大图，别的文件交给系统 */
    private fun showAttachment(rel: String) {
        val dir = ws ?: return
        val f = File(MdNotes.root(dir), rel)
        if (f.extension.lowercase() !in ImageAssets.PICKABLE) {
            Toast.makeText(a, rel, Toast.LENGTH_SHORT).show()
            return
        }
        Bg.submit("读附件大图 $rel") {
            val bmp = ImageAssets.load(f, 2048)
            a.runOnUiThread {
                val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                if (bmp != null) {
                    root.addView(ImageView(a).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                    })
                } else {
                    root.addView(Ui.body(a, a.getString(R.string.img_missing)))
                }
                Sheet(a).size(720, 0.8f).title(f.name).content(root)
                    .action(a.getString(R.string.act_done), primary = true).show()
            }
        }
    }

    // ---------- 删除（进 `Notes/.trash/`） ----------

    /**
     * 删一篇 = 把文件**挪进** `Notes/.trash/`（Obsidian 自己的回收站目录；Mac 与本端扫描都跳过 `.` 开头的目录），
     * 不抹掉（红线 11：删除类操作先归档）。同名已在就加 -2。成功后重扫。
     */
    fun delete(rel: String, then: (Boolean) -> Unit) {
        val dir = ws ?: return then(false)
        if (rel == current) { handler.removeCallbacks(saveLater); dirty = false }
        val src = MdNotes.file(dir, rel)
        val bin = File(MdNotes.root(dir), ".trash").apply { mkdirs() }
        val used = bin.list()?.toSet().orEmpty()
        val name = MdNotes.availablePath(src.name, used)
        val ok = src.isFile && src.renameTo(File(bin, name))
        Log.i(TAG, "删笔记 $rel → .trash/$name ok=$ok")
        if (ok) refresh { then(true) } else then(false)
    }

    // ---------- 新建 / 改名 ----------

    /** 新建一篇空笔记（落在 `Notes/` 根，撞名加 -2），扫完交回相对路径（同 Mac `createNote`） */
    fun create(name: String, then: (String) -> Unit) {
        val dir = ws ?: return
        val clean = name.trim().ifEmpty { a.getString(R.string.md_untitled) }
        val used = scan?.notes?.map { it.relPath }?.toSet().orEmpty()
        val rel = MdNotes.availablePath(MdNotes.safeFileName(clean) + ".md", used)
        Bg.submit("新建笔记 $rel") {
            val ok = runCatching { MdNotes.write(MdNotes.file(dir, rel), "") }
                .onFailure { Log.e(TAG, "新建笔记失败 $rel", it) }.isSuccess
            a.runOnUiThread { if (ok) refresh { then(rel) } }
        }
    }

    /**
     * 改名 = 改文件名（同 Mac `renameNote`）。🔴 指向它的 `[[旧名字]]` 会断链——不改别人的正文（红线 9），
     * 这是用户明确接受的代价。目标已存在就不改。成功交回新相对路径。
     */
    fun rename(rel: String, name: String, then: (String?) -> Unit) {
        val dir = ws ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return then(null)
        if (rel == current) flush()
        val item = MdNotes.Item(rel)
        val ext = rel.substringAfterLast('.', "md")
        val newRel = (if (item.folder.isEmpty()) "" else item.folder + "/") + MdNotes.safeFileName(clean) + "." + ext
        if (newRel == rel) return then(rel)
        val dst = MdNotes.file(dir, newRel)
        if (dst.exists()) {
            Toast.makeText(a, a.getString(R.string.md_name_taken), Toast.LENGTH_SHORT).show()
            return then(null)
        }
        val ok = MdNotes.file(dir, rel).renameTo(dst)
        if (!ok) return then(null)
        if (current == rel) {
            current = newRel
            titleView.text = titleOf(newRel)
        }
        refresh { then(newRel) }
    }
}
