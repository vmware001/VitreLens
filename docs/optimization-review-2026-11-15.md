# VitreLens 性能与界面优化评审

> 日期：2026-11-15 ｜ 版本：v2.50（5570c6b）｜ **本次只读审计，未修改任何代码/配置**
>
> 注：本文件是**改动前的审计快照**（行号均基于 5570c6b）。随后的性能优化已按本清单实施，见 commit「perf: …」系列。
> 范围：网页版（根目录）、Android（`android/`）、iOS（`ios/`）、构建与资产
> 方法：静态审计 + 真实浏览器运行时测量（Chrome DevTools Protocol + 硬件 GPU）
> 历史评审：`docs/code-review-2026-10-28.md`（偏正确性/Android），本文不重复其中已修条目，仍在的标「遗留」

---

## 一、实测环境与数据

**环境**：Windows，Intel 集显（ANGLE D3D11，软件无降级），Chrome headless（硬件 GPU）；桌面视口 1440×900、DPR 1；iPhone 模拟 390×844、DPR 3（应用内钳到 2）。
**测试图**：`reference/edited.jpg` — 6144×8192（50.3MP，18.7MB，JPEG）；`reference/original.heic` — 6144×8192 HEIC。

| 项目 | 实测 | 评价 |
| --- | --- | --- |
| 首屏加载（loadEvent） | 588ms，主线程任务 602ms | 正常 |
| 50MP JPEG 导入（桌面） | **50ms** | 优秀（Chrome 解码很快） |
| 50MP HEIC 导入（含按需拉 2MB libheif） | **1486ms** | 可接受，但主线程冻结 |
| 预览帧间隔（特写、默认水印宽度 92%） | avg 10ms / p95 10.5ms | 良好 |
| 预览内部 GL 渲染分辨率（特写 92%） | **1091×1455 = 1.59MP/帧** | 可见区只有约 0.23MP → 7× 浪费 |
| 预览内部 GL 渲染分辨率（特写 60%） | 1673×2231 = **3.73MP/帧** | p95 10.4ms |
| 预览内部 GL 渲染分辨率（特写 40%） | 2229×2972 = **6.62MP/帧** | **p95 20.8ms，开始掉帧** |
| 同上，桌面 DPR=2（Retina） | 4459×5945 = **26.5MP/帧** | **avg 24ms / p95 38ms / 峰值 54ms** |
| 整图模式 | 580×774 = 0.45MP/帧 | 便宜 |
| 拖动「水印宽度」30 帧 | 产生 **27 种不同画布尺寸**，avg 21.2ms / p95 38.3ms | **每帧重建大画布，明显掉帧** |
| 每帧新建 canvas 数（liquid/frost 材质） | **4 个/帧**；solid 材质 0 个 | GC 压力来源 |
| 导出 50MP JPEG（默认 q=100） | toast 1.40s；**主线程长任务合计 1.77s**；文件 **30.15MB** | 卡顿 + 体积过大 |
| 导出 50MP JPEG（q=92） | toast 0.53s；长任务合计 0.80s；文件 **5.87MB** | 视觉几乎无差 |
| 导出后回到预览 | 额外 **1.02s**（重新上传整图 550ms + 重建模糊 293ms + 其余） | 白做一次全链路 |
| 导出同步热点 | `getImageData` 两次共 **585ms**、整图 `texImage2D` **550ms**, `toBlob` 233ms | GPU 队列被 50MP 上传拖住 |
| APK 构成（v2.50 public，918KB 压缩 / 1.84MB 解压） | **4 个字体 = 776KB 压缩（84.6%）**，dex 仅 245KB | 包体优化=字体子集化 |
| iOS 随包字体 | 1.54MB（与 Android 同一套 4 个 TTF） | 同上 |

> 说明：headless 下 rAF 节拍约 10ms（100Hz），因此「p95 10.4ms」代表没有掉帧；出现 20/38ms 时是真实主线程/GPU 超时。Android/iOS 因本机无真机/模拟器，数字来自静态审计（标「估算」）。

---

## 二、性能优化清单

### 2.1 跨端 TOP 10（按投入产出比排序）

