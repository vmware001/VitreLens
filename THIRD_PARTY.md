# 第三方代码与素材说明

本项目自身代码以 **GNU GPL v3.0** 发布（见 `LICENSE`）。以下为其中的第三方成分。

> 兼容性说明：three.js 为 MIT 许可，MIT 允许被并入 GPL 项目，无冲突。

## 1. 代码移植

### three.js — MIT

`android/app/res/raw/glass_frag_pbr.glsl` 里的液态玻璃着色器，移植了 three.js
`MeshPhysicalMaterial` 的透射（transmission）与色散（dispersion）公式，源码对应
`src/renderers/shaders/ShaderChunk/transmission_*.glsl.js`。该文件中以注释形式引用了
这些公式的原始写法，用于说明对应关系。

- 项目主页：https://threejs.org
- 许可：MIT（MIT 与 GPLv3 兼容，可并入本项目）

```
Copyright © 2010-2024 three.js authors
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

### @specy/liquid-glass — MIT

参考了它的**参数语义**（`depth / segments / radius / tint / roughness /
transmission / reflectivity / ior / dispersion / thickness`）与整体思路
（截图当背景 + 3D 玻璃体透射）。**未复制其源代码**。

- 项目主页：https://github.com/Specy/liquid-glass
- 作者说明：https://specy.app/blog/posts/liquid-glass-in-the-web

### Kyant0/AndroidLiquidGlass

界面视觉语言的参考（玻璃卡片、模糊背景）。**未复制其代码**，也未使用该库
（它是 Compose Multiplatform 库，本项目是原生 View）。

- 项目主页：https://github.com/Kyant0/AndroidLiquidGlass

## 2. 随仓库分发的第三方库（仅网页版）

### libheif — LGPL-3.0

`js/vendor/libheif-bundle.js` 是 libheif 的 Emscripten 构建（wasm 内嵌），
网页版用它解码 HEIC/HEIF。许可全文见 `js/vendor/libheif-LICENSE.txt`。

- 项目主页：https://github.com/strukturag/libheif
- 许可：LGPL-3.0
- 兼容性：LGPLv3 明确允许并入 GPLv3 作品（LGPLv3 §2），本项目整体以 GPLv3 发布，无冲突。
- **Android 版不使用它**——原生解码走系统 `ImageDecoder` / `MediaCodec`，APK 内无此库。

> 该 bundle 是预编译产物，建议后续在仓库里注明其来源与版本号，便于复现。

## 3. 随 APK 分发的字体（SIL Open Font License 1.1）

`android/app/res/font/` 内置四款字体，许可原文在 `android/licenses/fonts/`：

| 文件 | 字体 | 许可 |
| --- | --- | --- |
| `inter.ttf` | Inter | SIL OFL 1.1 |
| `oswald.ttf` | Oswald | SIL OFL 1.1 |
| `playfair.ttf` | Playfair Display | SIL OFL 1.1 |
| `jetbrainsmono.ttf` | JetBrains Mono | SIL OFL 1.1 |

OFL 1.1 与 GPLv3 兼容，可随本项目一起分发。字体仅用于渲染水印文字，未修改字形。

> 内置字体只有拉丁字形；文本含中日韩字符时会自动退回系统字体，避免出现豆腐块。

## 4. 构建期工具（不随 APK 分发）

| 工具 | 许可 | 用途 |
| --- | --- | --- |
| Android SDK Build-Tools（aapt2 / d8 / zipalign / apksigner） | Apache-2.0 | 编译、打包、签名 |
| OpenJDK 21 | GPLv2 + Classpath Exception | 编译与运行测试 |
| Python 3 + Pillow + imageio-ffmpeg | PSF / HPND / BSD-2 | 生成测试素材、导出着色器 |

APK 内**不含**任何第三方库：`classes.dex` 只有本项目自己的类，也没有 native `.so`。

## 5. 商标与品牌图标

`WatermarkPainter` 支持在个人照片上标注拍摄器材的品牌标识，两种来源：

1. **内置矢量绘制**（徕卡 / 哈苏 H / 蔡司 / 理光 / 苹果）——本项目自己用 Canvas 路径绘制
2. **本地高清图标**（可选）——放在 `android/local-logos/`，构建时嵌入，
   **该目录已在 `.gitignore` 中排除，不随仓库分发**

品牌标识均为各自公司的**注册商标**，此处仅用于个人照片的器材标注。
本项目与上述公司无任何关联，也未获得其授权或背书。

## 6. 商标

`WatermarkPainter.drawLogo()` 用矢量绘制了 Leica、Hasselblad、ZEISS、RICOH 的品牌标识。
这些是各自公司的**注册商标**，此处仅用于在个人照片上标注拍摄器材。
本项目与上述公司无任何关联，也未获得其授权或背书。若用于商业场景，请自行评估商标使用风险。
