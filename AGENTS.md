# AGENTS.md — UniReader Android

UniReader 的安卓端：**一个 App 两种模式**（启动页两个标签页，`Launcher.kt`：「本机工作区」= 模式1、
「连接 Mac」= 模式2；2026-09-26 起选哪台 Mac 在启动页上做——连过的 Mac 点一下 / 扫码，经
`PadActivity.start(host, token)` 带参数进去直接开连，不带参数才是旧行为「进去先弹连接设置」；停在哪一页记在本机）。

- 模式1 独立版（`local/`）：平板本机直接打开工作区 `.unrd`，Pdfium 渲染 + 裸 SQLite 落库，不需要 Mac。
  🔴 **目标是与 macOS 版功能对齐**（用户 2026-09-26 定）：模式1 是独立产品，Mac 上有的功能这里都要有，
  **只有平板上确实做不到的才跳过**（跳过的要在 `../TODO.md` 记一句为什么）。Mac 端每加一个功能，默认模式1 也要跟上

- 模式2 输入板（`pad/`）：连 Mac 当手写输入板/第二屏（二进制线格式 + UDP RT 上行）
- `shared/`：两模式共用的几何 / 输入 / 渲染 / 笔迹算法

## 与主仓库的关系（先读这条）

- 本目录是**独立 git 仓库**，主仓库 `.gitignore` 忽略了 `android` → 安卓改动**在这里单独提交**，
  不会跟 macOS 端的提交混在一起。
- 所以**安卓端自己的规则、结构、坑写在本文件**（`CLAUDE.md` 是它的软链），跟着安卓仓库走；
  主仓库根的 `AGENTS.md` 只留一句引用。**改安卓代码前先读本文件，跨端契约再回上级目录查。**
- 上级目录（主仓库，路径都相对本目录写成 `../`）：
  - `../PROTOCOL.md` — 二进制线格式**唯一契约**，三端字节级一致，改协议先改它
  - `../ANDROID-STANDALONE-PLAN.md` — 模式1 方案（§9 已踩的坑 / §11.1 待真机清单 / §11.2 模拟器能证明什么）
  - `../ANDROID-MODE2-PLAN.md` — 模式2 方案
  - `../OFFLINE-MIRROR-PLAN.md` — 工作区离线镜像（整份复制到本机、离线写笔迹、接回硬盘三方合并）
  - `../SCRATCHPAD-ANDROID-HANDOFF.md` — 草稿纸安卓端交接（坐标系 / 存储 / 协议）
  - `../TODO.md`（进行中/待办，第一优先）、`../HISTORY.md`（已完成归档）——安卓条目也在里面
  - `../AGENTS.md` — macOS 端的构建、红线与结构

## 构建与验证

```bash
cd android
./gradlew assembleDebug              # wrapper 已补齐（gradle 9.5.1），别再用 ~/.gradle 里的裸 gradle
./gradlew test                       # JVM 单测：WireCodecTest / InkEditTest / ScratchGeomTest
./gradlew connectedDebugAndroidTest  # 插桩测试，要设备或模拟器（见下：数据层只能在设备上验）
./pack.sh                            # release 打包；adb 恰好一台设备时顺带安装（--debug / --no-install）
                                     # release 会先自动改 app/build.gradle.kts：versionCode+1、versionName patch+1
                                     # （debug 不动版本号；--no-bump 跳过；升 minor/major 手动改 versionName）

python3 tools/icons/gen.py           # 改图标：改几何 → 重新生成全部 ic_*.xml + 自检
python3 tools/icons/gen.py --check   # 只自检不写盘（bbox / 重心 / 尺寸 / 引用一致性）
python3 tools/icons/gen.py --sheet   # 顺带出 tools/icons/sheet.png 对照大图（要 matplotlib）
```

- 新克隆先补 `local.properties`（本机文件，不入 git）：`sdk.dir` + `releaseStorePassword` / `releaseKeyPassword`。
- 签名：debug/release **共用同一证书** `~/.keystores/xVanTuring.jks`（alias `key0`），保证两个变体可互相覆盖安装。
- **只打 `arm64-v8a`**（Pdfium 是 native 库，多 ABI 会把 APK 撑几十 MB）→ **x86_64 模拟器装不上**，是刻意取舍。
- `io.legere:pdfiumandroid` 锁在 **2.0.1**，别随手升：AGP 9.2.0 内置的 Kotlin 编译器是 2.2.0、最多读元数据 2.3.0，
  而 2.0.2+ 是 Kotlin 2.4 编的 → 整个 `compileDebugKotlin` 直接失败（连 `kotlin.Unit` 都报 incompatible）。
- **数据层的测试只能跑在设备上**：`android.database.sqlite` 在 JVM 单测里是空壳，故 `local/store/` 的用例都在 `androidTest`。
  跑单个类：`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<全限定类名>`
  （**不能带 `--offline`**，UTP 的插件要联网解析）。同一台机器经 mDNS 注册两次时 Gradle 会当成两台设备、
  在"第二台"上跑出 0 个用例并以 `Could not load test results` 收场——**看 `app/build/outputs/androidTest-results/`
  里那份非空的 XML 才是真结果**。
