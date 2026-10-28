# VitreLens 代码评审

| 项 | 内容 |
|---|---|
| 评审对象 | `D:\data\code\liquidglass`（Android 4,198 行 Java + Web 3,614 行 JS） |
| 评审版本 | commit `ecb534c`，APK v1.35 / versionCode 36 |
| 评审方式 | 全量源码通读 + 模拟器实测（Android 12L / SDK 32，`127.0.0.1:16384`）+ 产物二进制校验 |
| 日期 | 2026-10-28 |

结论分三档：**P0 会产生错误结果或丢数据**、**P1 稳定性与资源问题**、**P2 代码卫生**。每条标注「已实测」或「读码推断」。

---

## 零、先说做对的部分

避免只列问题造成误判，以下设计值得保留：

- **元数据层是全项目最扎实的部分**。`Meta.java` 刻意不依赖 Android API，`run-tests.sh` 用纯 JVM 跑 69 条断言，覆盖 JPEG/HEIC 解析、ICC 去重、MPF 偏移重算、XMP `Item:Length` 改写、HDR 尾块逐字节搬运。实测三张样本（ProXDR 静态图 / 动态照片 / HEIC）导出后，MPF 的 `size`/`offset` 与新文件长度**逐字节自洽**，增益图字节完全一致。这块不需要动。
- **玻璃着色器的物理建模是对的**。用解析高度场代替网格、`refract` 走厚度、三通道色散、`clamp(2·ior−2)` 修正粗糙度，与 three.js 的对应关系在注释里逐条写明。实测玻璃、色散、投影均正常渲染。
- **失败降级有意识**。GL 初始化失败 → 只出文字；视频转码失败 → 照片照出、弹窗说明原因；导出后回读自检增益图。这些都比同类工具做得好。

下面开始是问题。

---

## 一、P0：会产生错误结果或丢数据

### P0-1　HDR 动态照片的视频被按 SDR + BT.601 处理，色彩错误且 HDR 丢失

**位置**：`VideoWatermark.java:440-495`（`yuvToBitmap`）、`:136-141`（编码器 configure）

**已实测**。用项目自带的 `dyn8.jpg` 走完整导出流程，从容器里抽出视频段做二进制比对：

```
输入视频 avc1 colr:  nclx primaries=9  transfer=18  matrix=9   full_range=0
输出视频 avc1 colr:  nclx primaries=1  transfer=1   matrix=1   full_range=0
                     (BT.2020 primaries / HLG 传输)  →  (BT.709 / SDR gamma)
```

输入是 **BT.2020 + HLG**（H.273：primaries=9、transfer=18 即 HLG、matrix=9），而 `yuvToBitmap` 用的是写死的 **BT.601 有限范围**系数：

```java
int c = Y - 16;                       // limited range 偏移
int r = (298 * c + 409 * e + 128) >> 8;   // BT.601 系数
int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
int b = (298 * c + 516 * d + 128) >> 8;
```

三重错误叠加：

1. **矩阵用错**：BT.2020 的 YUV→RGB 系数与 BT.601 不同（1.4746/0.1646/0.5714 vs 1.402/0.3441/0.7139），红绿方向偏移最明显；
2. **传输函数被丢弃**：HLG 需要 OOTF 反变换才能映射到显示亮度，直接当 gamma 编码的 YUV 处理 → 画面发灰、对比度塌陷、高光细节丢失；
3. **输出标签写死 BT.709**：编码器没设 `KEY_COLOR_STANDARD/TRANSFER/RANGE`，MediaCodec 默认填 709/SDR，播放器不会做任何补偿。

这正是目标用户群（OPPO / 小米旗舰的 HDR 实况照片）最常见的素材。而状态栏还显示 `ProXDR ✓`——照片的增益图确实保住了，但**视频段已经变成偏色的 SDR**，用户不会察觉。

**修复方向**（按投入排序）：

- **最小改动**：从 `MediaFormat` 读源色彩信息，至少把矩阵选对，并把编码器标签写成与实际一致：