| # | 端 | 问题 | 证据 | 建议 | 预期收益 | 工作量 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Web | **特写预览按「原图等效宽度」渲染整幅画面，可见区只占 10~20%** | `app.js:325-340`（W=viewW/ratio）、`app.js:342-370`、`glass.js:74-93`；实测 1.59→26.5MP/帧 | 只对 `panel bbox + 阴影边距` 渲染；让 `u_crop` 真正参与采样（现在 `glass.js:290` 恒为 0,0,1,1）；预览画布固定为视口尺寸 | 片元数降 3~14×；Retina 拖动 p95 38ms→预计 <16ms | M |
| 2 | Web | **拖动「水印宽度」每帧重建画布** | 实测 30 帧 27 种尺寸（1068×1424 ~ 2191×2921）；`app.js:357-358`、`glass.js:281` 按 W/H 重设 canvas | 预览画布尺寸与 panel 解耦；参数变化只改 UV/裁剪；必要时尺寸量化到 64px | 去掉每帧 6~26MP 的画布分配/清空，拖动跟手 | M |
| 3 | Web | **导出默认 JPEG q=100 + 无面积上限 + 全尺寸纹理上传** | `app.js:33`（quality 100）、`app.js:1426`（仅限最大边 16384）、`app.js:1437-1439`、`glass.js:224-236/321-329`；实测 30MB/1.4s vs 5.9MB/0.53s | 默认 q 92~95；加面积上限（如 64MP）与失败降档；strip 画布复用；导出与预览用不同纹理 | 导出体积 −80%，耗时 −60%，并规避 200MP 时约 800MB canvas | S/M |
| 4 | Web | **导出后强制重传整图 + 重建模糊（1.02s）** | `glass.js:326`（`renderCropped` 里 `this.image=null`）→ `app.js:1649` requestRender → 整链路重跑；实测 `setImage` 550ms、`getImageData` 293ms | 导出用独立纹理/独立 renderer；若预览状态未变则不 requestRender | 每次导出省 ~1s | S |
| 5 | Web | **50MP 纹理常驻显存（约 192MiB）+ `preserveDrawingBuffer:true`** | `glass.js:161`、`glass.js:165`（maxTex 上限 8192）、`glass.js:224-236` | 预览纹理按显示需要上限 2048/4096；`preserveDrawingBuffer:false`（render 与 drawImage 同任务）；删无用 `texSource` | VRAM 192MiB→16~64MiB；移动端少一类 context lost | S |
| 6 | Android | **导入先全尺寸解码再缩放** | `MainActivity.java:1329-1332`（`decodeBitmap` 无 targetSize）、缩放发生在 `:1363-1371`；`maxPixels` 上限 56MP（`:896-900`, 估算） | `decoder.setTargetSize()` 直接解到目标尺寸；上限按堆大小定 | 200MP 峰值 ~800MB→~40MB；导入 1.5-3s→0.4-0.8s（估算） | M |
| 7 | Android | **视频链路每帧三份全尺寸缓冲；4K 单槽 100MB、双槽 200MB** | `VideoWatermark.java:553-558`、`:581-585`（`dual = 双槽 ≤384MB`） | `pixels` 只留一行；转换尺寸直接等于输出尺寸；或改 GL 直通 | 4K 峰值 −133MB，省一次整帧 `setPixels` | M |
| 8 | Android | **每次预览刷新重建全链路位图 + GL 纹理 + 回读** | `MainActivity.java:1190-1193`（每次 copy 整帧 + compose + makeBackdrop）、`:1214-1216`；`GlassRenderer.java:297-315`（每帧 glGenTextures/texImage2D×3）、`:383`（glReadPixels）、`:392-394`（CPU 翻转） | 预览尺寸按实际显示（2000→~1200）、复用可变位图、纹理常驻 + `glTexSubImage2D`、回读用 PBO/异步 | 单帧分配 ~21MB→~5MB，帧耗时 35-70ms→10-20ms（估算） | M |
| 9 | iOS | **导出完全不走 Metal：成片是 Core Graphics 近似白板** | `Exporter.swift:19-40`（只调 `WatermarkCanvas.draw`）、`WatermarkCanvas.swift:11-21/97-123`（`drawPanel` 是两层半透明白填充）；Metal 路径只在 `WatermarkCanvas.composite`（`:42-48`）被预览使用 | 导出改走 `composite`（已处理缩放与回退），或抽出离屏 Metal 导出 | **功能正确性**：预览与成片一致；导出 +30~80ms（估算） | M |
| 10 | iOS | **模糊缓存 key 不含图像身份，且从不清理** | `GlassRenderer.swift:65-68`（`"b1-\(w)x\(h)"`）、`:176`；`clearCache()` 定义于 `:209`，全仓无调用 | key 加图像哈希/uuid；换图时 `clearCache()` | **功能正确性**：换照片后玻璃里不再是上一张的模糊背景；常驻纹理 8→2 张 | S |

### 2.2 网页版（细节，均已在真实浏览器复现或逐行核对）

**P0**

