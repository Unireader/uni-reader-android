# AGENTS.md — UniReader Android

UniReader 的安卓端：**一个 App 两种模式**（启动页二选一，`Launcher.kt`）。

- 模式1 独立版（`local/`）：平板本机直接打开工作区 `.unrd`，Pdfium 渲染 + 裸 SQLite 落库，不需要 Mac
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
  真源在 Mac，关窗要在 Mac 上做）、`chipTrailingIcon`（模式1 `⌄` 切工作区 / 模式2 📖 开书库）。
  **模式1 的目录只能跳到页顶**：pdfiumandroid 的书签 API 只给页号不给页内位置，故 `TocItem.frac` 恒 0。
- `local/store/` = Mac 定的**跨平台 schema 契约**的 Kotlin 版（裸 `SQLiteDatabase`，不用 Room）；
  **写库一律经 `StoreQueue`**（单线程 executor 独占 `LibraryStore`），主线程只 submit 参数、拿快照刷界面。
  **建表语句只有 `local/store/Schema.kt` 一处**（schema v12，逐字抄 Mac 的 `migrate()`），且只对
  「文件还不存在」的**全新**库跑一次；`Db.open` 照旧一个字 DDL 都不写、不迁移老库。
- **模式1 可以本机建库、本机加书**（2026-08-30）：`Workspace.create`（建 `<名字>.unrd` 骨架 + 空库，
  重名不覆盖、失败连文件夹一起删）+ `local/PdfImport.kt`（探页数 → SHA-256 → 拷进 `PDFs/` → 入库；
  **内容 hash 是文档身份**，同一份内容入两次只多一条 location）。入口：启动页「新建」/ 书库右上「＋」。
- `local/FileBrowser.kt` = 唯一的目录浏览器（先列存储卷再逐级点进），按 `Mode` 分三用：
  `WORKSPACE`（只有 `.unrd` 能选中）/ `FOLDER`（选存放位置）/ `PDF`（挑文件，点一个加一本、不关窗）。
  **别再各处抄一份**——异步令牌、卷枚举、慢卷上的后台列目录都在里面。
- 模式1 的阅读界面 = 「一个工作区」的多标签页（`ReaderActivity` + `DocTabsBar` + `TabSet`）：
  一个工作区一份 `LibraryStore`+`StoreQueue` 全部标签页共用，标签页懒装载、LRU 只保活 3 篇。
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
  `local/store/MirrorFp.kt`（离线镜像的行指纹：类型标签 + 分隔符 + 表规格/列顺序）
  ↔ Mac `Sources/Store/MirrorFingerprint.swift`，向量表 `../spike/mirror-fp-vectors.txt`
  （由 Mac 的 `spike/mirror-fp-test.swift` 生成，本端 `MirrorFpTest` 逐条比对，**只许在末尾追加**）
  ——两端差一个 bit，同步时整张表会被误判成「全都改过」。
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
- **环形选笔盘的长按判据是「位移 + 速度」两道闸**（2026-08-28 用户报「很容易误触」）。
  只看「离落笔点的总位移」挡不住小字：写一个小字全程都在 14dp 半径里打转，停满 1s 盘就凭空弹出来。
  加了滑动窗口内的平均速度（写字必然在动、长按必然不动）。常量在 `PadConst.LP`，
  **与 Mac `AppModel` 的 `holdSpeed*` 是同一套的两份实现**（模式2 的判定跑在 Mac），改一边同步另一边。
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
