# 本地品牌图标（不随仓库分发）

把高清品牌标识放在这里，构建时会自动嵌入 APK；**本目录已在 `.gitignore` 中排除**，
不会进 GitHub（品牌标识是各自公司的注册商标，只在本地自用）。

文件名固定为（PNG，建议 512~1024px 宽，透明背景）：

```
local-logos/leica.png
local-logos/hasselblad.png
local-logos/zeiss.png
local-logos/ricoh.png
```

`build.sh` 会把它们复制到 `app/res/drawable-nodpi/logo_*.png`。

**没放文件也能正常构建**——会自动写入 1×1 透明占位图，此时水印回退到内置的矢量绘制。