- **`LibraryStoreTest`/`ScratchPadStoreTest`/`StoreQueueTest`/`StrokeEchoTest` 依赖真 fixture**：
  它们开的是 `/sdcard/Download/内覆盖.unrd`（Mac 造的真工作区）并要「所有文件访问权限」，
  **换台干净模拟器就必然全红 30 条**（`SQLITE_CANTOPEN … Permission denied`）。
  别把它当成自己改坏了——先 `git stash -u` 跑一遍基线对数，或只跑自己那个类。
  不依赖 fixture 的（`WorkspaceCreateTest`/`MirrorBuilderTest`）一律在 `cacheDir` 里做，随处能跑。
- 依赖已缓存，日常可 `--offline`；**新增依赖时必须去掉 `--offline`**（要联网解析）。
- 装包可以做，**手感/观感一律由用户在真机上测**，别自己截图自证；结论攒进 `../ANDROID-STANDALONE-PLAN.md §11.1`。

## 结构要点

- **两模式只差一个注入口 + 一层子类**：`shared/PageCanvasView`（连续页流：几何+输入+渲染，**不含「提交给谁」**）
  被 `pad/PadView` 与 `local/LocalCanvasView` 继承；页图来源走 `shared/PageImageSource`
  （`pad/PageFetcher` = HTTP 从 Mac 取 `/page.png?i=N`／`local/PdfSource` = 本机 Pdfium）。
  「本地乐观预览 + 真源回推」两模式同一条路径，只是模式1 的真源就在进程内。
- `shared/` 改一处两模式同时受益——这是「同一个 App」的全部意义，别在 `pad/`、`local/` 各抄一份。
- **导航三件套 2026-08-28 起也共用**：`shared/TopBar`（顶栏，早就是）+ `shared/ReaderDrawer`
  （左侧抽屉：目录 / 书库）+ `shared/DocTabsBar`（标签页栏）。数据走中立模型 `shared/ReaderNav.kt`
  （`TocItem`/`LibItem`）：模式2 从 Mac 的 `toc`/`library`/`docs` 广播转一层，模式1 从
  `PdfSource.toc`（Pdfium 书签）与工作区 SQLite 读。**`shared/` 一行都不许认识 `WireCodec`。**
  两模式的差异只由开关表达，不分叉代码：`DocTabsBar.canClose`（模式2 = false，「开着哪几篇」
  真源在 Mac，关窗要在 Mac 上做）。**工作区芯片两模式同义**（`⌄` 切工作区，2026-09-06 起；
  此前模式2 点它是开书库）：模式1 切本机的 `.unrd`，模式2 在 **Mac 已开着的工作区**之间切
  ——`docs` 广播每项带 `ws`（`../PROTOCOL.md §4.2`），标签页栏**只列当前工作区那几篇**、
  芯片下拉按 `ws` 分组，点一行就对那个工作区里上次待过的那篇发 `selectDoc`。
  🔴 当前工作区取 **`docs` 里 selected 那项的 `ws`**，不取 `library` 广播的 wsName：
  两条广播的先后没有保证（同 `layout`/`toc` 那个坑），拿另一条的字段分组会在切档瞬间错位一拍。
  **模式1 的目录只能跳到页顶**：pdfiumandroid 的书签 API 只给页号不给页内位置，故 `TocItem.frac` 恒 0。
- **撤销/重做 + 剪贴板（2026-09-02）**：入口在 `shared/PageCanvasView`（`requestUndo` /
  `requestClipCopy` / `requestClipPaste`），实现由两模式各自注入（`onUndoRequested` / `onClipCommit`）。
  **模式2 只发帧**（`undo` 0x4F / `clip` 0x51，栈与剪贴板都在 Mac）；**模式1 本机就是真源**：
  `LocalCanvasView` 自带一条撤销栈 + 进程级剪贴板 `local/InkClipLocal.kt`。
  模式1 的栈存**整份可见笔迹的快照**而不是 id 级增量——`Stroke` 不可变，一份快照只是一串引用；
  恢复直接走既有的 `reconcileStrokes`（擦除在用的那条），「隐藏图层一条都不碰」的规矩一并继承。
  **模式1 只管笔迹**：注解的增删改不进栈（与 Mac 端有差距，记在 `../TODO.md`）。
  选中集有无变化经 `onLassoSelChanged` 回调给顶栏（灰掉/亮起剪切与复制）——它写在
  `lassoSelection` 的 setter 里，那个字段有七八处赋值点，逐个补调用迟早漏一处。
- `local/store/` = Mac 定的**跨平台 schema 契约**的 Kotlin 版（裸 `SQLiteDatabase`，不用 Room）；
  **写库一律经 `StoreQueue`**（单线程 executor 独占 `LibraryStore`），主线程只 submit 参数、拿快照刷界面。
  **建表 / 迁移语句只有 `local/store/Schema.kt` 一处**，逐字对应 Mac 的 `LibraryStore.migrate()`
  （`Schema.VERSION = 17`；新建库走 `createLibrary`，**可写**打开已有库时 `LibraryStore.open` 调 `Schema.migrate`：
  `CREATE … IF NOT EXISTS` 补齐到 v17 的全部表（含本端只建不用的 v15 `md_doc`）+ 与 Mac 同序的 `ADD COLUMN` +
  写 `meta.schema_version`；库里版本比本端新时不往回写。只读打开不迁移。唯一没抄的是 Mac 私有缓存表 `page_geom`）。
  🔴 **2026-09-26 用户撤销了「安卓不改表结构」的旧规定**：安卓可以像 Mac 一样迁移老库（`CREATE … IF NOT EXISTS`
  补表、`ADD COLUMN` 补列、写 `meta.schema_version`），也可以提出新的表 / 列。剩下的纪律只有一条——
  **schema 是两端共同的契约**：任何一端改结构，另一端同一次一起改，DDL 逐字相同、版本号同步，
  并同步 `../REQUIREMENTS.md §8` 与相关方案文档；不许出现「只有一端认识」的表或列。
  （历史：此前只在全新库建表、老库一个字 DDL 不写，例外是 `page_align` 镜像补表与 v16 画板两张表补建，
  现在这些都并入正常迁移。）
