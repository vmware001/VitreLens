# VitreLens（Android 端）

液态玻璃水印的原生 App，用来解决浏览器做不到的两件事：

1. **动态照片的视频也能带水印**：把尾部的 MP4 解码 → 逐帧合成水印 → 重编码 →
   装回 JPEG 尾部，并同步改写 XMP 容器目录里的视频长度。
2. **OPPO ProXDR / HDR 增益图**：保留 ICC、增益图标记段（`urn:iso:std:iso:ts:21496:-1`）、
   MPF 多图结构和 EOI 之后的增益图 JPEG。

网页版（`../index.html`）功能更全、可交互调节更方便；涉及上面两项时用这个 APK。

## 产物

```
VitreLens.apk     已签名，可直接安装（applicationId: com.vitrelens.app）
```

安装：拷到手机点开安装，或 `adb install -r VitreLens.apk`。
最低 Android 10（API 29）。debug keystore 签名，不需要关闭「未知来源」以外的额外设置。

导出的照片在相册 `Pictures/VitreLens/`。

## 构建

```bash
cd android
./build.sh          # 导出色器 → 跑单元测试 → aapt2/javac/d8/zipalign/apksigner
./run-tests.sh      # 只跑元数据处理测试（纯 JVM，不需要设备）
```

依赖：`sdk/`（build-tools 34.0.0 + platforms/android-34 + platform-tools）、
`C:/Users/esetp/tools/jdk21`。

## 目录

| 文件 | 作用 |
| --- | --- |
| `app/src/com/vitrelens/app/MainActivity.java` | UI、解码、合成、导出到 MediaStore |
| `app/src/com/vitrelens/app/GlassRenderer.java` | 离屏 EGL + GLES2，跑液态玻璃着色器 |
| `app/src/com/vitrelens/app/WatermarkPainter.java` | 水印版式与文字/Logo 绘制 |
| `app/src/com/vitrelens/app/Meta.java` | JPEG 容器解析重建、MPF、XMP 目录、HEIF 取 EXIF（**不依赖 Android**，可 JVM 测试） |
| `app/src/com/vitrelens/app/VideoWatermark.java` | 动态照片视频转码：MediaCodec 解码 → 合成 → H.264 编码 → MediaMuxer 封装 |
| `app/src/com/vitrelens/app/SurfaceBlitter.java` | 把合成好的帧贴到编码器输入 Surface（EGL_RECORDABLE_ANDROID） |
| `app/src/com/vitrelens/app/Composer.java` | 水印合成（照片导出与视频逐帧共用） |
| `app/res/raw/glass_frag.glsl` | **由 `tools/extract_shaders.py` 从 `js/glass.js` 生成，不要手改** |
| `tools/extract_shaders.py` | 网页版是着色器唯一真源，构建时自动同步 |
| `tools/MetaTest.java` | 拿 `../reference` 里的真机样张做回归测试 |

## 视频水印怎么做的

```
JPEG 尾部 = [增益图 JPEG][原 MP4]
                 ↓
   抠出 MP4 → MediaCodec 解码(HEVC) → ImageReader(RGBA_8888) 拿位图
                 ↓
   Composer 逐帧合成（和照片导出同一套代码，位置/质感完全一致）
                 ↓
   MediaCodec 编码 H.264 + SurfaceBlitter 上传 → MediaMuxer 封装（音轨原样搬运）
                 ↓
   新尾部 = [增益图 原字节][新 MP4]，同时改写 XMP 里 MotionPhoto 的 Item:Length
```

编码用 H.264 而不是 HEVC，是为了兼容性（各家相册都能播）。帧率/码率按原片的
帧数和时长推算，码率约 0.13 bit/像素/帧。

## 功能（与网页版对齐）

四个分区，共 30 项可调：

| 分区 | 内容 |
| --- | --- |
| 01 水印内容 | 相机名称 / 副标题 / 拍摄参数 / 时间地点、品牌 Logo（5 选 1）、副标题·Logo·参数·时间 四个显示开关、从 EXIF 重新读取 |
| 02 版式 | 位置（左下/居中/右下）、水印宽度、距底高度、机型字号、参数字号、字体（无衬线/窄体/衬线/等宽）、机型材质（液态玻璃/磨砂玻璃/不透明）、斜体 |
| 03 液态玻璃 | 预设（清透/磨砂/厚玻璃）、折射率、圆角 R、**深度**、**厚度**、**粗糙度**、**色散**、**反射率**、**透射率**、高光、投影、加白、细分、色调 |
| 04 导出 | 实况照片水印、视频水印高度、视频水印宽度 |

### 玻璃模型：移植 three.js MeshPhysicalMaterial 透射

