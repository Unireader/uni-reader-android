package com.xvan.unireader.shared.exercise

import android.text.style.BackgroundColorSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.util.regex.Pattern
import org.commonmark.node.CustomNode
import org.commonmark.node.Node

/**
 * `==高亮==`（Obsidian 写法；Mac 那边是引擎的 `HighlightExtension`，2026-11-10 起笔记里开着）：
 * 两个等号夹着的一段铺一层黄底。里面的字按纯文字处理（不再套粗体 / 公式），一行之内配对，`===` 这种不算。
 * 依赖 `MarkwonInlineParserPlugin`（公式插件也用它），所以要排在它后面。
 */
class HighlightPlugin : AbstractMarkwonPlugin() {

    class HighlightNode : CustomNode()

    private class Processor : InlineProcessor() {
        override fun specialCharacter(): Char = '='

        override fun parse(): Node? {
            val m = match(RE) ?: return null
            return HighlightNode().apply { appendChild(text(m.substring(2, m.length - 2))) }
        }
    }

    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(MarkwonInlineParserPlugin::class.java) { it.factoryBuilder().addInlineProcessor(Processor()) }
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(HighlightNode::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.visitChildren(node)
            visitor.setSpans(start, BackgroundColorSpan(COLOR))
        }
    }

    companion object {
        /** 开头两个等号、后面不是等号也不是空白；中间不换行；结尾两个等号前不是空白。 */
        val RE: Pattern = Pattern.compile("==(?=[^=\\s])[^\\n]*?[^\\s]==")
        /** 半透明的黄（深浅两套底色上都看得清字）。 */
        const val COLOR = 0x66FFD60A
    }
}