```java
int std = getInt(vf, MediaFormat.KEY_COLOR_STANDARD, -1);   // 1=BT709 2=BT601_NTSC 4=BT2020
int range = getInt(vf, MediaFormat.KEY_COLOR_RANGE, -1);    // 1=limited 2=full
// 选系数矩阵；BT.2020 → 1.4746/0.1646/0.5714
ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, std > 0 ? std : 1);
ef.setInteger(MediaFormat.KEY_COLOR_RANGE, range > 0 ? range : 1);
```

- **正确做法**：HLG/BT.2020 源先做 tone mapping 到 SDR 再编码（否则即使标签对了，SDR 播放器上依然过曝）；或者保留 HLG，用 `KEY_COLOR_TRANSFER=3 (HLG)` 编码并把 profile 提到 High10。前者实现简单、兼容性好，建议作为默认；后者作为开关。

- **不建议**继续用"解不出来就报错"的策略：这条路径现在**不报错**，静默产出错色视频，比失败更糟。

---

### P0-2　编辑列表（elst）丢失，音画同步偏移 100ms

**位置**：`VideoWatermark.java:305-356`（重新封装）

**已实测**。解析两条 MP4 的 `moov/trak/edts/elst`：

```
输入：video  elst[0] segdur=3167ms  media_time=100ms     ← 跳过前 100ms
      audio  elst[0] EMPTY 70ms, elst[1] 3072ms          ← 音频延迟 70ms

输出：video  （无 edts/elst）                             ← 偏移丢失
      audio  elst[0] EMPTY 70ms, elst[1] 3072ms          ← 保留
```

音频的 70ms 延迟被 MediaMuxer 重建了（因为样本 PTS 保留了偏移），但视频的 `media_time=100ms` 没有——解码器把被 elst 裁掉的前 3 帧也解出来并写进了新文件，于是**视频内容整体提前 100ms，相对音频产生 −100ms 偏移**。

对 3 秒的实况照片，100ms 约等于 3 帧，属于"能看出来"的程度（拍手、说话口型）。长度越短占比越大。

**根因**：`MediaMuxer` 不会继承源文件的 edit list，需要手动处理。两种修法：

- **推荐**：解码后按 `elst.media_time` 丢弃早于该时刻的帧（`if (pts < editStartUs) { release; continue; }`），时间轴从 0 重新起算；
- 或者封装后用 `MediaMuxer` 之外的工具补写 `elst`（Android 没有公开 API，需要自己拼 box，不建议）。

顺带：`basePts` 在 `:213` 只从视频首帧取，`:336` 却同时用于音频。当视频首帧 PTS ≠ 0 时，音频会被错误平移同样大小。当前样本首帧 PTS 恰好为 0，所以没暴露。建议视频、音频各自减去自己的首样本 PTS。

---

### P0-3　连续选图存在竞态，可能把 A 图的元数据套到 B 图上

**位置**：`MainActivity.java:306-353`（`loadImage`）、`:365-367`（`decodeFull` 读字段）

**读码推断**（触发条件明确，但未构造复现）。

`loadImage` 在后台线程里写三个**实例字段**：`srcUri`、`meta`、`photo`；而 `decodeFull` 不接收参数，直接读 `this.srcUri`：

```java
private void loadImage(Uri uri) {
    new Thread(() -> {
        srcUri = uri;                       // ← 字段
        meta = Meta.parse(...);
        ...
        Bitmap bmp = decodeFull(bounds.outWidth, bounds.outHeight);  // ← 读 this.srcUri
```

用户在加载过程中再点一次"选择"（或外部 `SEND` 意图进来），两个线程并行：

1. 线程 A 设 `srcUri=A`，开始解码大图（几百 ms）；
2. 线程 B 设 `srcUri=B`、`meta=B 的`，解码完成，界面显示 B；
3. 线程 A 的 `decodeFull` 这时读到 `srcUri=B`，解出 B 的像素，却配着 A 的 `meta` 写回 `photo`/`photoW`。

结果：**画面是 B，机型/参数/时间是 A 的**，导出时 `Meta.assemble` 还会用 B 的像素 + A 的 EXIF 拼文件。用户很难发现，但产出的照片元数据是错的。

**修复**：把 `uri` 作为参数传下去，并在开始时取一个自增 token，回调前校验：