1. 特写模式渲染分辨率失控（同 TOP1）。根因链：`render()` 在 `detail` 下用 `ratio = crop.w / W`、`W = viewW / ratio` 反推整幅宽度（`app.js:325-340`），本意是让裁切区 1:1 像素，代价是整幅以该分辨率重渲再裁。默认 `viewMode:'detail'`（`app.js:28`），水印越窄越糟：widthPct 92→1.59MP、60→3.73MP、40→6.62MP；DPR2 下 26.5MP/帧（p95 38ms）。
2. 拖动「水印宽度」触发画布每帧重建（同 TOP2）。`W` 随 panel 宽变化 → `work.width`（`app.js:357-358`）与 GL `canvas.width`（`glass.js:281`）反复重分配；实测 30 帧 27 种尺寸。桌面 p95 38.3ms，低端机更差。

**P1**

3. 导出成本高且默认质量拉满（同 TOP3）。`DEFAULTS.quality: 100`（`app.js:33`）；50MP q100 产出 30.15MB、主线程阻塞 1.77s（长任务 1167ms+605ms）。导出路径里：整图 2D canvas（`app.js:1437-1455`）→ 每 strip 新建 canvas + 上传纹理 + 重建 3 张模糊图（`app.js:1488-1503`、`glass.js:321-329`）→ `toBlob`（233ms）。**同步部分全程阻塞 UI，无 Worker/OffscreenCanvas**。
4. 导出后整链路重跑约 1s（同 TOP4）。
5. 每帧 4 张临时 canvas（liquid/frost 文字材质，`watermark.js:300/308/353/366`，实测 4/帧、solid 为 0），叠加 2 次 `Watermark.layout`（`app.js:326/342`）。
6. 预览纹理可达 8192 长边、约 192MiB VRAM（同 TOP5）；`preserveDrawingBuffer:true` 在部分浏览器还会多一次 backbuffer 保留/拷贝。
7. `MAX_EXPORT = 16384` 只限边长（`app.js:1426`），不限制面积：16320×12240 的 200MP 源在 scale=1 时直接建约 800MB canvas；移动端 Safari 的 canvas 面积上限约 16.7MP，50MP 导出在 iPhone Safari 上大概率失败/空白（需真机确认）。
8. EXIF 解析读整个文件后逐字节扫描：`exif.js:575` `arrayBuffer()` 全文件、`:409-411` 从 SOS 扫到 EOI、`:461-462/:479-482` 逐字节复制段与尾部（行号来自逐行审计）。20MB JPEG ≈ 2000 万次 `getUint8`，仍是导入主线程的大头之一。
9. 无 `webglcontextlost/restored` 处理（全仓无监听）：移动端丢上下文后 renderer 仍非 null，画布永久空白；`render()` catch 每帧 toast 可能刷屏（`app.js:360-367`）。

**P2**

10. `scale/quality/format` 只影响导出/UI，却都触发整幅预览重渲（`app.js:1321-1331`、`:1334-1349`）；`quality` 拖动时会做无意义全图渲染。
11. 启动同步执行 `installThumbFilter/buildBrands/renderDemo`（`app.js:1838-1841`）；`renderDemo` 画 1120×430（11 个 `ctx.filter=blur` 椭圆 + 玻璃 pass + 2 张模糊纹理），而 ≤980px 时 `.dz-demo` 是 `display:none`（`css:1149`）——移动端白画。
12. CSS 层：9 处 `backdrop-filter`（`css:86/362/438/455/490/519/1102/1127/1181`），其中 topbar/insp-rail 背后几乎是实色，收益≈0；22 个滑块各带 `filter:url(#thumbGlass)`（`css:887`）；8 处 `transition: all`（`css:187/465/695/717/743/768/1016`）。
13. 滑块输入路径有强制同步布局：`paintRange` 每帧读 `wrap.clientWidth`（`app.js:955`）并写 4 个 CSS 变量（`:952-959`，其中 `--p` 在 CSS 中**无消费者**），fill/thumb 用 `width/left`（`css:847/867`）而非合成属性。
14. 动态照片导出多次整块拷贝：`app.js:1514` 读全文件 → `:1584` 拼 `out` → `:1600` `new Blob` → `:1596` 再 `Exif.parse` 自检；峰值约 3 份，20MB 图 + 10MB 尾部 ≈100MB。
15. 无质量/性能档位：`segments` 默认 128（`app.js:37`），`ior/色散` 默认值不低，没有移动端/低端机降档开关。
16. 其它小问题：拖拽 `dragDepth` 只增不减（`app.js:1764-1781`，在子元素间移动会卡住遮罩）；`images[]` 常驻每张全尺寸解码结果与 `motion.trailer` 拷贝（`app.js:591-601`），无 LRU/上限；`ImageData` HEIC 全尺寸解码后 `Exif.readFile` 又把文件读一遍（`app.js:588`）。

### 2.3 Android（细节；数字为估算，行号来自逐行审计）

**P0**

