# VitreLens

给照片叠加**可折射背景的液态玻璃水印**——水印不是贴一层半透明色块，而是一块真正会折射、
有色散、有厚度的玻璃。支持自定义机型名、品牌标识、拍摄参数、时间，导出保持原始分辨率，
并且**保留动态照片（实况照片）与 HDR 增益图**。

包含两个实现：

| 目录 | 形态 | 说明 |
| --- | --- | --- |
| 根目录 | 纯前端网页 | 双击 `index.html` 即可用，无构建、无第三方依赖 |
| [`android/`](android/) | 原生 Android App | 解决浏览器做不到的事：动态照片视频打水印、保留 EXIF/MPF/增益图/ICC |

## 为什么需要原生 App

浏览器里能做出很漂亮的玻璃，但有两件事做不到：

1. **动态照片的视频段打水印** —— 需要把 JPEG 尾部的 MP4 解码、逐帧合成、重新编码，
   再把新视频装回容器并同步改写 XMP 里的长度。这是文件级操作，浏览器碰不到。
2. **完整保留元数据** —— EXIF、MPF 多图结构、HDR 增益图、原厂 ICC 配置，
   都要在重编码后原样搬回去，否则相册里的 ProXDR 和「实况」都会失效。

## 玻璃是怎么做的

移植 three.js `MeshPhysicalMaterial` 的透射模型：

1. 圆角矩形 SDF → 高度场（边缘在 `depth` 宽度内做圆弧收边）
2. 对高度场求梯度 → 表面法线
3. 视线按 Snell 定律折射：`refract(-v, n, 1/ior) * thickness`
4. 光线走完 `thickness` 后落到背景，落点偏移就是画面被掰弯的量
5. 色散 = 三通道用 `ior ± dispersion*0.5` 各采一次
6. 粗糙度 = 在清晰/模糊多级 mip 之间插值
7. 反射率 = 菲涅尔项 + 高光

唯一的简化：原版用几何网格，我们用**解析高度场**——水印是规则圆角矩形，
高度场能直接算出来，省掉整个 mesh。

细节见 [`android/README.md`](android/README.md)。

## 构建

网页版：无构建，直接打开 `index.html`。

Android：需要 Android SDK Build-Tools 34 与 JDK 21，然后

```bash
cd android
./build.sh          # 自动递增版本号，产出 VitreLens.apk
./run-tests.sh      # 元数据重组的单元测试
```

`android/sdk/`、`reference/` 与签名密钥都已在 `.gitignore` 中排除，需自备。

## 应用内更新

点标题右侧的版本号可以检查更新；启动时也会静默查一次（每天最多一次，失败不打扰）。

流程是 `GitHub Releases API → 下载 APK → 交给系统安装器`，实现在 `Updater.java`：

- 版本比较按点分段，`1.44 > 1.39`
- 下载进度：GitHub 的直链常走 chunked、不给 `Content-Length`，拿不到长度时退化成「已下载 X KB」
- 安装前用 `PackageManager.getPackageArchiveInfo` 校验包名，链接被换掉也装不上别的东西
- Android 8+ 没有「安装未知应用」权限时，直接跳到对应的设置页
- **国内直连 `github.com` 的下载服务器常常不通**，失败对话框里有「用浏览器打开」的兜底

APK 通过自写的 `ApkProvider`（只读 `ContentProvider`）以 `content://` 交给安装器 ——
本项目没有 AndroidX，用不了 `androidx.core.content.FileProvider`，
而 Android 7 以后用 `file://` 传 APK 会直接抛 `FileUriExposedException`。

## 许可

**GNU General Public License v3.0**，见 [`LICENSE`](LICENSE)。

Copyright (C) 2026 VitreLens contributors

本程序是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
（第 3 版或你选择的任何更新版本）的条款重新分发和/或修改它。
本程序分发时希望它有用，但不提供任何担保，甚至不提供适销性或特定用途适用性的默示担保。

你可以自由使用、修改、分发；但分发修改版时必须同样以 GPLv3 公开源代码。

第三方成分、商标说明见 [`THIRD_PARTY.md`](THIRD_PARTY.md)。要点：

- 玻璃着色器移植了 three.js 的透射公式（MIT，与 GPLv3 兼容）
- 参数语义参考 [@specy/liquid-glass](https://github.com/Specy/liquid-glass)（未复制代码）
- 界面视觉参考 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（未使用该库）
- Leica / Hasselblad / ZEISS / RICOH 是各自公司的注册商标，本项目仅用于在个人照片上标注拍摄器材，与各公司无关联