```java
private int loadSeq = 0;

private void loadImage(Uri uri) {
    final int seq = ++loadSeq;
    new Thread(() -> {
        Meta m = Meta.parse(...);          // 局部变量
        Bitmap bmp = decodeFull(uri, m, w, h);
        if (seq != loadSeq) { bmp.recycle(); return; }   // 已被更新的请求取代
        ...
    }).start();
}
```

---

### P0-4　`fileSize()` 取不到长度时，整个尾部（增益图 + 视频）静默丢失

**位置**：`Meta.java:147`、`MainActivity.java:423-443`

**读码推断**。

```java
if (fileLen > 0) m.trailerLength = Math.max(0, fileLen - m.eoiEnd);
```

`fileSize()` 依次尝试 `OpenableColumns.SIZE` → `AssetFileDescriptor.getLength()`，两者都可能返回 −1（`UNKNOWN_LENGTH`，管道类 provider 常见）。一旦为 −1：

- `trailerLength = 0` → `motion=false`、HDR 尾块判定跳过、`trailerVideoLength=0`；
- 导出时 `assemble` 里 `if (orig != null && trailerLength > 0)` 不成立 → **尾部一个字节都不写**；
- 自检读回输出，发现 `hdr=false`，提示"⚠ 增益图丢失"——但**照片已经写进相册了**，动态照片变成静态图。

**修复**：不要依赖外部长度。`Meta.parse` 已经在完整扫描到 EOI，让它在流末尾自行统计总长即可（或用 `ParcelFileDescriptor.statSize`）：

```java
// parse() 末尾：如果 fileLen <= 0，用已读字节数 + 剩余字节数补出真实长度
```

---

### P0-5　XMP 超 64KB 时容器长度改写失败，但视频仍被替换

**位置**：`Meta.java:255-268`（`rebuildXmpSegment` 超限返回 null）、`:627-642`（调用处静默忽略）

**读码推断**。`rebuildXmpSegment` 在 `payloadLen + 2 > 65533` 时返回 `null`，`assemble` 里：

```java
byte[] seg = rebuildXmpSegment(patched);
if (seg != null) { /* 替换 XMP 段 */ }     // null 就什么都不做
```

随后仍然把新视频写进尾部。此时 **XMP 里的 `MotionPhoto Item:Length` 是旧长度，实际视频是新长度**，播放器按旧长度取流 → 截断或读到脏数据。属于容器级不一致，比"视频没加上水印"更糟。

**修复**：`rebuildXmpSegment` 失败时**放弃替换视频**（回退为原样搬运），并把原因回传：

```java
if (replaceVideo && xmpText != null) {
    String patched = replaceItemLength(...);
    byte[] seg = patched == null ? null : rebuildXmpSegment(patched);
    if (seg == null) { replaceVideo = false; videoWarn = "XMP 段过大，视频保持原样"; }
    else keepSegs.set(xmpIdx, seg);
}
```

---

## 二、P1：稳定性、内存与资源

### P1-1　预览位图逐次泄漏，长时间调参会 OOM

**位置**：`MainActivity.java:556`、`:567-594`

```java
Bitmap out = composer.composeOn(work, work.getWidth(), work.getHeight(), st);
lastComposed = out;          // ← 旧的 lastComposed 从未 recycle
```

每次预览（滑杆拖动 70ms 去抖一次）都替换 `lastComposed`，旧位图交给 GC。1400px 预览约 8MB，连续拖动 30 秒可以制造上百 MB 垃圾；`preview.setImageBitmap(show)` 还持有上一张的引用。

`closeUp()` 在 `ACTION_MOVE` 里每次新建一张裁剪位图，同样不回收：

```java
case MotionEvent.ACTION_MOVE: {
    ...
    updateCloseUp();          // → Bitmap.createBitmap(full, ...) 每帧一张
```

**修复**：替换前回收，且特写视图复用一张位图：

```java
Bitmap old = lastComposed;
lastComposed = out;
if (old != null && old != out) old.recycle();
```

### P1-2　`GlassRenderer` 从不释放，EGL 上下文与纹理泄漏

**位置**：`GlassRenderer.java`（全类无 `release()`）、`MainActivity.java:179-183`（`onDestroy`）

`onDestroy` 只 `glQueue.shutdownNow()`，EGLDisplay/Context/Surface、FBO、3 个纹理全部未销毁。