1. 导入全尺寸解码（TOP6）。`decodeFull` 用 `ImageDecoder` 默认尺寸 + `SOFTWARE`/`mutableRequired`（`MainActivity.java:1329-1332`），缩放在 `:1363-1371`；`maxPixels()` 56MP 上限（`:896-900`）。200MP 峰值约 800MB，50MP 约 200MB，导入时有大 GC 停顿。
2. 视频帧流水线三份全尺寸缓冲（TOP7）：`FrameSlot` 有 `raw`、`pixels`、`work`（`VideoWatermark.java:553-558`），`bytesPerSlot=8*cw*ch+4*outW*outH`、双槽阈值 384MB（`:581-585`）；4K 单槽约 100MB、双槽约 200MB，叠加 photo/backdrop 极易 OOM。

**P1**

3. 预览路径过采样 + 每帧新对象：`PREVIEW_MAX=2000`（`MainActivity.java:53`）但舞台实际约 1050px；每次 `base.copy(ARGB_8888,true)` + `composeOn` + `setImageBitmap(result)`（`:1749-1775`），单帧新分配约 21MB、纹理上传约 168MB/s。
4. GL 渲染器每次 `render()` 新建输出纹理 + 重挂 FBO + 3 次 `texImage2D` + `glReadPixels` + CPU 逐行翻转 + 新 Bitmap + 删纹理（`GlassRenderer.java:297-315`、`:378-401`）。
5. 视频拖帧全链路：每格新建 `MediaCodec`（`VideoFrameDecoder.java:43-49`）、按源尺寸分配 `raw`+`int[w*h]`、每个候选帧全尺寸 `copy()`（`:85-95`）；随后 `makeBackdrop`（centerCrop 过扫约 30MB + 10MB `int[]` + 6 遍 boxBlur + setPixels，`MainActivity.java:1190-1193`）、`applyUiGlass` 每帧重算两块 UI 玻璃、`roundCorners` 主线程 `getPixel/setPixel` 约 3.5 万次/面板（`:1524-1537`）。
6. 玻璃着色器每像素采样 7 次（`glass_frag_pbr.glsl:115` 1 次 + `:193-195` 3 次 `sampleTransmission`，rough>0 时每次 2 采样，`:103-110`）；面板中心约 75% 面积三次 UV 完全相同，可早退到 1 次。另有 4 次 `sin`（`:69-95`）、`pow(x,5)`（`:208`）、2 次 `exp`（`:128-129`）。
7. 冷启动主线程串行：EGL 初始化 + 编译 2 个 shader、4 个 `Typeface`、6 个 logo 解码 + 逐像素 `sqrt` mask、程序化构建约 200 个 View、读 SP 检查更新（`MainActivity.java:261-302`，估算 200-400ms）。
8. 元数据与导出拷贝：`Meta.java:137-143` 找 EOI 用单字节 `in.read()` 扫完整张 JPEG；`:793-818` 导出至少 4 份整幅拷贝（估算 56MP q100 峰值可省约 120MB）。
9. 只有「宽度 ≥1600 走真高斯」这一档（`Composer.java:92-123`、`GlassRenderer.java:288`），没有分辨率/质量档；低端机要么全开要么全关。
10. 缩略图每次换图/批量导出 `removeAllViews` 重建，每张照片 `new Thread` 重新解码（`MainActivity.java:906-975`，估算）。

### 2.4 iOS（细节；本机无 Xcode，均未真机验证）

**P0**

1. 导出不走 Metal（TOP9）：`Exporter.swift:27-30` 只做 `image.draw` + `WatermarkCanvas.draw`，而 `draw` 的面板是 CG 近似（`WatermarkCanvas.swift:11-21/97-123`）。用户在玻璃页调的所有折射/色散/厚度参数**不会进入成片**；跨端同参数导出时 iOS 会明显「平」。
2. 模糊缓存 key 无图像身份且从不清理（TOP10）：`GlassRenderer.swift:65-68/176`；两张同为 1400×1050 的照片 key 相同，换图后玻璃内部仍是第一张的模糊背景，无法自愈。

**P1**