`@specy/liquid-glass` 的真实做法（作者博客有写）：**html2canvas 截图当背景纹理** →
铺在挤出的圆角玻璃体下方 → 交给 **three.js 内置的 `MeshPhysicalMaterial`** 做透射。
也就是它并没有自己写着色器，用的是 three.js `transmission_fragment.glsl.js` 里的公式。

我们逐条对上（`res/raw/glass_frag_pbr.glsl`）：

| three.js | 我们 |
| --- | --- |
| `transmissionRay = refract(-v, n, 1/ior) * thickness` | 同（**不除 cos**，thickness 是光程长度） |
| `roughness * clamp(ior*2-2, 0, 1)` → 选 mip | 同，用三级近似 mip（清晰 / ÷22 / ÷72 模糊） |
| `iors = ior ± dispersion*0.5`，三通道分别采样 | 同 |
| `totalDiffuse = mix(diffuse, transmitted, transmission)` | 同（diffuse 用环境色） |
| 几何倒角产生法线 | 解析高度场求梯度（不需要三角面） |

差异只有一处：**它用几何网格，我们用解析高度场**——水印是规则圆角矩形，
高度场可以直接算出来，省掉整个 mesh 和顶点法线。

### 早期版本：照原理手写的那一版

原版是 Three.js 做的**真几何网格 + 光线折射**，参数表是
`depth / segments / radius / tint / roughness / transmission / reflectivity / ior / dispersion / thickness`。
我们把几何换成解析高度场（不需要网格），折射核心照搬：

1. 圆角矩形 SDF → **高度场**：中间平，边缘在 `depth` 宽度内做圆弧收边
2. 对高度场求梯度 → 表面法线 `N`
3. 视线按 **Snell 定律**折射进玻璃：`refract(vec3(0,0,-1), N, 1/ior)`
4. 光线走过 `厚度 + 局部高度` 后落到背景平面，落点偏移就是背景被掰弯的量
5. **色散** = 三个通道用 `ior*(1±disp)` 各折射一次
6. **粗糙度** = 在清晰/模糊两张背景之间插值
7. **反射率** = 菲涅尔 `(0.045 + 0.955·(1-Nz)⁴) · refl` 叠一层环境高光
8. **透射率** = `mix(原始背景, 折射结果, trans)`
9. `segments` 在解析实现里对应法线采样步长（越高边缘越锐）

对应关系：`depth ↔ 深度`、`thickness ↔ 厚度`、`roughness ↔ 粗糙度`、
`dispersion ↔ 色散`、`reflectivity ↔ 反射率`、`transmission ↔ 透射率`、`tint ↔ 色调`。

> 着色器在 `res/raw/glass_frag_pbr.glsl`（Android 专用，手写）。
> `js/glass.js` → `glass_frag.glsl` 那条老管线保留给网页版，两边暂时并存。

### 横向扭曲（不是色散）

左右方向的「隔着玻璃砖看东西」的**扭曲**，和彩色边缘是两回事：

```glsl
refr.x += (-nxy.x * bulge * 1.55 + wobble * 1.25) * eta * zr * 1.35 * depthT * u_warp;
refr.x += -nxy.x * (1.0 - abs(nxy.y)) * eta * zr * 0.55 * u_warp;   // 上下边缘再叠柱面压缩
```

`横向扭曲` 滑块 0~200%。

### 横向色散

着色器里色散的位移方向按「以水平为主」重写：

```glsl
vec2 caDir = vec2(outward.x * 2.10, outward.y * 0.62);   // 左右边缘比上下强 3 倍多
caDir += vec2(nxy.x * 0.65, nxy.y * 0.18) * (1.0 - lens); // 内部也带一点横向错位
float caGain = 0.30 + 0.60 * bulge;                       // 越靠边角越强
vec2 ca = caDir * lens * u_chroma * eta * zr * caGain * 2.0;
```

默认值 70%，滑块可 0~100 调。这是**唯一真源在 `js/glass.js`**、由工具生成到 `res/raw/`。

### 界面

参考 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的视觉语言：
导入照片后，整屏背景换成该照片的模糊副本，上面浮着半透明玻璃卡片
（22dp 圆角、1dp 白色描边、#16 白色填充），滑块用玻璃轨道 + 球面滑块。

## 两个画面瑕疵的成因与修法

**① 玻璃底部出现横向条纹拖影**
折射采样会越过画面边界，被 `CLAMP_TO_EDGE` 拉成一整条。修法：把位移换算到**整张照片的 uv 空间**
（新 uniform `u_crop`），再按「离照片边缘还剩多少」限幅：

