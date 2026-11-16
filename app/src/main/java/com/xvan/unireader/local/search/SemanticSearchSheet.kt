package com.xvan.unireader.local.search

import android.app.Activity
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.xvan.unireader.R
import com.xvan.unireader.local.server.ServerSync
import com.xvan.unireader.local.server.ServerSyncIO
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import java.io.File

/**
 * 搜索设置（`../docs/SEMANTIC-SYNC-PLAN.md §4.4`）：「同时按意思搜索」开关、模型一行（未下载 / 下载进度 / 已就位 + 删除）、
 * 向量一行（本工作区已同步多少条、上次出错）。入口在 Agent 面板 ⋯「搜索设置…」。[workspace] = 当前打开的工作区（null = 没有）。
 */
class SemanticSearchSheet(private val a: Activity, private val workspace: File?) {

    private val enabledBox = CheckBox(a).apply {
        text = a.getString(R.string.sem_enabled)
        setTextColor(Ui.onSurface(a))
    }
    private val modelLine = Ui.body(a, "", variant = false)
    private val modelBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
    private val modelButtons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
    private val sourceRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val vecLine = Ui.body(a, "", variant = false)
    private val vecErr = Ui.body(a, "").apply { textSize = 12f; visibility = View.GONE }

    /** 服务器工作区 id（后台读一次）；null = 不是服务器工作区 */
    @Volatile private var wid: String? = null
    private var widKnown = false
    private val listener: () -> Unit = { refresh() }
    private var wasInstalled = EmbedModel.isInstalled(a)

    fun show() {
        enabledBox.isChecked = SemanticVectors.enabled(a)
        enabledBox.setOnCheckedChangeListener { _, on ->
            SemanticVectors.setEnabled(a, on)
            if (on) pullNow()
        }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(enabledBox)
            addView(Ui.tip(a, a.getString(R.string.sem_tip)))
            addView(Ui.groupTitle(a, a.getString(R.string.sem_model_title)))
            addView(modelLine)
            addView(modelBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 6) })
            addView(sourceRow)
            addView(modelButtons, LinearLayout.LayoutParams(-1, -2))
            addView(Ui.groupTitle(a, a.getString(R.string.sem_vectors_title)))
            addView(vecLine)
            addView(vecErr)
        }
        EmbedModel.addListener(listener)
        SemanticVectors.addListener(listener)
        val d = Sheet(a).title(a.getString(R.string.sem_title))
            .size(520, 0.85f)
            .content(body)
            .action(a.getString(R.string.act_done), primary = true)
            .show()
        d.setOnDismissListener {
            EmbedModel.removeListener(listener)
            SemanticVectors.removeListener(listener)
        }
        refresh()
    }

    private fun pullNow() {
        val ws = workspace ?: return
        ServerSync.pullVectors(a, ws, 0L)
    }

    private fun refresh() {
        if (a.isFinishing || a.isDestroyed) return
        refreshModel()
        refreshVectors()
    }

    private fun refreshModel() {
        val installed = EmbedModel.isInstalled(a)
        modelButtons.removeAllViews()
        sourceRow.removeAllViews()
        modelBar.visibility = View.GONE
        when (val st = EmbedModel.state) {
            is EmbedModel.State.Downloading -> {
                modelLine.text = a.getString(
                    R.string.sem_model_downloading,
                    Formatter.formatFileSize(a, st.done), Formatter.formatFileSize(a, st.total),
                )
                modelBar.visibility = View.VISIBLE
                modelBar.progress = if (st.total > 0) (st.done * 1000 / st.total).toInt() else 0
                modelButtons.addView(Ui.textButton(a, a.getString(R.string.act_cancel)) { EmbedModel.cancel() })
            }
            is EmbedModel.State.Verifying -> modelLine.text = a.getString(R.string.sem_model_verifying)
            else -> {
                val failed = (st as? EmbedModel.State.Failed)?.message?.let {
                    when (it) {
                        "size" -> a.getString(R.string.sem_model_bad_size, EmbedModel.FILE_NAME)
                        "checksum" -> a.getString(R.string.sem_model_bad_sum)
                        else -> it
                    }
                }
                modelLine.text = when {
                    installed -> a.getString(R.string.sem_model_ready, Formatter.formatFileSize(a, EmbedModel.SIZE))
                    else -> a.getString(R.string.sem_model_missing, Formatter.formatFileSize(a, EmbedModel.SIZE))
                } + (failed?.let { "\n" + a.getString(R.string.sem_model_failed, it) } ?: "")
                if (installed) {
                    modelButtons.addView(Ui.textButton(a, a.getString(R.string.sem_model_delete)) { confirmDelete() })
                } else {
                    sourceRow.addView(Ui.body(a, a.getString(R.string.sem_model_source)).apply { textSize = 12f })
                    sourceRow.addView(
                        android.widget.RadioGroup(a).apply {
                            orientation = LinearLayout.HORIZONTAL
                            for ((i, e) in EmbedModel.ENDPOINTS.withIndex()) {
                                addView(
                                    android.widget.RadioButton(a).apply {
                                        id = 1000 + i
                                        text = e.removePrefix("https://")
                                        textSize = 13f
                                        setTextColor(Ui.onSurface(a))
                                    },
                                )
                            }
                            check(1000 + EmbedModel.ENDPOINTS.indexOf(EmbedModel.endpoint(a)).coerceAtLeast(0))
                            setOnCheckedChangeListener { _, id -> EmbedModel.ENDPOINTS.getOrNull(id - 1000)?.let { EmbedModel.setEndpoint(a, it) } }
                        },
                    )
                    modelButtons.addView(Ui.textButton(a, a.getString(R.string.sem_model_check)) { EmbedModel.checkFile(a) })
                    modelButtons.addView(Ui.textButton(a, a.getString(R.string.sem_model_download), primary = true) {
                        EmbedModel.start(a)
                    })
                }
            }
        }
        // 刚装好 / 检查通过：开关开着就马上拉一次向量
        if (installed && !wasInstalled) { wasInstalled = true; if (SemanticVectors.enabled(a)) pullNow() }
        if (!installed) wasInstalled = false
    }

    private fun confirmDelete() {
        Sheet(a).title(a.getString(R.string.sem_model_delete_title))
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.sem_model_delete), primary = true) { EmbedModel.delete(a) }
            .show()
    }

    private fun refreshVectors() {
        val ws = workspace
        if (ws == null) {
            vecLine.text = a.getString(R.string.sem_vectors_no_ws); vecErr.visibility = View.GONE; return
        }
        Thread({
            if (!widKnown) {
                wid = ServerSyncIO.serverLink(ws)?.workspaceId
                widKnown = true
            }
            val id = wid
            val stats = id?.let { SemanticVectors.stats(a, it) }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (id == null || stats == null) {
                    vecLine.text = a.getString(R.string.sem_vectors_local_ws)
                    vecErr.visibility = View.GONE
                    return@runOnUiThread
                }
                vecLine.text = a.getString(R.string.sem_vectors_count, stats.count) +
                    if (stats.pulling) " · " + a.getString(R.string.sem_vectors_pulling) else ""
                val err = when {
                    stats.unsupported -> a.getString(R.string.sem_vectors_unsupported)
                    stats.error != null -> a.getString(R.string.sem_vectors_error, stats.error)
                    else -> null
                }
                vecErr.text = err.orEmpty()
                vecErr.visibility = if (err == null) View.GONE else View.VISIBLE
            }
        }, "unireader-vec-stats").start()
    }
}