放大问题的因素：`AndroidManifest.xml` 的 `configChanges` 只声明了 `orientation|screenSize|keyboardHidden`（`0x4a0`）。**系统深色模式切换（uiMode）、字体大小、语言变化都不在其中** → 这些操作会重建 Activity，每次泄漏一个 GL 上下文。

**修复**：

```java
void release() {
    if (display != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(display, EGLSurface.EGL_NO_SURFACE, EGLSurface.EGL_NO_SURFACE, EGLContext.EGL_NO_CONTEXT);
        int[] t = {texPhoto, texBlur, texBlur2}, f = {fbo};
        GLES20.glDeleteTextures(3, t, 0);
        GLES20.glDeleteFramebuffers(1, f, 0);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
    }
    display = EGL14.EGL_NO_DISPLAY;
}
```

并在 `configChanges` 里补 `uiMode|density|fontScale|locale`，从根上减少重建。

### P1-3　`scaleInto` 换尺寸时不回收旧模糊位图

**位置**：`GlassRenderer.java:191-201`

```java
if (dst == null || dst.getWidth() != w || dst.getHeight() != h) {
    dst = Bitmap.createBitmap(w, h, ARGB_8888);   // ← 旧 dst 直接丢弃
}
```

裁切块尺寸随水印宽度变化而变化（拖"水印宽度"滑杆就会变），每变一次泄漏两张。**修复**：先 `if (dst != null && 尺寸不符) dst.recycle();`。

### P1-4　`render()` 每次做两遍全尺寸字节拷贝

**位置**：`GlassRenderer.java:277-290`

```java
ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
GLES20.glReadPixels(..., buf);
byte[] px = buf.array();          // 第一遍
byte[] out = new byte[px.length]; // 第二遍
for (...) System.arraycopy(...);  // 逐行翻转
```

一块 4096×2200 的裁切区 = 36MB × 2 的瞬时分配。**修复**：原地翻转（首尾行交换），省掉 `out`；或用一块可复用的 `byte[]` 成员变量。另外 `buf.array()` 依赖 `allocateDirect` 返回的缓冲区可访问数组（当前 Android 实现可用，但属于实现细节），改成 `buf.get(px)` 更稳妥。

### P1-5　`initLogos` 被重复调用两次，24 张位图里有 12 张成为垃圾

**位置**：`MainActivity.java:144-166`

两段**完全相同**的 `try { decode 6 张 PNG → initLogos }` 代码块紧邻。第二次调用把 `logoBmps` 整个换掉，第一次解出的 6 张原图 + 6 张掩码图全部失联；`buildMask` 产出的掩码位图也从未回收原图。

**修复**：删掉第二段；`initLogos` 里对旧数组做回收。

### P1-6　视频路径按 4K 计算会占 100MB 以上临时位图

**位置**：`VideoWatermark.java:154-157`

```java
Bitmap frameRaw  = Bitmap.createBitmap(srcW, srcH, ARGB_8888);   // 4K: 33MB
Bitmap frameWork = rotation == 0 ? frameRaw : createBitmap(outW, outH, ...); // 再 33MB
int[] rowPixels  = new int[srcW * srcH];                          // 再 33MB
```

4K 实况照片视频（3840×2160）三项合计约 100MB，叠加 `largeHeap` 与照片位图，OOM 概率不低。**修复**：`rowPixels` 改为按行（`setPixels(line, 0, W, 0, y, W, 1)`），`frameWork` 仅在 `rotation != 0` 时分配（已经是），但可以把 `rowPixels` 减到一行。

### P1-7　导出没有防重入，连点会排队产出多份

**位置**：`MainActivity.java:598-604`

`export()` 直接 `glQueue.execute(...)`，按钮不置灰。连点三次 → 三次完整导出排队（含三次视频转码），每次弹一个 `ProgressDialog`，相册里出现三张几乎一样的图。**修复**：进入时 `btnExport.setEnabled(false)`，`finally` 里恢复；或用 `AtomicBoolean busy` 守卫。

### P1-8　`ProgressDialog` 与 Activity 生命周期脱钩

**位置**：`MainActivity.java:248/307/602`，回调里 `pd.dismiss()`