```glsl
vec2 photoUv = u_crop.xy + uv * u_crop.zw;
vec2 dPhoto  = (refr / u_res) * u_crop.zw;
dPhoto.x = clamp(dPhoto.x, -photoUv.x * 0.9, (1.0 - photoUv.x) * 0.9);
dPhoto.y = clamp(dPhoto.y, -photoUv.y * 0.9, (1.0 - photoUv.y) * 0.9);
```

水印贴到画面边缘时折射会自动减弱，不再拖影。

**② 画面上出现细竖线（玻璃渲染块与原始像素的交界）**
渲染块四周的留白从 40u/60u 加到 70u/90u（投影高斯扩散约 20u，保证边界处已完全无变化）；
同时纹理上限从 4096 放到设备真实值（通常 8192+），`stripMax` 不再硬截 3600，
普通照片不会再被切成两条分别渲染、避免接缝。

## 色彩配置（ICC）必须原样搬运

- **JPEG 源**：直接把原来的 APP2 ICC 段带过去（优先于重编码器自己生成的 profile）
- **HEIC/HEIF 源**：色彩配置在 `meta → iprp → ipco → colr`（类型 `prof`/`rICC`）里，
  `Meta.heifIcc()` 把它取出来拼成标准的 APP2 `ICC_PROFILE` 段

之前 HEIC 这条是漏的，导出只能用编码器生成的替代 profile（520/536 字节），
和原厂的 548 字节 `Display P3` 不是同一份。现在字节完全一致（有单测校验）。

## OPPO 的 HDR 增益图藏在哪（实测）

`reference/` 里那张「相册 → 转换为兼容格式」产出的 JPEG 揭示了一切：

**HEIC 的增益图不在 HEIF 的 item 里，而在最后一个 box 之后的私有尾块**：

```
[4B 长度][4B float 1.2（增益上限）][头部：机型名等，补齐到 4384 字节]
[内嵌 JPEG 3072×4096 —— 增益图本体，约 3.5MB]
```

- 尾块**自描述**：开头 4 字节（大端）就等于自身长度
- 相册转 JPEG 时把它**原样搬到 EOI 之后**，不加 MPF、也不加 XMP
- 早期版本只从 HEIF 取 EXIF，把这 3.5MB 整个丢了 → ProXDR 消失

现在 `Meta.heifTrailerStart()` 会沿着 box 链走，**遇到类型不是可打印 ASCII 的 box 就停**（那就是尾块），
记录 `trailerStart`/`trailerLength`，`assemble` 原样搬运。JPEG 侧也用"自描述长度"识别这类尾块，
比找标记段可靠。

## 实况照片的取景差异（实测）

用二维配准把原图的静态帧和视频首帧对齐，得到（`dynamic_photo.jpg`）：

```
视频取景 = 照片的 94%   （裁掉约 6%）
水平偏移 0.0%           （水平居中）
垂直偏移 6.0%           （贴着底边裁，裁的是上边）
```

所以视频里的水印不能和照片用同一套坐标。默认按实测值换算：

- `视频水印高度` 默认 **+5%**、`视频水印宽度` 默认 **106%**（= 92% / 0.94）
- 两个都能单独微调

## 真机/模拟器实测记录

在 MuMu 模拟器（Android 12 / x86_64）上跑通了完整链路，过程中踩到四个真问题：

1. **解码器不能用 ImageReader(RGBA_8888) 当输出面**
   `configure` 直接失败：`setPortMode on output to DynamicANWBuffer failed w/ err -1010`。
   硬解码器输出端口只认 YUV。改成 **ByteBuffer 输出 + `getOutputImage()`**，
   自己做 YUV_420_888 → ARGB（按行批量拷再算，BT.601 整数运算）。
2. **解码器要能回退**
   系统首个 HEVC 解码器（`OMX.qcom.video.decoder.hevc`）在这台机器上是个残缺实现，
   `configure` 返回 -61。改成按 `MediaCodecList` 顺序逐个尝试，
   第一个失败就换下一个（真机上第一个通常是硬解，最快）。
3. **`MediaMuxer` 要求视频轨第一帧必须带 `BUFFER_FLAG_KEY_FRAME`**
   这是最隐蔽的一个：不带标志时 `writeSampleData` **不抛异常、静默丢弃每一个样本**，
   结果成片 moov 里视频 `stbl` 是空的、只有音轨。表现为「导出成功但视频 0 帧」。
   我当初为了区分音视频轨，把 `BufferInfo.flags` 换成了一个 `boolean`，把关键帧标志丢了。