- **扫描页对齐（模式1 只读，2026-09-17，`../SCAN-ALIGN-PLAN.md`）**：开关与测量只在 Mac；本端按**打开的那个文件**的
  内容 hash 读 `page_align`（`LibraryStore.activePageAlign`，老库没表 → 按没开），交给 `PdfSource`。开着时
  `PdfSource.pageSizes` 报对齐后的 `(W, sh)`、`request` 出转正平移过的图——几何层 / 草稿纸垫页 / 参考窗都只经这两处
  认识页面，所以不必各改；库里的批注坐标本来就是按对齐页面写的，**一个不换算**。页图缓存键带 `displayKey`（`hash~a戳`）。
  出图**没用** pdfiumandroid 2.0.1 的带矩阵 `renderPageBitmap`：它把 `Matrix` 的两个斜切项互换着传给 PDFium，旋转会反向
  （原因写在 `PdfSource.renderAligned`）；现在是「原页出中间图 + `Canvas.drawBitmap(Matrix)`」。
  离线镜像另有一条 `page_align` 通道（`MirrorDiff.alignPlan` / `MirrorApply.fillAlign`，按 `updated_at` 取新、不进基线）。
- **模式1 可以本机建库、本机加书**（2026-08-30）：`Workspace.create`（建 `<名字>.unrd` 骨架 + 空库，
  重名不覆盖、失败连文件夹一起删）+ `local/PdfImport.kt`（探页数 → SHA-256 → 拷进 `PDFs/` → 入库；
  **内容 hash 是文档身份**，同一份内容入两次只多一条 location）。入口：启动页「新建」/ 书库右上「＋」。
- `local/FileBrowser.kt` = 唯一的目录浏览器（先列存储卷再逐级点进），按 `Mode` 分三用：
  `WORKSPACE`（只有 `.unrd` 能选中）/ `FOLDER`（选存放位置）/ `PDF`（挑文件，点一个加一本、不关窗）。
  **别再各处抄一份**——异步令牌、卷枚举、慢卷上的后台列目录都在里面。
- `local/mirror/` = **离线镜像**（`../OFFLINE-MIRROR-PLAN.md`）：`MirrorStore`（`sync_base` 基线表 +
  血缘 meta + 借出记录编解码）+ `MirrorBuilder`（建镜像：`VACUUM INTO` → 拷 PDF → 内化外部文件 →
  算基线 → 源库记一笔借出）。**必须跑在 `StoreQueue` 的独占线程上**——慢卷上是分钟级（主线程做必 ANR），
  且老机器的「整文件拷」兜底路径正是靠「拷的时候进程内没人在写」才安全。
- **划字（模式1 专属，2026-09-04）** = 顶栏模式键的**第五档「选字」**（`MODE_TEXT`）+
  `shared/OcrText.kt`（`TextRun`/`OcrTextSelect`/`OcrFlow`/`OcrWatermark`）+ `shared/TextSelect.kt`
  （选区装配）+ `local/TextSelectBar.kt`（划完浮出来的「四色高亮 / 批注 / 复制」）。
  文本层来自工作区库的 `ocr_page`——**Mac 跑的 OCR，本机一行都不跑**（随离线镜像同步过来，
  见 `../OFFLINE-MIRROR-PLAN.md §4.1`）；没同步过来的书在平板上就是划不动，切进选字模式会有一句提示。
  🔴 **`MODE_TEXT=4` 不进 `PadConst.MODE_LABELS`**：那张表是线上契约（`../PROTOCOL.md §4.1` 的
  `mode` u8 只有 0..3），模式1 用 `LOCAL_MODE_LABELS`，模式2 循环碰不到第五档。
  **模式2 的划字刻意没做，方案已备**——要做先读 `../ANDROID-MODE2-PLAN.md §9`
  （选定「平板本地判定、Mac 只执行动作」，预留 `0x52 textLayer`/`0x53 textAct`，
  顺带给 `notes` 补 `aw`/类型色）。
- 模式1 的阅读界面 = 「一个工作区」的多标签页（`ReaderActivity` + `DocTabsBar` + `TabSet`）：
  一个工作区一份 `LibraryStore`+`StoreQueue` 全部标签页共用，标签页懒装载、LRU 只保活 3 篇。