后台线程持有 `ProgressDialog` 引用，Activity 若在导出期间被销毁（返回键、系统回收），`pd.dismiss()` 会抛 `BadTokenException` 或泄漏窗口。`setPdMessage` 里 catch 了 `Exception`，但 `dismiss()` 没有。**修复**：改用 `isFinishing()/isDestroyed()` 判断，或换成不依赖 Activity 的进度通知。

### P1-9　`parseExif` 缺少边界检查，损坏的 EXIF 会让整段元数据失效

**位置**：`Meta.java:519-524`

```java
int ifd = readInt(t, 4, le);
int n = readShort(t, ifd, le);      // ifd 可能越界 → AIOOBE
```

外层 `catch (Exception ignored)` 兜住，后果是 `make/model/date/focal` 全部为空，水印只剩默认文案——用户看到的是"读不出机型"，而不是"EXIF 损坏"。**修复**：`if (ifd < 8 || ifd + 2 > t.length) return;`，并对每个 entry 的 `vo + size` 做 clamp（已有部分）。

### P1-10　MPF 条目多于两个时，替换视频后 size 字段不更新（潜在）

**位置**：`Meta.java:672-694`

```java
writeInt(mpfPatched, mpfSizePos[0], trailerStart, le);
long cursor = trailerStart;
for (int m = 1; m < mpfOffPos.length; m++) {
    writeInt(mpfPatched, mpfOffPos[m], cursor - tiffAbs, le);
    if (m < mpfSizes.length && mpfSizes[m] > 0) cursor += mpfSizes[m];   // ← 用原始 size
}
```

当前样本 MPF 只有两个条目（主图 + 增益图），视频不在 MPF 里，所以没暴露。若某厂商把视频也登记为 MPF 条目（Google 旧式 MicroVideo），替换后该条目的 `size` 仍是旧值，其后条目的偏移也会错。**修复**：把被替换的那一条的 size 写成 `newVideoLen`，并在循环里用更新后的值累加。

### P1-11　导出的 EXIF 缩略图仍是旧图

**位置**：`Meta.java:644-664`（整段复制 EXIF）

IFD1 缩略图随 EXIF 段原样搬运，内容是没有水印的旧图。多数相册用主图，但文件管理器/某些旧版相册会先显示 EXIF 缩略图。**修复**：要么重写 IFD1（成本高），要么提供"移除缩略图"选项。

---

## 三、P2：代码卫生

| 编号 | 位置 | 问题 |
|---|---|---|
| P2-1 | `WatermarkPainter.java:32` | 注释 `// 0 无 1 徕卡 2 哈苏 3 蔡司 4 理光` 与实现不符（实际 3=哈苏H、4=蔡司、5=理光、6=苹果、7=自定义） |
| P2-2 | `WatermarkPainter.java:38-41` | `glassHeightPct` 的注释被复制到了 `gapPct` 上 |
| P2-3 | `WatermarkPainter.java:305/352` | `drawLiquidText`/`drawFrostText` 的 `u` 参数从未使用 |
| P2-4 | `MainActivity.java:192` | `getParcelableExtra(EXTRA_STREAM)` 在 API 33 起废弃，targetSdk 34 应改用 `getParcelableExtra(name, Uri.class)` |
| P2-5 | `VideoWatermark.java:110` | `totalBytes` 计算后从未使用 |
| P2-6 | `res/raw/glass_frag.glsl` | 4.6KB 死资源，`R.raw.glass_frag` 无任何引用，可删除 |
| P2-7 | `MainActivity.java:194-196` | `srcPath` extra 可指向任意本地路径，构成任意文件读取入口（仅限本应用有权限的文件）。建议限制为 `getExternalFilesDir()` 或去掉该入口 |
| P2-8 | `MainActivity.java:187-204` | 未处理 `ACTION_SEND_MULTIPLE`；外部意图无来源校验（`exported=true` 且无权限保护） |
| P2-9 | `VideoWatermark.java:532-542` | `extractRange` 的 skip 在 provider 不支持时退化为逐字节 `read()`，30MB 偏移会非常慢。建议用 `ParcelFileDescriptor` + `FileChannel.position()` |
| P2-10 | `Meta.java:164-170` | `xmpText` 用字符索引切分 `xmpBytes` 的字节数组（`System.arraycopy(xmpBytes, 0, prefix, 0, xmlAt)`）。当前 XMP 前缀是纯 ASCII 所以正确，一旦前缀含非 ASCII 就会截错 |
| P2-11 | `Meta.java:760-765` | XMP 段识别用 `s[4]=='h' && s[5]=='t'`，过弱；建议匹配完整前缀 `http://ns.adobe.com/xap/1.0/` |
| P2-12 | `ControlPanel.java:296` | `deserialize` 对损坏数据静默失败，无任何提示 |
| P2-13 | `ControlPanel.java:218-231` | 套用预设后 `activePreset` 不会因手动改滑杆而失效，胶囊仍高亮"正在用"，与实际参数不符 |

