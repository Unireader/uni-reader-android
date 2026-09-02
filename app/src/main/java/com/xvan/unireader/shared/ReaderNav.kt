package com.xvan.unireader.shared

/**
 * 阅读导航的**中立模型**：目录一项 / 书库一项。两模式共用（同 `Ink.kt` 之于笔迹）。
 *
 * 这两样东西两种模式都有，只是来源不同——模式2 从 Mac 的 `toc`/`library` 广播解出来，
 * 模式1 从本机 Pdfium 书签与工作区 SQLite 读出来。把形状定在 `shared/`，
 * [ReaderDrawer] 才能一份实现两模式共用（`pad`/`local` → `shared` 单向依赖，
 * `shared` 不许反过来认识 `WireCodec`）。
 */

/**
 * 目录一项，**先序拍平 + depth**（树结构在 [ReaderDrawer] 里按 depth 就地重建：
 * 前一项 depth 更小者即父）。线格式本来就是这么发的（`../PROTOCOL.md §4.2` 的 `toc`），
 * 模式1 从 Pdfium 的嵌套书签递归拍平成同一形状。
 *
 * @param page 0-based 目标页；**-1 = 坏书签**（destination 解不出目标页，真实 PDF 里很常见），
 *   两模式一律渲染成不可点的灰行、不显示页码、不参与当前章节追踪。
 * @param frac 页内纵向归一化位置（0 = 页顶）。**模式1 恒 0**：Pdfium 的高层书签 API
 *   只给页号不给页内位置，跳过去只能落在页顶（章节从页中部起时会落在上一节末尾附近）。
 */
data class TocItem(val depth: Int, val page: Int, val frac: Float, val label: String)

/**
 * 一枚**书签**（`../REQUIREMENTS.md §1.9`）：用户自己加的、带名字的定位记录。
 * 与 PDF 自带目录是两回事，但显示时与目录**合并成同一棵树**（规则见 [TocMerge]）。
 *
 * 来源同样按模式分：模式2 从 Mac 的 `bookmarks` 广播解出来，模式1 从工作区 SQLite 的
 * `note` 表（kind=5）读。列表恒按「页 → 页内位置 → 建立时刻」有序，**两端都别再自己排**。
 */
data class BookmarkItem(val id: String, val page: Int, val frac: Float, val title: String)

/**
 * 书库一项。[id] 的含义**由各模式自定**，[ReaderDrawer] 只负责原样回传给 `onOpenDoc`：
 * 模式2 是**库文档 id**（`library`/`openDoc` 那个 id 空间，见 `../PROTOCOL.md §4.1` 的警告块），
 * 模式1 是本机 `library.sqlite` 的 `document.id`。[open] = 已经开着（模式2 = Mac 某个窗口里开着，
 * 模式1 = 已在某个标签页里）。
 */
data class LibItem(val id: String, val title: String, val open: Boolean)