3. 每次刷新把全分辨率原图重采样到预览尺寸：`WatermarkCanvas.swift:41`、`:62-73`（`UIGraphicsImageRenderer.draw`），48MP 估算 50~150ms；`isDarkBackdrop`（`:77-93`）也每次重画。
4. 每帧重建 photo 纹理 + 一次 GPU→CPU 回读 + 同步等待 + `Data(data)` 整块拷贝：`GlassRenderer.swift:61/96/217-232`；单次刷新估算分配约 41MB（`估算`），16 次/秒约 650MB/s 抖动。
5. 模糊链 GPU→CPU→GPU 两次往返（`:181-200`）：`createCGImage`（回读）→ `makeTexture`（再画一遍 + `tex.replace` 再拷）。
6. 防抖不可取消：`AppModel.swift:74` `previewTask?.cancel()` 不会取消 `Task.detached`（`:87-91`），拖动时 2~4 个全链路渲染可并发叠加。
7. 导出内存与元数据：整幅位图 + `jpegData` + `.atomic` 双份（`Exporter.swift:27/32/38`），48MP 峰值估算约 460MB；且导出丢弃 EXIF/GPS/ICC，Display P3 被压到 sRGB。
8. 导入在 MainActor 上做全图重栅格化（`AppModel.swift:121/156-164`，估算 200-400ms）。
9. 一个 `@Published style` 驱动 20+ 滑块、整页 diff（`Pages.swift:202-207/305-318`、`ContentView.swift:7-43`），拖动时 `.ultraThinMaterial` + `shadow(radius:18)` + `LazyVGrid` 全部重算。
10. Metal 全屏 quad，面板外像素也做一次采样 + 阴影分支后才早退（`Glass.metal:84-97/144`，估算面板通常只占 10~20% 面积）；页内首次在 body 里初始化 `GlassRenderer.shared`（编译管线）。

### 2.5 跨端 / 资产 / 构建

1. **包体 84.6% 是字体**（APK 918KB 中 776KB）。`inter.ttf` 876KB、`playfair.ttf` 300KB、`oswald.ttf` 172KB、`jetbrainsmono.ttf` 187KB，全部是完整 TTF；水印实际只用拉丁 + 少量符号（ƒ、×、·、°、↗ 等）与固定字号。做子集化（保留 Latin-1 + 标点 + 需要的符号，或直接转 woff2/子集 TTF）可把 APK 压到约 400-500KB；iOS 同理省约 1.3MB。
2. **三端字体不一致**：Web 用系统字体栈（`css:29-31` 与 `watermark.js:8-12`：Helvetica/Segoe/Bahnschrift/Didot…），Android/iOS 随包 Inter/Oswald/Playfair/JetBrains Mono。同一张图、同一参数，Web 导出的字形与 App 不同；不同 OS 的浏览器（mac/win/linux）之间也不同。若「三端效果一致」是产品承诺，Web 需要按需加载子集字体（可懒加载，只在选择对应字体时拉几十 KB）。
3. **品牌 Logo 在 Web 是空实现**：`brands.js:22-26` 的 `measure/draw` 直接返回 0，但 UI 仍显示 10 个品牌按钮 + 「Logo」开关（`index.html:182-192/187-192`）→ 开关无效果，是「点了没反应」的困惑源。Android 的公开 APK 也不含 logo 图（36 个 entry，无 `drawable-nodpi/logo_*`），只有本地构建才带。三端需要统一：要么实现，要么隐藏并说明「Logo 仅本地构建可用」。
4. **着色器「唯一真源」只覆盖两处**：`build.sh:57-59` 从 `js/glass.js` 导出色器给 Android（`tools/extract_shaders.py`），但 iOS 的 `Glass.metal` 是手写副本，没有同步机制，长期必然漂移。建议把 Metal 也纳入生成/校验流程，或加一个「三端参数表快照」测试。
5. APK 无 R8/资源压缩（`build.sh` 只用 d8），但 dex 仅 245KB，收益有限——**不建议**优先做混淆，先做字体子集化。
6. 工程卫生：`build.sh:11-25` 每次构建把旧 `build/` 移进 `.trash`（约 6MB/次），长期膨胀；仓库内 `android/sdk`、`reference/`（含 600MB 视频）已在 `.gitignore`，但本地工作区占用很大。

---

## 三、界面 / UX 优化清单

### 3.1 网页版