- **画板笔记（v16，2026-09-26，`../BOARD-NOTE-PLAN.md`）**＝工作区里独立的无限白板，整页就是一张草稿纸
  （画布坐标 / 笔宽 / 橡皮 ×800 / 网格全按 `../PROTOCOL.md §4.4`）。
  · 模式1：标签页 docId 存 `board:<id>`（`TabSet.BOARD_PREFIX`，于是标签页组存取一行没改）；画板标签**没有**
  阅读画布，整页是 `local/BoardController` 那块 `ScratchCanvas`（照 `ScratchController` 写，读写 `board_note` /
  `board_item`：笔迹 kind=1，payload 同草稿纸 kind=4 但**不写 padId**）。工具状态挂在一块不显示的
  `LocalCanvasView`（`ReaderActivity.boardTools`）上，顶栏模式 / 笔 / 尺子、笔胶囊、侧键都经 `toolCanvas()`。
  入口：书库页一组、抽屉书库页一组（`ReaderDrawer.setBoards`，模式2 不设 = 不显示）、「+」、⋯、画板浮条最左键。
  🔴 **新 id 一律大写 UUID**（`newBoardId()`）：Mac 把这两张表的 id 解析成 `UUID` 再按 `uuidString`（大写）写回，
  小写 id 会让 Mac 那边 `ON CONFLICT(id)` 对不上、多出重复行。
  · 模式2：`boards`(0x52) / `boardImages`(0x55) 下行、`boardOpen`(0x53) / `boardAdd`(0x54) 上行；笔迹仍走草稿纸
  那几条（画板会话里那张纸永远开着）。`PadScratch.boardMode` 收起关闭 / 页面底图 / 其它草稿纸 / 删除；
  kind=1 时盖一层「Mac 正在看 Markdown 笔记」。图片按 `GET /image?h=<sha>` 取（`pad/BoardImageFetcher`）。
  · 图片（kind=2）两模式都**只显示不编辑**，画在底纹之上、笔迹之下（`ScratchCanvas.setPics`）。
  · **分页画板（v17，`../BOARD-NOTE-PLAN.md §9`）**：有 `board_page` 行 = 分页。契约（布局 `(-W/2, i×(H+24), W, H)`、
  模板 u8 与几何、尺寸预设）只在 `shared/BoardPaging.kt`（↔ Mac `BoardModel.swift`，`BoardPagingTest` ↔ `spike/board-store-test.swift`）；
  画法与视口（页宽适配 / 横向夹页宽 / 到底上拉加页 / 页码）只在 `ScratchCanvas.setPages` 那一份，两模式共用。
  画布上一律画布坐标；模式1 落库时按「第一个点（图片按上沿）所在页」换成**页内坐标** + payload `page`
  （换算只在 `local/store/BoardPageSet`）。插页 / 删页 / 改尺寸**只写 `board_page`**（删页连同 `page` 指向它的条目删行），
  写完整篇重读——页内坐标不变，重读时按新布局自然挪位，没变的条目一行不重写。模式2 收 `boardPages`(0x56)，
  到底上拉发 `boardPageAdd`(0x57)、改当前页背景发 `boardPageTemplate`(0x58)；插页 / 删页 / 批量 / 改尺寸只在 Mac 与模式1。
  新建画板两模式都先弹 `shared/NewBoardSheet`（选模式 / 尺寸 / 横竖 / 背景 / 页数），模式2 分页时 `boardAdd` 带可选尾部。
- **草稿纸 / 画板笔迹走分块位图（2026-09-26，用户报画板多了拖动掉帧）**：`shared/ScratchTiles` 把成形笔迹按
  「块缩放下 512px 网格」在后台线程池里用软件画进位图，`ScratchCanvas.onDraw` 只贴图；缺块时笔迹少才直接画
  （`DIRECT_MAX_PTS`），多了宁可先空着等块。🔴 `ScratchCanvas.strokes` 的每一处增删都要经 `strokesChanged(removed, added)`，
  漏一处那一处改了就看不见。草稿纸几何与缩放成正比，`InkRenderer.drawScratchStroke` 缩放后**不重建**、按比例缩放已有轮廓
  （放大超过 1.5 倍才重建）。计时打点 logcat `UniReader/Tiles`。
- **笔迹点集二进制（schema v18，2026-09-26，`../BINARY-INK-PLAN.md`）**：`note` / `board_item` 多两列 `points`（`InkPointsBlob`，
  u8 版本 + f32 x/y/z 小端，跨端向量 `../spike/ink-blob-vectors.txt`）+ `points_at`（= 写时的 `updated_at`，不相等 = 旧版改过、读 JSON）。
  读一律经 `InkPayload.readStroke(payload, id, page, points, pointsValid)`（四条规则与 Mac `InkStrokePayload.read` 同一份）；
  写口都要带 `points = InkPointsBlob.encode(...)`；payload 里的 JSON 点一律摘成 `[]`（只在 `LibraryStore.inkPayloadForWrite`）。
  开工作区后分步整理老行（`compactInkPointsStep`，一批一个 `StoreQueue` 任务）；用户定默认清掉 JSON 点、不留兼容副本。
  🔴 本端不用 SQL 的 `json_*` 函数（API 26 不保证有 JSON1）。
- **草稿纸 / 画板上的框选（2026-09-26）**：`ScratchCanvas` 自带一份（`Tools.lassoTool` = 顶栏框选模式），两模式共用：
  圈选（笔迹任一点落多边形内）/ 拖框内移动 / 拖手柄缩放，**只作用于笔迹**。移动 / 缩放**本地立刻生效**，经
  `onLassoEdit` 交给宿主：模式1 `updateScratchStrokes` / `updateBoardStrokes` 写回点 + 线宽（🔴 不能走擦除那条
  reconcile：它按「点数没变 = 没改」跳过，平移过的笔迹点数恰好不变）；模式2 发 `lassoMove` / `lassoScale`，
  纸开着时坐标按**画布坐标**解释（`../PROTOCOL.md §4.4`，Mac `AppModel+Scratch.applyScratchLasso` 复判）。
  选中集按**多边形**记，表一换就重判（模式2 的笔迹没有 id）；拖动时选中那几条从 `strokes` 里拿出来单独画，松手放回。
  顶栏剪切 / 复制 / 粘贴在画板标签或纸开着时作用在纸上：模式1 走 `InkClipLocal.putCanvas/takeCanvas`（画布空间，
  跨空间折算同 Mac `InkClipboard.scaled`），模式2 发 `clip`（画布坐标，粘贴落点 = 视口正中）。