4. **10bit HEVC 在模拟器上解不了**
   `C2SoftHevcDec: Fatal error 0x43dd`，模拟器自带播放器同样失败（`NuPlayerDecoder ... err=-61`），
   属于模拟器能力限制。真机（骁龙 8 Elite）硬解 Main10 没问题。
   现在解不了会**优雅降级**：照片照常导出、原视频原样保留、弹框说明原因。

### 测试用替身片

`build/dyn8.jpg`：把原动态照片的视频换成 8bit H.264（ffmpeg 转码），
XMP 里的 `MotionPhoto Item:Length` 改写并用空格补齐，保持 XMP 段长度不变，
所以 MPF 偏移依然有效，是整个文件结构完全合法的动态照片。

```
ffmpeg -i test.mp4 -c:v libx264 -profile:v high -pix_fmt yuv420p \
       -vf "scale=648:864,setsar=1" -c:a copy -movflags +faststart test8.mp4
```

## 已覆盖的测试（60 项，全绿）

用用户提供的两张真机文件跑：

- `IMG20261025201847_config.jpg`（ProXDR 静图：ICC + 增益图标记 + MPF 双图 + 1.3MB 增益图尾）
- `dynamic_photo.jpg`（OPPO 动态照片：MPF + 10MB 视频尾）
- `IMG20261025201847.heic`（6144×8192，Exif 项在 `meta→iloc` 里）

校验点：段结构完整、ICC 不重复、MPF 主图 size == 新尾部起点、
每个副图偏移精确落在 `FF D8` 且首尾相接、尾部逐字节一致、EXIF 字段可回读。
外加「换掉视频段」的专项：XMP 长度改写正确、增益图逐字节保留、新视频完整写入、
MPF 偏移跟着移动、EOI+增益图+视频 == 文件长度。

## 已知限制

- 视频重编码耗时：模拟器上 93 帧 648×864 约 20 秒（纯 CPU 软解软编）；
  真机走硬解硬编会快很多。
- 视频编码统一用 **H.264**（不是 HEVC），兼容性最好；10bit 源会被转成 8bit。
- ICC 优先沿用**原图那段**（OPPO 的 ProXDR 认的是 `Display P3 Gamut with sRGB Transfer`），
  只有原图没有 ICC 时才用重编码器生成的。段顺序也和原图一致。
- `OpCamera:VideoLength` 是 OPPO 私有字段，语义不明（原文件里它也不等于任何实际字节数），
  保持原值不动；真正决定容器布局的是 `Container:Directory` 的 `Item:Length`，那个会更新。
- 着色器与界面交互只做了静态检查，没有真机/模拟器跑过（这台机器没有 adb 设备）。
- 超大图按堆上限限制在 56MP，超过会等比缩小（界面会提示原图尺寸）。

## 许可

本项目以 **GNU General Public License v3.0** 发布，见仓库根目录的 `LICENSE`。

你可以自由使用、修改、分发；但**分发修改版时必须同样以 GPLv3 公开源代码**。

第三方成分与商标说明见仓库根目录的 `THIRD_PARTY.md`，摘要：

- 玻璃着色器移植了 **three.js** `MeshPhysicalMaterial` 的 transmission / dispersion 公式（MIT，与 GPLv3 兼容）
- 参数语义参考 **@specy/liquid-glass**（MIT），未复制其代码
- 界面视觉参考 **Kyant0/AndroidLiquidGlass**，未使用该库
- 相机品牌标识（Leica / Hasselblad / ZEISS / RICOH）是各自公司的注册商标，仅用于个人照片标注

APK 内不含任何第三方库：只有本项目自己的 `classes.dex` 与资源，没有 native `.so`。

### 仓库里不包含的东西

`.gitignore` 已排除以下内容，克隆后需自备：

| 排除项 | 原因 |
| --- | --- |
| `reference/` | 个人照片样张（含 GPS），见 `reference/README.md` |
| `android/sdk/` | Android SDK，约 350MB |
| `android/debug.keystore` | 签名私钥 |
| `android/build/`、`*.apk` | 构建产物 |

缺少 `reference/` 时元数据单测会**自动跳过**（打印提示、退出码 0），不影响构建。

## 参考样张

`android/tools/MetaTest.java` 需要三类样张才能跑完整测试，放在 `reference/`（**不入库**）：

| 文件名 | 内容 |
| --- | --- |
| `IMG20261025201847_config.jpg` | ProXDR 静图（ICC + 增益图标记 + MPF 双图） |
| `dynamic_photo.jpg` | 动态照片（MPF + 视频尾） |
| `IMG20261025201847.heic` | HEIC（验证 `meta→iloc` 取 Exif、`colr` 取 ICC、box 之后的 HDR 尾块） |

换成自己的照片也行，只要同样是这三类。**目录为空时测试会自动跳过**（退出码 0），不影响构建。