| # | 级别 | 问题 | 证据 | 建议 |
| --- | --- | --- | --- | --- |
| U1 | P1 | **窄屏顶栏按钮不右对齐**：≤760px 时 `.topbar-links` 被隐藏，而 `.topbar-spacer` 在 ≤1100px 已被 `display:none`，没有元素把 `.topbar-actions` 推到右侧 | `css:1274-1280`；实测 390px 宽时 `#btnExport` 右边缘 257px，右侧空 133px | `.topbar-actions{margin-left:auto}` 或在 ≤760 恢复 spacer |
| U2 | P1 | **Toast 与「特写/整图」切换重叠**：toast 固定在 `top:70px` 居中，切换器固定在 stage 右上（58+12=70px） | `css:1078-1089`、`css:444-457`；移动端截图两元素横向重叠约 60px | 移动端 toast 下移到 110px+ 或改为 stage 内底部；或提高切换器 z-index 并右移 |
| U3 | P1 | **默认「特写 2×」在竖图上只显示一条细带**：特写裁切比例由水印面板决定（宽度大、高度小），竖图时 390×844 屏幕里画布只有约 366×80，舞台大面积留黑 | 截图：桌面 1026×222 画布 / 746px 高舞台；移动 390×~80 / 398px 高舞台；`app.js:299-308` | 竖屏默认「整图」；或让特写裁切按容器比例扩展高度/提高缩放，填满可用高度 |
| U4 | P1 | **亮照片上白字水印不可读**：`darkTone`（按背景亮度）只作用于玻璃的 `u_opaque`，文字始终白色；「恢复自动」只是清空 `textColor` 回到白色 | `glass.js:260-267/307-309`、`watermark.js` 文字绘制、`app.js:1716-1721`；`reference` 亮叶背景截图可直接看到「OPPO…」发灰 | 按水印区域局部亮度选深/浅文字或加 1px 深色描边；按钮文案与行为对齐 |
| U5 | P1 | **无障碍缺失**：22 个滑块的 label 是 `<span>`，`input` 无 id/aria-label/aria-valuetext，`output` 无 `for`；toggle 无 `aria-pressed`（仅 switch 有）；`.seg` 无 radio 语义 | `index.html:211-214/187-192`、`app.js:1353-1360` | 滑块 `label for` + `aria-valuetext`；toggle/seg 补 `aria-pressed`/`role=radiogroup` |
| U6 | P1 | **触屏滑块需长按 300ms 才开始拖动**，移动 ≤8px 即取消；首次用户大概率以为坏了 | `app.js:1013-1014/1264-1283` | 降到 120ms 左右或按下即跟手 + 方向锁；或保留但加「按住拖动」提示 |
| U7 | P2 | 导出无进度/取消；批量导出每张后有 260ms 固定等待，且 `state` 在循环内被读取（导出中改参数会让后续图用新值）；`#btnExportAll`（btn-ghost）的 busy spinner 会定位到 `.inspector` 左上角（`.btn` 无 `position:relative`） | `app.js:1615-1651`（`:1640` sleep）、`css:163-173`/`css:573` | 进度 (`i/N`) + 取消标志；导出开始时快照 state；`.btn{position:relative}` |
| U8 | P2 | 22 个滑块加 9 个 `backdrop-filter` + 22 个 SVG 位移滤镜，首屏/拖动都有合成开销 | `css:887`、`css:86` 等 | 只在 `.is-dragging` 启 thumb 滤镜；删无意义 backdrop-filter |
| U9 | P2 | 无 `prefers-reduced-motion`；无浅色主题；`--p` 死变量；拖拽遮罩在子元素间移动后卡住 | 全仓无 `prefers-reduced-motion`；`app.js:947`；`app.js:1764-1781` | 补 reduce-motion；清理死变量；dragDepth 改为进入/离开配对 |
| U10 | P2 | 移动端 HUD 与胶片条 bottom 区间重叠（HUD 18-44、filmstrip 14-68），多图时叠在一起；HUD 无 max-width 易溢出；横屏下 46vh 的 inspector + 4×42px rail 溢出（实测 doc 高 410 > 视口 390，body overflow:hidden 裁掉「导出」tab） | `css:421-439/505-521`、`css:1157-1162/1169-1186`、实测文档溢出 20px | 矮屏/横屏改横向 rail 或可折叠 drawer；HUD 与 filmstrip 分层占位 |

### 3.2 Android

| # | 级别 | 问题 | 证据 | 建议 |
| --- | --- | --- | --- | --- |
| A1 | P1 | 只有一套 layout，无 `layout-land`/`sw600dp`/`values-night`；`configChanges` 含 `orientation`，旋转不重建也不换布局 → 横屏 stage 被压到约 250dp、右侧控制区约 200dp | `activity_main.xml`（无 land 版）、`AndroidManifest.xml:30` | 加横屏左右分栏与平板限宽 |
| A2 | P1 | 触摸目标普遍 <48dp：选择/导出 38dp、播放/特写 30dp、chip/预设 34dp、SeekBar 36dp、EditText 42dp | `activity_main.xml:84/151`、`ControlPanel.java:761` | 视觉尺寸可小，用 padding/TouchDelegate 把热区补到 48dp |
| A3 | P1 | 「播放/特写」浮在照片上，白字 + 8% 白底胶囊，在雪景/白墙/天空上基本不可见 | `bg_chip.xml:12`、`activity_main.xml:149-162` | 胶囊加 `#66000000` 底 + 1dp 白描边 |
| A4 | P1 | 长任务用不可取消的 `ProgressDialog`，4K 实况转码 30s+ 只能等；批量导出失败只写日志无 UI 提示 | `MainActivity.java:1981-1990/407`、`:2108-2110` | 进度条 + 取消标志（MediaCodec 循环里查） |
| A5 | P2 | 滑块每个 tick 都触发震动（一次 100 格拖动 ~100 次）；文本输入逐字符触发整幅重合成 | `ControlPanel.java:693/917-918`、`:729` | 震动只在校准点/抬手；文本输入合并 250-300ms |
| A6 | P2 | 容器高度写死 dp、文字用 sp：系统字体 1.3× 时 chip 12sp→15.6sp 接近 34dp 上限，2× 必然裁切 | `ControlPanel.java:670`、`activity_main.xml:157` | 容器改 `minHeight` + `wrap_content` |
| A7 | P2 | 小字低对比（hint/status `#70FFFFFF`/`#80FFFFFF` @10.5sp 约 4.0-4.5:1），status `maxLines=2` 会截掉「导出/ProXDR」结论 | `ControlPanel.java:654-661`、`activity_main.xml:271-274` | 提到 `#A6FFFFFF`；status 改 3 行或可滚动 |
| A8 | P2 | 色轮无 `contentDescription`/无障碍节点，TalkBack 无法选色；status 无 liveRegion；装饰图未标 `importantForAccessibility=no` | `HsbWheelView.java:76-118`、`ControlPanel.java:615-640` | 色轮补 3 个等价 seekbar；status 加 liveRegion |
| A9 | P2 | 自绘「果冻」弹簧动画不读系统动画开关；RTL 下代码里仍用 `rightMargin/leftMargin` | `MainActivity.java:1682-1711`、`ControlPanel.java:587/762` | 关闭动画时跳过；统一 `setMarginStart/End` |