- **可自由编组的工具栏（2026-09-26 用户定，像 macOS 那样配置）**：`shared/Toolbox.kt`（摆放 / 拖动 / 顶栏排不下收进 ⋯）+
  `shared/ToolLayout.kt`（纯数据：组 = 名字 + key 列表 + 在顶栏 / 浮动 + 位置；记在 SharedPreferences `toolbars` 的 `layout`，
  **两模式共用一份**）+ `shared/ToolbarEditor.kt`（编辑面板）。每颗键是一颗「工具」，只在一个地方：某一组里，或哪组都不在 = 在 ⋯ 里。
  顶栏四段：「固定在左」的组（`TopBar.pinArea`）/ 中间其余并在顶栏里的组（横向滚动，**不往 ⋯ 里收**）/「固定在右」的组
  （`pinRightArea`）/ 最右的 ⋯（组属性 `pin`：固定的没有把手、不能拖、不滚动）。只有固定组放不下时才把按钮收进 ⋯（读数不收）；
  「编辑工具栏…」常驻 ⋯（固定组没有把手，编辑入口靠它）。页码（`pageLabel`）与模式2 的延迟读数（`latency`）也是工具，
  默认在右侧固定的「状态」组。
  · 顶栏的键经 `TopBar.icon(key, …)` 登记（原先 ⋯ 里的低频项也是 `icon`，带 `toggle` / `available`）；画板 / 草稿纸控制栏由宿主
  `bar.tools.adopt(barView, Toolbox.*_BAR_KEYS)` 整排交出去——两边同名的键（适应内容、纸样…）在布局里是同一颗。
  控制栏 `barView` 本身不再上屏，Controller 照旧设它的 visibility，那只当「这些键此刻能不能用」的信号（改了调 `onBarShown`）。
  · 宿主改键的状态照旧 `bar.setActive / setVisible / setTint / setEnabled`，刷完 HUD 调 `bar.tools.refresh()`（没变不重量）。
  `setVisible(false)` 与 `available` 为假是同一个意思 =「此刻不能用」，**不直接改按钮的 visibility**，交给 `Toolbox`：
  默认拿走；编辑面板勾了「显示暂时不能用的按钮」（`Toolbox.showDisabled`，prefs `toolbars` 的 `showDisabled`）时
  灰着显示、点了没反应（包层 `ItemWrap.blocked` 吃掉触摸，不碰按钮自己的 enabled / alpha），⋯ 里也灰着列出。
  · 🔴 新加一颗键：`icon()` 登记 + 在 `ToolLayout.defaults()` 里给它一个默认位置（或进 `MENU_KEYS`），合并逻辑会把它放进老用户的布局；
  图标照例改 `tools/icons/gen.py`。浮着的组插在 `tools.below` 之下（抽屉永远最上层），位置归 `Toolbox`，宿主别改它们的 LayoutParams。
- **界面文案资源（2026-09-26 起）**：从画板笔记开始，新文案进 `res/values/strings.xml`（英文）+
  `res/values-zh/strings.xml`（中文），两份一起加；更早的文案仍直接写在代码里（中文），没有搬。
- **书写锁定 + 相对粗细模式（2026-09-26）**：锁的是**切笔本身**（09-27 用户纠正）：`PageCanvasView.writingLocked`
  锁定后 `cyclePen` 笔记档不轮替、`selectPen` 只认当前笔、`cycleMode`/`setModeLocal` 只在 笔记/擦除 间来回；
  顶栏「writeLock」键在**笔组**里紧跟 mode/pen（图标 `ic_write_lock` = 笔 + 小锁，别再和「锁定缩放」的 `ic_lock`
  共用——用户报两个锁分不清；老布局经 `ToolLayout.MOVED` 一次性挪过来）；模式1 本机独立判定
  （`RadialController` 长按盘只剩当前笔+橡皮），模式2 双向发 `../PROTOCOL.md` `lock` 0x59（`MacClient.Callback
  .onLock`/`PadView.onLockChanged`，同 `eraser` 一个 opcode 两个方向都用）。相对粗细模式在共用层
  `PageCanvasView.relativeInkWidth`（落笔时 `strokePen()` 按本机 `zoom` 折算 `curStrokePen`），草稿纸 / 画板经
  `ScratchCanvas.Tools.relativeInk` 按画布缩放折算；**折算在 pad 上做**，模式2 上行的 `pen.w` 已是折算后的值、
  Mac 原样用（Mac 拿不到 pad 的缩放）。开关模式1 存 `ToolPrefs`，模式2 与 Mac 双向同步 `relInk` 0x5A
  （`onRelInkChanged`/`setRelativeInkWidthLocal`，同 lock）。