---

## 四、安全与隐私

**结论：没有可利用的高危面。** 无网络权限、无网络代码、无第三方 SDK、无动态加载、无设备标识采集，`allowBackup=false`。`exported=true` 的 Activity 只接收 `image/*`，最坏情况是第三方应用让它把一张用户自己的图复制进相册。

需要留意的两点：

1. **`srcPath` 任意路径读取**（P2-7）。虽然只能读应用有权限的文件，但它把"外部应用可指定任意路径"这件事暴露了出来，建议移除或加白名单。
2. **临时文件残留**。`buildWatermarkedVideo` 的失败路径中 `vl_out.mp4` 的删除依赖调用方，`vl_src.mp4` 在 `extractRange` 抛异常时不会删（`finally` 里只删 `tmpIn`，但 `extractRange` 在 try 外）。建议统一在 `finally` 清理 `getCacheDir()` 下的 `vl_*.mp4`。

---

## 五、优化建议（不改行为，只提效率与画质）

### 5.1 视频合成：把"每帧全量重算"降级为"每帧只采样"

当前每帧都跑一次完整链路：`layout()`（最多 9 次 `Paint` 分配 + 文本测量）→ `composeOn` → `GlassRenderer.render`（`glReadPixels` 同步阻塞）。实测 648×864 / 93 帧耗时约 1.1 秒，折算 4K 会到 30 秒量级。

可行的分层优化（收益递增）：

1. **缓存 layout**：整段视频的 `Style` 不变，`WatermarkPainter.layout(W,H,st)` 只需算一次，把结果传入 `composeOn` 的重载。
2. **缓存文字层**：文字与 Logo 与背景无关，可预渲染成一张带 alpha 的图层，每帧只做 `drawBitmap`。
3. **缓存玻璃几何**：`sdRoundBox` 的 SDF、`profile(d)` 高度场、法线方向都只依赖几何与 uniform，与背景无关。预计算一张 RGBA 的"位移图 + 法线图"，着色器每帧只做纹理采样与混合，可省掉最贵的分支。
4. **避免 `glReadPixels` 往返**：视频路径其实不需要回读——可以直接把解码帧作为纹理渲染到编码器输入 Surface，省掉"GL→Bitmap→GL"的两跳。这是最大的架构级优化，能把 4K 视频处理压到接近实时。

### 5.2 图像路径

- `ByteArrayOutputStream(1<<22)` + `toByteArray()` 会在峰值再复制一份完整 JPEG（50MP 图约 20MB）。可改为先写临时文件再流式拼装，或让 `assemble` 接受 `OutputStream` 分段写入。
- `Meta.parse` 为了定位 EOI 会把整个文件读一遍，而 `loadImage` 之后 `ImageDecoder` 还会再读一遍。若能在读到 SOS 后按文件长度反推 EOI（尾部长度已知时），可以省一次全文件 IO。
- `makeBackdrop` 用 `140px → 420px` 两步缩放做模糊，成本低但放大后有明显块状。可改用 `RenderScript`/`RenderEffect`（API 31+）的 `createBlurEffect`，或一次 `Bitmap.createScaledBitmap` 到 420 宽再轻度高斯。

### 5.3 结构

- `MainActivity` 808 行承担了载入、预览、特写、导出、视频编排五件事。建议拆出 `ExportTask`（导出流程 + 进度）与 `PhotoSession`（当前图的状态：uri/meta/photo/dirty），顺带解决 P0-3 的字段竞态。
- `Style` 是可变对象且被多线程读（`copy()` 传值已经规避了大部分问题），但 `layout()` 里会写 `st.name = ""` 这种副作用，建议改成局部变量。

---