### 3.3 iOS

| # | 级别 | 问题 | 证据 | 建议 |
| --- | --- | --- | --- | --- |
| I1 | P1 | **预览只在「内容」页显示**：在「玻璃」页拖厚度/色散/边缘折射看不到结果，必须切页；切页又因 `.id(model.page)` 整棵子树销毁重建 | `Pages.swift:143-152`、`ContentView.swift:28` | 常驻预览条（header 下方），或玻璃页顶部 pin 一张小图；去掉 `.id` 换 `ZStack+opacity` 保状态 |
| I2 | P1 | 全项目固定 `.system(size:)`（最小 9.5-10pt），无 Dynamic Type | `Pages.swift:46/63/67`、`ContentView.swift:75/78/103` | 语义字号 + `@ScaledMetric`，下限 11pt |
| I3 | P1 | 触控目标不足 44pt：tab ≈31pt、Slider `.controlSize(.small)` ≈28pt、输入框 ≈35pt | `ContentView.swift:103-106`、`Pages.swift:73-75/242-245` | `contentShape` + 最小 44pt |
| I4 | P1 | 仅 iPhone 竖屏（`TARGETED_DEVICE_FAMILY="1"`、仅 Portrait）：iPad 走兼容模式、横屏被禁 | `project.yml:29/41-42` | 明确取舍；支持则卡片 `maxWidth: 520` |
| I5 | P2 | 白字 0.34-0.42 透明度落 #0D0F12 ≈2.6-3.2:1（小字 AA 要求 4.5:1） | `ContentView.swift:80`、`Pages.swift:48/160/382/396` | 提到 0.62 以上 |
| I6 | P2 | 导出仅 spinner，无进度/取消；无触觉反馈 | `Exporter.swift:19-40`、`ContentView.swift:83-87` | AsyncStream 进度 + 取消；预设切换/导出完成加触觉 |
| I7 | P2 | 无 Assets/AppIcon，启动屏白底 + 强制深色 → 启动白闪；上架会被拒 | `project.yml:40`、`VitreLensApp.swift:16` | 加 Assets.xcassets（AppIcon + 深色启动底） |
| I8 | P2 | 转场/spring 不判 Reduce Motion；滑杆无 `accessibilityLabel`（VoiceOver 只念百分比） | `ContentView.swift:24-27/98-113`、`Pages.swift:52-78` | 补 reduce-motion 判断与语义 |

### 3.4 跨端一致性

1. 玻璃本体三端实现不同：Web/Android 为真实折射，iOS 成片是 CG 近似（见 2.4-1）；参数面板一致但输出不一致，建议把「三端同参数同图导出像素差」纳入 CI 快照。
2. 字体：Web 系统字体 vs App 随包字体（见 2.5-2）。
3. 品牌 Logo 能力：Web 空实现、公开 APK 无图、iOS 有绘制分支但无资源；建议统一产品说法或统一实现。

---

## 四、建议的优化路线图