- **画板笔记记住上次滚动位置（schema v19，2026-09-26，`../BOARD-NOTE-PLAN.md §10`）**：`board_note` 加
  `viewport_x/y/zoom`（`viewport_zoom<=0` = 没存过，按老规矩摆），**刻意不进 `MirrorFp`**（同 `last_opened_at`）。
  `ScratchCanvas.openSession(restore:)`/`currentViewport()`，`BoardController` 停手 0.6s 节流写回、
  `close()`/宿主 `onPause()` 立即 flush。模式2 没有本机库，不在这次范围内，视口仍是各端各自维护。
- UI 是**经典 View，零 Compose 依赖**：语义色板（深浅两套）+ `shared/Ui.kt` 设计系统 +
  `shared/TopBar.kt` 两模式共用顶栏 + `shared/Sheet.kt` 统一弹层。
- **图标是生成物**：`res/drawable/ic_*.xml` 全部由 `tools/icons/gen.py` 一份几何源码生成，
  **不许手改 XML**（改了下次生成就没了）。加/改图标 = 改 `gen.py` 里的 `ICONS()` 再重跑。
  规格：24 画布 / 内容活动区 20 / 描边 1.8 round / 只有圆点这类元素允许填充。
  生成前逐个量 bbox，超出安全区、重心偏出 12±0.4、尺寸不在区间、或与代码里的
  `R.drawable.ic_*` 引用对不上（画了没人用 / 用了没画 / res 里有手工残留），**当场报错不写盘**。

## 红线与高频坑

- **页面尺寸口径必须与 Mac 一致**：CropBox 有效则 CropBox、否则 MediaBox。取错了 Mac 上写的笔迹在平板上
  整体漂移/缩放，且是那种「看着差一点点、说不清哪错了」的 bug（`../ANDROID-STANDALONE-PLAN.md §9.1`）。
- **同款算法多份实现，改一边必须同步其余端**：`shared/InkEdit.kt`（局部擦除切段/平移/框选缩放/多边形命中/尺子吸附）↔ Mac
  `Sources/App/InkEdit.swift` ↔ web 版；`pad/WireCodec.kt` ↔ `Sources/Resources/wire.js` ↔ `WireCodec.swift`；
  `shared/NoteBubbleGeom.kt`（文字笔记展开气泡的比例常数与位置规则）↔ Mac `Sources/Views/NoteBubbleView.swift`
  的 `NoteBubble` ↔ web `render.ts` 的 `BUB`（`../REQUIREMENTS.md §1.2`）；
  `shared/CanvasMargin.kt`（画板模式的页边软边界档位：STEP/SLACK/LIMIT 三个常数 + `marginFor`）
  ↔ Mac `Sources/App/CanvasMargin.swift` ↔ web `shared.ts` 的 `canvasMarginFor`；
  `shared/TocMerge.kt`（目录 + 书签的合并规则：一级组区间、组内插位、树顶平铺）
  ↔ Mac `Sources/App/TOCMerge.swift`（**参照实现**，`../spike/toc-merge-test.swift` 19 项）
  ↔ web `web/src/lib/tocMerge.ts`，本端 `TocMergeTest` 7 项与 Mac 那份逐条对应；
  `local/store/MirrorFp.kt`（离线镜像的行指纹：类型标签 + 分隔符 + 表规格/列顺序）
  ↔ Mac `Sources/Store/MirrorFingerprint.swift`，向量表 `../spike/mirror-fp-vectors.txt`
  （由 Mac 的 `spike/mirror-fp-test.swift` 生成，本端 `MirrorFpTest` 逐条比对，**只许在末尾追加**）
  ——两端差一个 bit，同步时整张表会被误判成「全都改过」；
  `shared/OcrText.kt` + `shared/TextSelect.kt`（**划字**：行内字符定位 `OcrTextSelect` ↔ Mac
  `Sources/App/OCRTextSelect.swift`；列块分组 `OcrFlow` ↔ `OCRFlow.swift`；平铺水印剔除
  `OcrWatermark` ↔ `OCRWatermark.swift`；选区装配 `TextSelect` ↔ `ReaderSurface+Selection.swift`
  的 `ocrLineHit`/`ocrGroupSelection`/`ocrLinearSelection`），本端 `TextSelectTest` 14 项
  ↔ Mac `spike/ocr-char-select-test.swift`/`ocr-watermark-test.swift`
  ——判定不一致 = 同一本书两端划出来的字不一样，而那段文字会当 `quote` 落库；
  `shared/ScanAlign.kt`（**扫描页对齐**：每页变换 `PageAlign` + 参数表解码 `ScanAlignTable` + 戳 / `displayKey`）
  ↔ Mac `Sources/App/ScanAlign.swift`（契约 `../SCAN-ALIGN-PLAN.md` §2/§3），本端 `ScanAlignTest` ↔ Mac
  `spike/scan-align-test.swift` ①②⑤，**戳的跨端向量**（样例 payload → `7438e8a2`）两端各算一遍抄进测试；
  `local/mirror/MirrorDiff.alignPlan` 与 `Plan.isCleanPushToMirror`/`pendingToSource` ↔ Mac `MirrorDiff.swift`
  （`MirrorDiffTest` 的对齐三条 ↔ `spike/mirror-align-test.swift` ①②）
  ——变换差一个符号，Mac 上写的笔迹在平板上就落在「整页微微转了一点」的位置。
  改完三端测试一起跑（安卓 `WireCodecTest` 的向量与 Mac/web 的跨端向量同源）。
