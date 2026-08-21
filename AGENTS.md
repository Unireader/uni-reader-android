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
- 依赖已缓存，日常可 `--offline`；**新增依赖时必须去掉 `--offline`**（要联网解析）。
- 装包可以做，**手感/观感一律由用户在真机上测**，别自己截图自证；结论攒进 `../ANDROID-STANDALONE-PLAN.md §11.1`。

## 结构要点

- **两模式只差一个注入口 + 一层子类**：`shared/PageCanvasView`（连续页流：几何+输入+渲染，**不含「提交给谁」**）
  被 `pad/PadView` 与 `local/LocalCanvasView` 继承；页图来源走 `shared/PageImageSource`
  （`pad/PageFetcher` = HTTP 从 Mac 取 `/page.png?i=N`／`local/PdfSource` = 本机 Pdfium）。
  「本地乐观预览 + 真源回推」两模式同一条路径，只是模式1 的真源就在进程内。
- `shared/` 改一处两模式同时受益——这是「同一个 App」的全部意义，别在 `pad/`、`local/` 各抄一份。
- `local/store/` = Mac 定的**跨平台 schema 契约**的 Kotlin 版（裸 `SQLiteDatabase`，不用 Room）；
  **写库一律经 `StoreQueue`**（单线程 executor 独占 `LibraryStore`），主线程只 submit 参数、拿快照刷界面。
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
  `Sources/App/InkEdit.swift` ↔ web 版；`pad/WireCodec.kt` ↔ `Sources/Resources/wire.js` ↔ `WireCodec.swift`。
  改完三端测试一起跑（安卓 `WireCodecTest` 的向量与 Mac/web 的跨端向量同源）。
- **草稿纸画布坐标系 = 逻辑点（dp），原点＝创建点、可负无界**；橡皮半径按 `eraserRefWidth = 800`
  从页宽归一化折算，**三端必须同一个数**（`../SCRATCHPAD-ANDROID-HANDOFF.md §1`）。
  **页面底图**（v10）同理是三端契约：页宽恒 `ScratchGeom.PAGE_REF_W = 800` 画布 dp、高按页纵横比、
  **锚点落在画布原点**（`../PROTOCOL.md §4.4`）。改一个数三端一起改，否则同一张纸两端写的位置不一样。
- **单写者约束**：工作区没有任何同步/加锁机制，**同一时间只能有一端打开同一个工作区**。
- UI **扁平、原生、不拟物**；颜色只走语义名，别硬编码色值。
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