### 第一批：小时级、低风险（建议先做，肉眼可见）
- Web：默认 JPEG 质量 100→92~95；导出加面积上限；`preserveDrawingBuffer:false`；预览纹理上限 4096；`quality/format/scale` 不再触发预览重渲；删 topbar/insp-rail 的 `backdrop-filter`；`.topbar-actions{margin-left:auto}`；toast 下移；删 `--p`；拖拽 depth 配对。
- Web：`editor` 侧验证后，再把「特写只渲染条带 + 固定预览画布」做成主改动（见第一批后的 P0）。
- Android：`PREVIEW_MAX` 2000→按 stage 实际像素；`base.copy` 换常驻可变位图；视频帧预览里去掉 `applyUiGlass()`、`hq=false`；`makeBackdrop` 复用 + 替换时 recycle；着色器平面区早退；`roundCorners` 改 `getPixels` 批量；`Meta` EOI 改分块读。
- iOS：`blurCache` key 加图像身份 + 换图 `clearCache()`；缓存预览代理 `CGImage`；去掉 `waitUntilCompleted`（`addCompletedHandler`）；预览防抖串行化只保留最新请求。

### 第二批：1~2 周（主性能攻坚）
- Web：特写改为「只渲染 panel bbox」并让 `u_crop` 生效；预览画布尺寸与 panel 解耦；导出 strip 画布/纹理复用 + 动态照片 Blob 分段拼装；EXIF 只读头部 + trailer `Blob.slice`；导出移入 `OffscreenCanvas`（或至少分片 `await` 让出主线程）+ 进度/取消。
- Android：`ImageDecoder.setTargetSize()`；视频 `FrameSlot` 精简（pixels 单行、转换尺寸=输出尺寸）；`GlassRenderer` 常驻纹理 + `glTexSubImage2D`；视频解码器常驻 + 只保留最终帧；GL 化 YUV→RGBA。
- iOS：导出改走 Metal（复用 `composite`）+ `CGImageDestination` 增量写 + 复制源 EXIF/ICC/P3；导入移出 MainActor；按字段拆 ObservableObject 降 diff。

### 第三批：结构性
- 三端统一着色器/排版渲染源（iOS Metal 纳入生成或加像素回归测试）。
- 质量档位（低端机降采样/关色散/关高光）+ 首次运行自动测速。
- 包体：字体子集化（APK/iOS 预期 −1.3MB~1.4MB）、无用的重复字体与本地资源清理。
- 批量导入内存上限（LRU/按需重解码），导出/导入后台化与进度反馈。
- 无障碍与响应式补齐（Web aria、Android 48dp/TalkBack、iOS Dynamic Type/44pt）。

---

## 五、需要真机/真实浏览器确认的事项

1. **Web 移动端 canvas 上限**：iOS Safari 上 50MP（6144×8192）导出是否空白/失败；若是，`MAX_EXPORT` 需要按面积（如 16MP）与 UA 分档。
2. **Web 特写 26.5MP/帧在真实 Retina MacBook** 的帧率：headless Intel 集显已出现 p95 38ms，Apple Silicon 会好一些但仍是浪费；建议 DevTools Performance/GPU 轨道复测。
3. **Web 移动端 `preserveDrawingBuffer:false` 与 `ctx.filter`** 在 Safari 的行为（Safari 对 canvas filter 支持较晚）。
4. **Android 4K 实况导出峰值内存**：`dumpsys meminfo` + `am start -W` 冷启动基线；Perfetto 看 `RenderThread UploadBitmap` 与 `roundCorners` 段。
5. **iOS 真机**：Time Profiler（`resizedCGImage` 占比）、Allocations（单次刷新 ~41MB 估算）、Metal System Trace（`waitUntilCompleted` 阻塞）、48MP 导出是否触发 jetsam。
6. **交互手感**：触屏滑块 300ms 长按在 iPhone Safari 上是否可接受；建议 A/B 或至少加提示。

---

## 六、做得好的地方（不要改坏）

- Web：`requestRender` 用 rAF 合并（`app.js:293-296`）；导出防重入（`app.js:1616`）；libheif 按需加载（`app.js:130-149`）；预览无 `getImageData/readPixels`；GL 程序/纹理/位移图只建一次（`glass.js:158-177`）；HEIC/动态照片/MPF/ICC 的处理在同类前端项目里属于罕见的完整度。
- Android：自研构建链无 AndroidX，体积小；元数据重组有 69 条单元测试；视频帧双槽流水线思路正确（只是内存拆分需要优化）；着色器注释把 three.js 公式逐条对上，可维护性好。
- iOS：Core 包与 App 拆分、Core 有测试；`WatermarkCanvas.composite` 已经抽象了「缩放→玻璃→文字」的正确顺序（导出只需改用它）。
- 三端参数/版式语义对齐得不错（Preset 数值一致），这是后续一致性的基础。

---

## 七、附：本次为只读审计

未修改、创建或删除仓库内任何代码/配置；仅生成本报告。运行时截图位于 `%TEMP%\vl-audit\`（01-desktop-empty、02-desktop-photo、05-mobile-detail、10-full-view 等）。