- **草稿纸画布坐标系 = 逻辑点（dp），原点＝创建点、可负无界**；橡皮半径按 `eraserRefWidth = 800`
  从页宽归一化折算，**三端必须同一个数**（`../SCRATCHPAD-ANDROID-HANDOFF.md §1`）。
  **页面底图**（v10）同理是三端契约：页宽恒 `ScratchGeom.PAGE_REF_W = 800` 画布 dp、高按页纵横比、
  **锚点落在画布原点**（`../PROTOCOL.md §4.4`）。改一个数三端一起改，否则同一张纸两端写的位置不一样。
- **画板模式的页边不是新坐标系**：页边笔迹仍是**页内笔迹**（归属那一页），只是归一化 `x`
  越出 `0…1`（单位还是页宽的倍数）。所以擦除/框选/图层/落库一律照旧，只有三处要跟着放宽：
  ① 命中（`locate`/`pageLocClamped` 的 `wide` 参数）；② 渲染 clamp（`InkRenderer` 的 `xMargin`，
  **不能靠 clamp 收边**——那会把页外笔迹压成页边一条竖线，该由 canvas clip 裁）；
  ③ 变换（`InkEdit.translated/scaled` 的 `xMargin`，默认 0 = 不出本页）。
  页边宽度**模式2 由 Mac 下发**（`canvas` 0x4B），**模式1 本机从笔迹算**（`CanvasMargin`）。
- **双指与防误触（2026-09-27，`shared/TouchGuard.kt`，页内与草稿纸/画板两处画布共用）**：
  · 捏合**按帧增量**算缩放，由 `PinchSplit` 按「指距变化 / 中点位移」连续加权（双指滚动时手指并拢不算缩放）。
  用户否决过「先挪一段再判意图」和百分比死区两种做法（起手不跟手），**别再加起手判定或锁定**。
  · `PenProximity`：笔悬停中 / 刚离开 0.3s 内不认新手指；手指手势进行中笔靠近、笔落下、系统判为手掌
  （`ACTION_CANCEL` / `FLAG_CANCELED`）→ 结束该手势、不甩惯性，开始不到 1s 的把画面还原到手指落下之前。
  数据看 logcat `UniReader/Pinch`、`UniReader/Palm`。
- **环形选笔盘的长按判据是「位移 + 速度」两道闸**（2026-08-28 用户报「很容易误触」）。
  只看「离落笔点的总位移」挡不住小字：写一个小字全程都在 14dp 半径里打转，停满 1s 盘就凭空弹出来。
  加了滑动窗口内的平均速度（写字必然在动、长按必然不动）。常量在 `PadConst.LP`，
  **与 Mac `AppModel` 的 `holdSpeed*` 是同一套的两份实现**（模式2 的判定跑在 Mac），改一边同步另一边。
  另两条同族的规矩（2026-09-02 修，`../TODO.md` 已知 Bug 有完整成因）：
  · **探针坐标锚在落笔那一刻的坐标系里**（`beginProbe` 冻结落点与页宽页高，`probeAt` 把屏幕位移
  折成归一化量，**刻意不 clamp**）。翻页模式下笔拖着页面一起走，按当前页面算的话笔相对页面
  几乎没动 → 判定方把「拖着翻页」当成长按。擦除模式页面不动，两种算法逐值相等。
  · **迟到的 `pressRing on=true` / `radial open=true` 一律丢弃**（`overlayAllowed`，笔不在纸上就不画）。
  这两样只在手势进行中才有意义；它们与 `strokes` 全量镜像共用一条有序 WS 通道，大帧一在飞就会迟到，
  补画出来就是「环凭空冒出来、还不在笔尖」。`false`（撤环/收盘）永远照收——那是清理。
- **笔迹回推有两种，别只处理一种**（2026-08-28，`../PROTOCOL.md §4.2`）：`strokes`(0x36) 是**整表替换**，
  `strokesAppend`(0x4C) 是**追加**（payload 逐字节相同，只差 opcode）。Mac 只在收笔那一处发追加帧
  ——全量镜像每收一笔就重发整篇是 O(n²)。对应 `PageCanvasView.setStrokes` / `appendStrokes` 两个入口，
  追加那条**不走**擦除那道整份丢弃闸（追加不会把擦掉的复活），只按 `ackRel` 销账乐观笔。
- **`ackRel` 对账：落墨与擦除是两件事，别用同一条判据**（2026-08-28 修，`../PROTOCOL.md §4.2`）。
  整份丢弃一份回推快照，只因为它**比本地的擦除旧**（比 `lastEraseRel`）。拿「本端已发出的最后一个
  `seqRel`」去比是错的：ink move 每 8ms 就是一个新序号，连续快写时判据永远为真、快照一份都进不来，
  乐观笔迹只能靠超时撤掉 → 用户看到「上一个字的笔画依次闪烁」。落墨**逐条认领**：每条乐观笔记下自己
  `ink end` 帧的 REL 序号（`UdpSender.sendRel` 同步返回），`ackRel` 追上才销账，没追上的叠在快照之上照画。
  乐观笔迹的兜底撤销判据是「**一份回推都没收到**」而不是「等够久了」——真源哑了才算掉线。