## 六、可以新增的功能

按"实现成本 / 用户价值"排序，前五项建议优先：

| # | 功能 | 说明 | 成本 |
|---|---|---|---|
| 1 | **批量导出** | 多选照片，套用同一套参数一次导出。当前一次只能处理一张，而"给一组照片统一加同款水印"是这类工具的核心场景 | 中 |
| 2 | **完整预设（含文案与版式）** | 现在 `serialize()` 只存 14 个玻璃参数，机型名/副标题/Logo/字体/版式都不存。用户每次换图都要重填 | 低 |
| 3 | **拖动水印位置** | 现在只有"左/中/右 + 距底高度"。直接在预览上拖拽面板，是最符合直觉的交互（`Panel.x/y` 已经是自由坐标） | 中 |
| 4 | **导出后直接分享** | 导出完成弹 `ACTION_SEND`，省掉"去相册找图"这一步 | 低 |
| 5 | **HDR 视频的 tone mapping 开关** | 配合 P0-1，"保持 HDR（HLG）/ 转为 SDR"二选一，并给出预览对比 | 中高 |
| 6 | **水印倾斜/旋转** | 现在只有 `skew`（斜体），没有整体旋转。适合斜置水印 | 低 |
| 7 | **水印透明度与混合模式** | `u_trans` 控制的是玻璃透射，不是整体不透明度 | 低 |
| 8 | **导出格式选择** | PNG（无损）/ WebP / AVIF；q100 JPEG 对纯色块仍会有振铃 | 低 |
| 9 | **机型/参数手动编辑** | 现在参数只能从 EXIF 读或手填字符串，可提供"焦距/光圈/快门/ISO 分字段输入 + 自动格式化" | 低 |
| 10 | **撤销/重做** | 调参过程容易调飞，尤其玻璃参数有 13 个 | 中 |
| 11 | **浅色主题** | 界面硬编码深色。补 `values-night` 反而更简单（当前是"永远深色"） | 中 |
| 12 | **Web 版与 App 参数互通** | 通过 URL hash / 二维码传递预设，两端视觉一致 | 中 |

---

## 七、建议的修复顺序

```
第一批（正确性，必须修）
  P0-1 HDR 视频色彩        ← 影响所有 HDR 实况照片，且静默出错
  P0-2 音画偏移            ← 100ms，可感知
  P0-3 选图竞态            ← 元数据错配，用户无法察觉
  P0-4 fileSize 兜底       ← 数据丢失

第二批（稳定性）
  P1-1 预览位图泄漏
  P1-7 导出防重入
  P1-2 / P1-3 GL 资源释放
  P1-5 重复 initLogos

第三批（体验与性能）
  P1-6 4K 内存
  5.1 视频合成优化
  P2 系列清理
```

---

## 八、验证方法（可复现）

```bash
cd D:/data/code/liquidglass/android

# 1) 元数据单元测试（69 条断言）
./run-tests.sh

# 2) 装到项目自带模拟器
./emu.sh install -r VitreLens.apk
./emu.sh logcat -c
./emu.sh shell "am start -n com.vitrelens.app/.MainActivity \
    --es srcPath /sdcard/Pictures/dyn8.jpg"
./emu.sh shell input tap 632 108          # 导出
./emu.sh logcat -d -s VitreLens:*

# 3) 校验产物：抽尾部视频、比 colr / elst / MPF / XMP
#    本次评审用的脚本思路见下
```

- **色彩**：读 `moov/trak/mdia/minf/stbl/stsd/avc1/colr` 的 `nclx` 四元组，比较输入输出；
- **音画**：读 `moov/trak/edts/elst` 的 `segment_duration` 与 `media_time`，比较两轨相对偏移；
- **容器一致性**：读 MPF 的 `MP Entry`，验证 `tiff + offset[n] == trailer_start`、`size[0] == trailer_start`、`trailer_start + size[1] == file_size`，以及 XMP `Item:Length` 与实际视频字节数相等。

本次实测的三张样本结论：MPF / XMP / 增益图**全部自洽**，问题集中在视频的色彩与时间轴。

---

*评审基于 commit `ecb534c`。文中「已实测」项均有模拟器产物与二进制比对支撑；「读码推断」项给出了触发条件与修复方案，未构造复现。*