- **页图缓存的额度别拿 `maxMemory()` 算**（2026-08-29 踩过两轮，`../HISTORY.md` 同日第一节）。
  **API 26 起 `Bitmap` 像素在 native 堆，不占 Java 堆** → `maxMemory()`（本机 256MB =
  `dalvik.vm.heapgrowthlimit`）跟页图没关系，拿它的 1/3 当预算 = 8GB 的平板上只肯留 85MB
  ＝**一张横屏页图**（2880×4073×4 ≈ 47MB），换篇文档回来必然重下。同理 **`largeHeap` 对页图无用**。
  现在两模式统一走 `PageWidths.cacheBytes(ctx)`＝总内存/20，夹 64…384MB。四条配套规矩：
  ① 换文档**不许清缓存**（键里带 contentHash，两篇文档本就不串；Mac 的 `AppModel.setPadRender`
  也犯过同一个错）；② 低清档/压缩字节这类小格必须是**额外加**的，不能从总额里切
  （第一版从 85MB 里切 32MB 给低清，目标档只剩 53MB 比不改还糟）；③ 额度大了**必须接
  `onTrimMemory`** 主动还——native 内存超支不抛 OOM，是整个进程被 lowmemorykiller 干掉、回来冷启
  （`PadActivity`/`ReaderActivity` 两处已接，注意 `UI_HIDDEN`(20) > `RUNNING_CRITICAL`(15)，
  常量不是单调刻度，判据顺序别写反）；④ 真正兜住「切回刚才那篇」的是**磁盘缓存**
  （`shared/PageDiskCache`，压缩字节一页才 200~600KB，512MB 装上千页）。
  查这类问题先看 `UniReader/PageFetch` 启动那行额度打点，别猜。
- **单写者约束**：工作区没有任何同步/加锁机制，**同一时间只能有一端打开同一个工作区**。
- UI **扁平、原生、不拟物**；颜色只走语义名，别硬编码色值。
- **界面上不许出现大段技术说明**（2026-08-30 用户明确要求）。一件事一句话说完；真要展开的背景
  知识走 `Ui.tip()`（ⓘ + 一行小字，点开才弹细节）。从前那些三四行的灰色长段落（权限说明、
  单写者警告、目录浏览器口径、打不开库的四条成因清单）用户扫一眼就跳过 = 白写；
  要查根因看日志比看弹窗准得多。
- **图标按钮的尺寸只在 `Ui.kt` 改**（`TOUCH`/`ICON`，2026-09-02 用户要求整体收 15% → 41/19，
  条高 `TopBar.BAR_H` 56→48）。这两个数一改**全 App 的图标按钮一起变**（顶栏、草稿纸浮条、
  参考窗、抽屉都走 `Ui.iconButton`）——这正是"整体缩小"的意思，别在某一处单独调。
  40dp 以下就别再往下调了（开始点不准）；`TopBar.reflow` 的排布按 `Ui.TOUCH` 现算，自动跟随。
- **镜像一个带圆弧的图标时，sweep 标志必须跟着翻**（`gen.py` 的 `MIRROR_X`，`undo`/`redo` 那对）：
  镜像把绕行方向也翻了过来，照抄 sweep 会让弧朝反方向鼓出画布（`--check` 会当场报"超出活动区"，
  但报的是结果不是原因，第一次撞上容易去调坐标）。
- **别手写图标 XML**。2026-08 之前 28 个图标是手写的，规格靠人肉复制 → 飘成三种线宽
  （1.6/1.8/2.0）、四种视觉尺寸（`ic_nib` 内容只占 6..18，`ic_lock` 撑到 3..22 差点被裁），
  `ic_ruler` 靠 `<group rotation>` 把矩形甩出画布，`ic_nib`（"切换笔"）画的是水滴、
  `ic_palette`（"纸样"）画的是调色盘。整套已按 `tools/icons/gen.py` 重做，规格只写一次。
  **图标大小以真机/模拟器上的成品为准**：斜置细杆（笔）和只有两笔的角标，按 bbox
  等大时在栏上看着就是小一号——这两个的尺寸是照着截图回调出来的，别按数字"纠正"回去。
- **给已经设过 padding 的 View 换背景，一律走 `Ui.setBackgroundKeepPadding`**，别直接
  `background = …`。`View.setBackground()` 会调 `Drawable.getPadding()`，返回 true 就**拿它
  覆盖 View 自己的 padding**——`InsetDrawable` 就是这种。图标按钮的内容框全靠 13dp padding
  锁在 22dp，框子一变 `FIT_CENTER` 立刻把图标放大；更阴的是取消激活时换上的 `RippleDrawable`
  的 `getPadding()` 返回 false，View **不还原**，胀完就一直胀着。
  2026-08 实测：顶栏尺子点一下 18.5 → 36.0 画布单位（≈1.94 倍），再关掉仍是 34.9，
  于是「点过的键比没点过的大一圈」。查这类问题**别去调图标几何**——先量渲染像素，
  跟 `gen.py --check` 打印的声明值一比，对不上就是别的地方在改缩放。
- **模拟器验证的四个坑**（`../ANDROID-STANDALONE-PLAN.md §11.2`，都误判过，别重踩）：
  ① `adb pull` 库要连 `-wal`/`-shm` 一起拉，否则新写入还在 WAL 里、读到的是旧数据；
  ② 按 `uiautomator dump` 出的文本定位点击，别记坐标（最近列表会重排）；
  ③ `input stylus swipe` 的终点只走到全程 ~80%（短行程更夸张），靠日志打实际归一化框来对；
  ④ 长按类手势（环形选笔盘）用分次 `input stylus motionevent`（DOWN → 宿主机 sleep → MOVE → UP），
  swipe 会被判成「在画」而取消长按。手势一律用 `input stylus`（手指在画布里是平移/捏合）。
