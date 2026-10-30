package com.vitrelens.app;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * 水印版式与文字绘制。
 * 尺寸按画幅宽度等比缩放（u = W / 1000），换算与网页版 js/watermark.js 保持一致。
 */
class WatermarkPainter {

    /** 全部可调参数，UI 直接绑这个对象 */
    static class Style {
        String name = "";
        String subtitle = "";
        String params = "";
        String meta = "";

        boolean showSubtitle = false;
        boolean showBrand = true;
        boolean showParams = true;
        boolean showMeta = true;

        int brand = 2;                 // 0 无 1 徕卡 2 哈苏 3 哈苏H 4 蔡司 5 理光 6 苹果 7 自定义
        /** 文字颜色跟随背景明暗自动切换（亮背景用深色字） */
        boolean autoText = true;
        /** 背景偏暗：不透明档的实色跟着走（浅色主题近白、深色主题近黑） */
        boolean themeDark;
        /** 文字色相 0~360；-1 表示不启用（保持白/黑自适应） */
        float textHue = -1f;
        /** 磨砂档的实色：0 = 按背景明暗自动，1 = 深色，2 = 浅色 */
        int frostTone = 0;
        /** true 时 depthPct / thicknessPct / edgePct 的单位是像素，而不是面板高度的百分比。
         *  界面上的玻璃面板尺寸差很大（左栏 54×204dp、右面板 272×248dp），
         *  用百分比会让两块的斜面宽度差出好几倍，看起来根本不是一个材质。 */
        boolean pixelUnits;
        /** 水平位置：0 = 贴左，50 = 居中，100 = 贴右 */
        float posXPct = 50f;
        String align = "center";       // 已废弃，仅保留字段兼容旧预设
        String font = "sans";     // sans / condensed / serif / mono
        String nameMaterial = "solid"; // liquid / frost / solid

        float widthPct = 92f;
        /** Logo 尺寸，100% = 跟随字号 */
        float logoSizePct = 100f;
        /** Logo 水平位置：0 = 紧贴名称，100 = 紧贴参数（默认）；logoRight 时此值不生效 */
        float logoPosPct = 100f;
        /** true = 把 logo 放到参数右侧 */
        boolean logoRight = false;
        /** 已废弃，仅保留字段兼容 */
        String logoPos = "right";
        /** 玻璃高度（垂直长轴方向），100% = 刚好包住文字 */
        float glassHeightPct = 100f;
        /** 玻璃高度（垂直长轴方向），100% = 刚好包住文字 */
        float gapPct = 3.8f;
        float nameSize = 24f;          // 每 1000px 画幅
        float metaSize = 12.5f;
        boolean italic = true;

        // 玻璃（参数对齐 @specy/liquid-glass 的 GlassStyle）
        float ior = 1.46f;
        float radiusPct = 100f;
        float depthPct = 24f;      // 边缘收边宽度 = 玻璃深度
        float thicknessPct = 36f;   // 玻璃厚度
        float roughPct = 20f;       // 粗糙度
        float dispPct = 6f;       // 色散
        /** 边缘折射强度：100% = 位移为斜面宽度的 2 倍（>100% 镜像感更强） */
        float edgePct = 140f;
        float warpPct = 0f;      // 横向扭曲
        float reflPct = 28f;       // 反射率
        float transPct = 98f;     // 透射率
        float glossPct = 28f;      // 高光
        float shadowPct = 18f;     // 投影
        float whitePct = 0f;       // 加白
        boolean tintOn = true;     // 色调
        int segments = 128;        // 法线采样细分：纯解析计算，越大越准且不额外耗时

        /** 视频水印高度微调（% 画幅宽度），正数=往上挪 */
        float videoGapShift = 2f;
        /** 视频水印宽度缩放（%），视频取景比照片窄，需要放大一点才和照片一致 */
        float videoWidthScale = 95f;

        Style copy() {
            Style s = new Style();
            s.name = name; s.subtitle = subtitle; s.params = params; s.meta = meta;
            s.showSubtitle = showSubtitle; s.showBrand = showBrand;
            s.showParams = showParams; s.showMeta = showMeta;
            s.brand = brand; s.align = align; s.font = font; s.nameMaterial = nameMaterial;
            s.posXPct = posXPct; s.autoText = autoText; s.themeDark = themeDark; s.pixelUnits = pixelUnits;
            s.textHue = textHue; s.frostTone = frostTone;
            s.widthPct = widthPct; s.gapPct = gapPct; s.glassHeightPct = glassHeightPct;
            s.logoSizePct = logoSizePct; s.logoPos = logoPos; s.logoPosPct = logoPosPct;
            s.logoRight = logoRight;
            s.nameSize = nameSize; s.metaSize = metaSize; s.italic = italic;
            s.ior = ior; s.radiusPct = radiusPct; s.depthPct = depthPct; s.edgePct = edgePct;
            s.thicknessPct = thicknessPct; s.roughPct = roughPct; s.dispPct = dispPct;
            s.reflPct = reflPct; s.transPct = transPct; s.glossPct = glossPct; s.warpPct = warpPct;
            s.shadowPct = shadowPct; s.whitePct = whitePct;
            s.tintOn = tintOn; s.segments = segments;
            s.videoGapShift = videoGapShift;
            s.videoWidthScale = videoWidthScale;
            return s;
        }
    }

    static class Panel {
        float x, y, w, h, r, bevel, edgeW;
    }

    static class Layout {
        Panel panel = new Panel();
        float padX, padY, u;
        float nameSize, metaSize, subSize, slant;
        float nameX, nameBaseline, nameW;
        float subX, subBaseline, subW, dotR, dotGap;
        float paramsX, paramsBaseline, paramsW;
        float metaX, metaBaseline, metaW;
        boolean paramsOn, metaOn, subOn;
        float logoX, logoY, logoW, logoH;
        boolean logoOn;
        /** 背景偏亮时改用深色文字（autoText 打开时才生效） */
        boolean darkText;
        /** 文字主色（RGB，不含 alpha）；由 textHue 决定，没启用时是白 */
        int textRgb = 0xFFFFFF;
        /** logo 与参数之间的白色短竖线；sepX <= 0 表示不画 */
        float sepX, sepTop, sepBottom;
        boolean hasContent;
    }

    private static final float TRACK_NAME = 0.026f;
    private static final float TRACK_META = 0.02f;
    private static final float TRACK_SUB = 0.02f;

    static Layout layout(int W, int H, Style st) {
        return layout(W, H, st, 0f, 0f, 0f);
    }

    /**
     * @param jsx 水平形变，-1~1（负=往左拉）。只用于预览的拖动手势，导出恒为 0。
     * @param jsy 垂直形变，-1~1
     */
    static Layout layout(int W, int H, Style st, float jsx, float jsy) {
        return layout(W, H, st, jsx, jsy, 0f);
    }

    /**
     * @param jp 长按程度 0~1：手指压下去，整块鼓起（变宽变高）
     */
    static Layout layout(int W, int H, Style st, float jsx, float jsy, float jp) {
        Layout L = new Layout();
        if (st.name == null) st.name = "";
        if (st.subtitle == null) st.subtitle = "";
        if (st.params == null) st.params = "";
        if (st.meta == null) st.meta = "";
        L.u = W / 1000f;
        if (st.textHue >= 0f) {
            L.textRgb = Color.HSVToColor(new float[]{
                    ((st.textHue % 360f) + 360f) % 360f, 0.62f, 1f}) & 0xFFFFFF;
        }
        float u = L.u;
        L.padX = 32 * u;
        L.padY = 22 * u;
        L.slant = st.italic ? 0.115f : 0f;

        float scale = 1f;
        // 最多收敛 6 次：水印变窄（比如视频用 95% 宽度）时，内容一次缩不够会溢出
        for (int attempt = 0; attempt < 6; attempt++) {
            float nameSize = st.nameSize * u * scale;
            float metaSize = st.metaSize * u * scale;
            float subSize = nameSize * 0.42f;

            Paint pName = stylePaint(nameSize, TRACK_NAME, st.font, true, st.name);
            Paint pMeta = stylePaint(metaSize, TRACK_META, "sans", false, st.params);
            Paint pSub = stylePaint(subSize, TRACK_SUB, st.font, false, st.subtitle);

            float nameW = pName.measureText(st.name) + (st.italic ? nameSize * 0.09f : 0);
            Paint.FontMetrics fmN = pName.getFontMetrics();
            float nameH = fmN.descent - fmN.ascent;

            boolean subOn = st.showSubtitle && st.subtitle.length() > 0;
            float dotR = subSize * 0.17f;
            float dotGap = subSize * 0.55f;
            float subW = subOn ? dotR * 2 + dotGap + pSub.measureText(st.subtitle)
                    + (st.italic ? subSize * 0.1f : 0) : 0;
            Paint.FontMetrics fmS = pSub.getFontMetrics();
            float subH = fmS.descent - fmS.ascent;

            float subGap = nameSize * 0.24f;
            float leftW = Math.max(nameW, subW);
            float leftH = nameH + (subOn ? subGap + subH : 0);

            boolean paramsOn = st.showParams && st.params.length() > 0;
            boolean metaOn = st.showMeta && st.meta.length() > 0;
            float paramsW = paramsOn ? pMeta.measureText(st.params) + (st.italic ? metaSize * 0.08f : 0) : 0;
            float metaW = metaOn ? pMeta.measureText(st.meta) + (st.italic ? metaSize * 0.08f : 0) : 0;
            Paint.FontMetrics fmM = pMeta.getFontMetrics();
            float lineH = fmM.descent - fmM.ascent;
            float lineGap = metaSize * 0.62f;

            // 哈苏是 10:1 的横排字标，按普通字号算高度只有 0.1 倍，小到看不清
            // Logo 高度不跟「机型字号」走：调名字大小时图标跟着变很怪。
            // 固定以 24u 为基准（等于默认字号），再乘 logoSizePct。
            float logoH = 24f * u * scale * (st.brand == 2 ? 1.2f : 1.0f) * (st.logoSizePct / 100f);
            float logoW = st.showBrand ? logoWidth(st.brand, logoH) : 0;

            float gapA = logoW > 0 ? nameSize * 0.55f : 0;
            // logo 与参数之间要留出竖线的呼吸空间，否则线会紧贴着两边
            float gapB = logoW > 0 ? nameSize * 1.05f : 0;
            float rightW = Math.max(paramsW, metaW);
            float rightH = (paramsOn ? lineH : 0) + (paramsOn && metaOn ? lineGap : 0) + (metaOn ? lineH : 0);
            float contentW = st.logoRight
                    ? leftW + gapA + rightW + gapB + logoW
                    : leftW + gapA + logoW + gapB + rightW;

            float margin = 38 * u;
            float panelW = Math.max(W * (st.widthPct / 100f), contentW + L.padX * 2);
            panelW = Math.min(panelW, W - margin * 2);
            float availW = panelW - L.padX * 2;

            if (contentW > availW + 0.5f && attempt < 5) {
                // 一次就按比例缩到位（再留 2% 余量），原来的 0.985 太保守，
                // 两次根本缩不到目标宽度
                scale *= Math.min(0.97f, availW / contentW * 0.98f);
                continue;
            }

            float contentH = Math.max(Math.max(leftH, rightH), logoW > 0 ? logoH : 0);
            float panelH = contentH * (st.glassHeightPct / 100f) + L.padY * 2;
            // 水平位置自由调节：0 = 贴左，100 = 贴右，50 = 居中
            float minX = margin;
            float maxX = Math.max(minX, W - margin - panelW);
            float fx = Math.max(0f, Math.min(100f, st.posXPct)) / 100f;
            float panelX = minX + (maxX - minX) * fx;

            float bottomGap = st.gapPct * W / 100f;
            float panelY = Math.max(0, H - bottomGap - panelH);

            // 拖动手感：像拉一块橡皮泥 —— 左右拉都把中段拉细（变窄），上下拉则顺着
            // 方向变高/变矮，长按压下去整块鼓起。形变方向永远跟着位移走，不会反着缩。
            if (jsx != 0f || jsy != 0f || jp != 0f) {
                float baseW = panelW, baseH = panelH;
                float cx = panelX + baseW / 2f;
                float cy = panelY + baseH / 2f;

                float wScale = 1f - Math.abs(jsx) * 0.20f;   // 左右都是收窄
                float hScale = 1f - jsy * 0.22f;             // 往上拉变高、往下拉变矮
                wScale *= 1f + jp * 0.14f;                   // 长按：鼓起
                hScale *= 1f + jp * 0.26f;
                panelW = baseW * Math.max(0.72f, wScale);
                panelH = baseH * Math.max(0.80f, hScale);

                // 收窄之后两侧才有余量，位移只用到这部分，不会把文字推出画幅
                float slack = Math.max(0f, (W - panelW) / 2f) * 0.9f;
                float push = Math.max(-slack, Math.min(slack, jsx * baseW * 0.10f));
                cx += push;
                cy += Math.max(-slack, Math.min(slack, jsy * baseH * 0.30f));
                panelX = cx - panelW / 2f;
                panelY = cy - panelH / 2f;
            }

            L.panel.x = panelX;
            L.panel.y = panelY;
            L.panel.w = panelW;
            L.panel.h = panelH;
            L.panel.r = (panelH / 2f) * (st.radiusPct / 100f);
            // 新版着色器直接从 depthPct / thicknessPct 算几何，这里保留字段给旧公式用
            L.panel.bevel = (panelH / 2f) * (st.depthPct / 100f);
            L.panel.edgeW = (panelH / 2f) * (st.depthPct / 100f);

            L.nameSize = nameSize;
            L.metaSize = metaSize;
            L.subSize = subSize;
            L.nameW = nameW;
            L.subW = subW;
            L.dotR = dotR;
            L.dotGap = dotGap;
            L.subOn = subOn;
            L.paramsW = paramsW;
            L.metaW = metaW;
            L.paramsOn = paramsOn;
            L.metaOn = metaOn;
            L.logoW = logoW;
            L.logoH = logoH;
            L.logoOn = logoW > 0;
            L.hasContent = st.name.length() > 0 || subOn || paramsOn || metaOn || L.logoOn;

            float cy = panelY + panelH / 2f;
            float leftTop = cy - leftH / 2f;
            L.nameX = panelX + L.padX;
            L.nameBaseline = leftTop - fmN.ascent;
            if (subOn) {
                L.subBaseline = leftTop + nameH + subGap - fmS.ascent;
                L.subX = panelX + L.padX;
            }

            // 右侧块的右边界。logo 放在参数右边时，右对齐的应该是 logo，参数得左移
            float rightEdge = panelX + panelW - L.padX;
            if (st.logoRight && logoW > 0) rightEdge -= (logoW + gapB);

            if (paramsOn || metaOn) {
                float rightTop = cy - rightH / 2f;
                float y = rightTop;
                if (paramsOn) {
                    L.paramsBaseline = y - fmM.ascent;
                    L.paramsX = rightEdge - paramsW;
                    y += lineH + lineGap;
                }
                if (metaOn) {
                    L.metaBaseline = y - fmM.ascent;
                    L.metaX = rightEdge - metaW;
                }
            }

            if (L.logoOn) {
                float leftEnd = panelX + L.padX + leftW;
                float rightStart = panelX + panelW - L.padX - rightW;
                float space = rightStart - leftEnd;
                float gx;
                if (st.logoRight) {
                    // 贴住面板右内边界，参数已经让出 logoW + gapB 的宽度
                    gx = rightEdge + gapB;
                } else {
                    // 0 = 紧贴名称，100 = 紧贴参数；中间连续可调。
                    // 两端各留 gapA / gapB，否则 logo 会直接压在参数上（竖线就夹在中间）
                    float lfx = Math.max(0f, Math.min(100f, st.logoPosPct)) / 100f;
                    float from = leftEnd + gapA;
                    float span = rightStart - gapB - from - logoW;
                    gx = (span > 0) ? (from + span * lfx) : (from);
                }
                L.logoX = gx;
                L.logoY = cy - logoH / 2f;

                // 白色短竖线永远落在 logo 与参数的正中（不管 logo 摆在哪）
                if (rightW > 0 && (st.logoRight || space > logoW)) {
                    // 永远落在 logo 与参数之间的正中，贴任何一侧都会显得挤
                    L.sepX = st.logoRight
                            ? (rightEdge + gx) / 2f
                            : (gx + logoW + rightStart) / 2f;
                    float half = Math.min(contentH, nameSize * 1.3f) * 0.5f;
                    L.sepTop = cy - half;
                    L.sepBottom = cy + half;
                }
            }
            return L;
        }
        return L;
    }

    /** 在已经合成好的画布上绘制文字层 */
    static void draw(Canvas c, Layout L, Style st) {
        float u = L.u;

        if (st.name.length() > 0) {
            Paint p = stylePaint(L.nameSize, TRACK_NAME, st.font, true, st.name);
            if ("liquid".equals(st.nameMaterial)) {
                drawLiquidText(c, st.name, L.nameX, L.nameBaseline, L.nameSize, L.slant, u, st.font, L.textRgb);
            } else if ("frost".equals(st.nameMaterial)) {
                drawFrostText(c, st.name, L.nameX, L.nameBaseline, L.nameSize, L.slant, u, st.font, L.textRgb);
            } else {
                // 亮背景上用深色字，阴影反过来用浅色，否则深字压深影会糊成一团
                p.setColor(textColor(L, 0xFF));
                p.setShadowLayer(Math.max(2f, u * 4.4f), 0, u * 1.0f,
                        L.darkText ? 0x40FFFFFF : 0x66000000);
                c.save();
                c.translate(L.nameX, L.nameBaseline);
                if (L.slant != 0) c.skew(-L.slant, 0);
                c.drawText(st.name, 0, 0, p);
                c.restore();
            }
        }

        if (L.subOn) {
            Paint pSub = stylePaint(L.subSize, TRACK_SUB, st.font, false, st.subtitle);
            pSub.setColor(textColor(L, 0xCC));
            pSub.setShadowLayer(Math.max(1f, u * 3f), 0, u * 0.8f,
                    L.darkText ? 0x33FFFFFF : 0x66000000);
            c.save();
            float bx = L.subX + L.dotR;
            c.translate(bx, L.subBaseline - L.subSize * 0.34f);
            if (L.slant != 0) c.skew(-L.slant * 0.8f, 0);
            c.drawCircle(0, 0, L.dotR, pSub);
            c.drawText(st.subtitle, L.dotR + L.dotGap, L.subSize * 0.34f, pSub);
            c.restore();
        }

        Paint pRight = stylePaint(L.metaSize, TRACK_META, "sans", false, st.params);
        pRight.setColor(textColor(L, 0xFF));
        pRight.setShadowLayer(Math.max(1f, u * 3f), 0, u * 0.8f,
                L.darkText ? 0x33FFFFFF : 0x66000000);
        if (L.paramsOn) {
            c.save();
            c.translate(L.paramsX, L.paramsBaseline);
            if (L.slant != 0) c.skew(-L.slant * 0.7f, 0);
            c.drawText(st.params, 0, 0, pRight);
            c.restore();
        }
        if (L.metaOn) {
            Paint pMeta2 = stylePaint(L.metaSize, TRACK_META, "sans", false, st.meta);
            pMeta2.setColor(textColor(L, 0xA6));
            pMeta2.setShadowLayer(Math.max(1f, u * 3f), 0, u * 0.8f, 0x55000000);
            c.save();
            c.translate(L.metaX, L.metaBaseline);
            if (L.slant != 0) c.skew(-L.slant * 0.7f, 0);
            c.drawText(st.meta, 0, 0, pMeta2);
            c.restore();
        }

        if (L.logoOn) drawLogo(c, st.brand, L.logoX, L.logoY, L.logoH, st.nameMaterial, L.darkText);

        // logo 与参数之间的分隔竖线
        if (L.sepX > 0) {
            Paint sep = new Paint(Paint.ANTI_ALIAS_FLAG);
            sep.setColor(textColor(L, "frost".equals(st.nameMaterial) ? 0x8A : 0xD2));
            sep.setStrokeWidth(Math.max(1.2f, u * 1.7f));
            sep.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(L.sepX, L.sepTop, L.sepX, L.sepBottom, sep);
        }
    }

    /* ── 机型名的三种材质 ── */

    /** 液态玻璃：亮边 + 渐变白 + 上方高光，近似网页版的折射字 */
    /** 配好字体/字号/字距的画笔（玻璃文字材质共用） */
    private static Paint glyphPaint(float size, String font, String text, float tracking) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(typeface(font, true, text));
        p.setTextSize(size);
        p.setLetterSpacing(tracking);
        return p;
    }

    /** 把颜色往白（t>0）或黑（t<0）混，返回不含 alpha 的 RGB */
    private static int shade(int rgb, float t) {
        float r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        if (t >= 0) {
            r += (255 - r) * t;
            g += (255 - g) * t;
            b += (255 - b) * t;
        } else {
            r *= 1 + t;
            g *= 1 + t;
            b *= 1 + t;
        }
        return (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
    }

    /**
     * 液态玻璃文字：多层叠加而不是一味加白。
     * 落影 → 上亮下暗的主体（保留色相）→ 下移的描边层（不同透明度，做出厚度）
     * → 顶部细高光 → 下缘冷色折射。
     * 原来的高光/冷边各 0.03 倍字号的粗描边，小字号时糊成一圈白边，很脏。
     */
    private static void drawLiquidText(Canvas c, String text, float x, float baseline,
                                       float size, float slant, float u, String font, int rgb) {
        c.save();
        c.translate(x, baseline);
        if (slant != 0) c.skew(-slant, 0);

        Paint sh = glyphPaint(size, font, text, TRACK_NAME);
        sh.setColor(0x38000000);
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1.2f, size * 0.09f),
                BlurMaskFilter.Blur.NORMAL));
        c.drawText(text, 0, size * 0.035f, sh);

        Paint body = glyphPaint(size, font, text, TRACK_NAME);
        body.setShader(new LinearGradient(0, -size * 0.74f, 0, -size * 0.02f,
                new int[]{0xFF000000 | shade(rgb, 0.55f),
                        0xFF000000 | shade(rgb, 0.10f),
                        0xFF000000 | shade(rgb, -0.18f)},
                new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
        c.drawText(text, 0, 0, body);

        Paint layer = glyphPaint(size, font, text, TRACK_NAME);
        layer.setStyle(Paint.Style.STROKE);
        layer.setStrokeWidth(Math.max(0.8f, size * 0.016f));
        layer.setColor((0x4D << 24) | shade(rgb, 0.25f));
        c.drawText(text, 0, size * 0.012f, layer);

        Paint hi = glyphPaint(size, font, text, TRACK_NAME);
        hi.setStyle(Paint.Style.STROKE);
        hi.setStrokeWidth(Math.max(0.7f, size * 0.012f));
        hi.setColor(0x8CFFFFFF);
        c.drawText(text, 0, -size * 0.004f, hi);

        Paint lo = glyphPaint(size, font, text, TRACK_NAME);
        lo.setStyle(Paint.Style.STROKE);
        lo.setStrokeWidth(Math.max(0.7f, size * 0.010f));
        lo.setColor(0x3D9EC8FF);
        c.drawText(text, 0, size * 0.018f, lo);

        c.restore();
    }

    /** 磨砂玻璃：半透明 + 模糊描边，像磨过的字 */
    /**
     * 毛玻璃文字：一层柔光 + 半透明本体 + 一道细边。
     * 原来柔光画了两遍、本体又加模糊，边缘散得太开，小字号看着像一团脏白。
     */
    private static void drawFrostText(Canvas c, String text, float x, float baseline,
                                      float size, float slant, float u, String font, int rgb) {
        c.save();
        c.translate(x, baseline);
        if (slant != 0) c.skew(-slant, 0);

        Paint halo = glyphPaint(size, font, text, TRACK_NAME);
        halo.setColor(0x33FFFFFF);
        halo.setMaskFilter(new BlurMaskFilter(Math.max(1.8f, size * 0.12f),
                BlurMaskFilter.Blur.NORMAL));
        c.drawText(text, 0, 0, halo);

        Paint body = glyphPaint(size, font, text, TRACK_NAME);
        body.setColor((0xCC << 24) | shade(rgb, 0.18f));
        c.drawText(text, 0, 0, body);

        Paint edge = glyphPaint(size, font, text, TRACK_NAME);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(0.7f, size * 0.011f));
        edge.setColor(0x59FFFFFF);
        c.drawText(text, 0, 0, edge);

        c.restore();
    }

    /** 内置字体（res/font，全部 SIL OFL 1.1），由 MainActivity 启动时注入 */
    private static Typeface[] bundled;   // sans / condensed / serif / mono

    static void initFonts(Typeface sans, Typeface condensed, Typeface serif, Typeface mono) {
        bundled = new Typeface[]{sans, condensed, serif, mono};
    }

    private static int fontSlot(String font) {
        if ("serif".equals(font)) return 2;
        if ("mono".equals(font)) return 3;
        if ("condensed".equals(font)) return 1;
        return 0;
    }

    /** 文本含中日韩字符时退回系统字体（内置字体只有拉丁字形） */
    private static boolean hasCjk(String t) {
        if (t == null) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF) return true;
            if (c >= 0xAC00 && c <= 0xD7AF) return true;
            if (c >= 0xF900 && c <= 0xFAFF) return true;
        }
        return false;
    }

    private static String sysFamily(String font) {
        if ("serif".equals(font)) return "serif";
        if ("mono".equals(font)) return "monospace";
        if ("condensed".equals(font)) return "sans-serif-condensed";
        return "sans-serif";
    }

    static Typeface typeface(String font, boolean bold, String text) {
        if (hasCjk(text) || bundled == null) {
            return Typeface.create(sysFamily(font), bold ? Typeface.BOLD : Typeface.NORMAL);
        }
        Typeface base = bundled[fontSlot(font)];
        if (base == null) return Typeface.create(sysFamily(font), bold ? Typeface.BOLD : Typeface.NORMAL);
        return Typeface.create(base, bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static Paint stylePaint(float size, float tracking, String font, boolean bold, String text) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(typeface(font, bold, text));
        p.setTextSize(size);
        p.setLetterSpacing(tracking);
        p.setColor(0xFFFFFFFF);
        return p;
    }

    /** 本地品牌图标（可选）。为空或 1×1 时回退到内置矢量绘制 */
    private static Bitmap[] logoBmps;
    private static float[] logoAspect = {1f, 1f, 1f, 1f, 1f, 1f};

    static void initLogos(Bitmap[] bmps) {
        logoBmps = bmps;
        for (int i = 0; i < 6 && i < bmps.length; i++) {
            Bitmap b = bmps[i];
            if (b != null && b.getWidth() > 1 && b.getHeight() > 0) {
                Bitmap m = buildMask(b);
                if (m != null) logoBmps[i] = m;
                // 蔡司（索引 3）：只要 ZEISS 字样，底部的弧线（官方标里代表镜片）在
                // 小尺寸下会糊成一条粗白条，像张笑脸。裁掉它，再按内容裁边 + 手绘描边。
                if (i == 3) logoBmps[i] = dropBottom(logoBmps[i], 0.62f);
                Bitmap t = trimAlpha(logoBmps[i]);
                if (t != null) logoBmps[i] = t;
                logoAspect[i] = logoBmps[i].getWidth() / (float) logoBmps[i].getHeight();
            }
        }
    }

    /**
     * 很多品牌图标是「白底黑字」或「蓝底白字」的实心图，没有透明通道。
     * 直接按 alpha 染白会得到一整块白方块。这里改成：以四角的主色当背景，
     * 用「与背景色的距离」当不透明度——文字留下、底色变透明。
     */
    private static Bitmap buildMask(Bitmap src) {
        return buildMask(src, false);
    }

    /**
     * @param keepBg true 时把背景色保留成一层淡白（而不是完全抠掉）。
     *               蔡司的标是「蓝底方块 + 白字」，底色就是标志的一部分，
     *               抠干净只剩一条宽扁的字+弧线，反而不像官方。
     */
    private static Bitmap buildMask(Bitmap src, boolean keepBg) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 3 || h < 3) return null;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int transparent = 0;
        for (int c : px) if ((c >>> 24) < 200) transparent++;

        if (transparent > px.length * 0.1) {
            // 有透明通道，但内容是一整块实心（比如徕卡的红色圆章 + 白色手写体）：
            // 只按 alpha 染白会得到一个白饼，字就看不见了。
            // 用亮度调制 alpha——深色底变淡、白色字保持不透明。
            double opaqueRatio = 1.0 - transparent / (double) px.length;
            if (opaqueRatio < 0.6) return null;
            // 还要区分「实心底 + 浅色字」（徕卡红章）和「一整块纯色图形」（苹果剪影）：
            // 前者亮度反差大，后者几乎没反差。没反差的保持原 alpha。
            double sum = 0, sum2 = 0;
            int n = 0;
            for (int c : px) {
                if ((c >>> 24) < 200) continue;
                double l = (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF)
                        + 0.114 * (c & 0xFF)) / 255.0;
                sum += l;
                sum2 += l * l;
                n++;
            }
            if (n == 0) return null;
            double mean = sum / n;
            double std = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
            if (std < 0.12) return null;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] dst = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                double lum = (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF)
                        + 0.114 * (c & 0xFF)) / 255.0;
                double k = 0.35 + 0.65 * Math.min(1.0, Math.max(0.0, (lum - 0.5) / 0.35));
                int alpha = (int) Math.round(((c >>> 24) & 0xFF) * k);
                dst[i] = (alpha << 24) | 0x00FFFFFF;
            }
            out.setPixels(dst, 0, w, 0, 0, w, h);
            return out;
        }

        int[] corner = {px[0], px[w - 1], px[(h - 1) * w], px[w * h - 1]};
        int bg = corner[0];
        int best = 0;
        for (int c : corner) {
            int n = 0;
            for (int d : corner) if (near(c, d)) n++;
            if (n > best) { best = n; bg = c; }
        }

        int br = (bg >> 16) & 0xFF, bgc = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] dst = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int dr = ((c >> 16) & 0xFF) - br;
            int dg = ((c >> 8) & 0xFF) - bgc;
            int db = (c & 0xFF) - bb;
            double d = Math.sqrt(dr * dr + dg * dg + db * db) / 441.0;
            double a = (d - 0.06) / (0.35 - 0.06);
            a = a < 0 ? 0 : (a > 1 ? 1 : a);
            if (keepBg) a = a < 0.45 ? 0.20 : 1.0;   // 底色留 20%，形成方块
            int alpha = (int) Math.round(a * 255);
            dst[i] = (alpha << 24) | 0x00FFFFFF;
        }
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static boolean near(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db) < 60;
    }

    /**
     * 量一下水印区域有多亮，用来决定文字用白还是深色。
     * 只采样 panel 内部的一小片网格，够用且很快。
     *
     * @param bg 还没画水印的背景图
     */
    static float measureLum(Bitmap bg, Layout L) {
        if (bg == null) return 0f;
        int x0 = Math.max(0, (int) L.panel.x);
        int y0 = Math.max(0, (int) L.panel.y);
        int x1 = Math.min(bg.getWidth(), (int) (L.panel.x + L.panel.w));
        int y1 = Math.min(bg.getHeight(), (int) (L.panel.y + L.panel.h));
        if (x1 <= x0 || y1 <= y0) return 0f;
        long sum = 0;
        int n = 0;
        for (int gy = 0; gy < 4; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                int px = x0 + (x1 - x0) * (2 * gx + 1) / 16;
                int py = y0 + (y1 - y0) * (2 * gy + 1) / 8;
                int c = bg.getPixel(px, py);
                // Rec.709 亮度
                sum += (long) (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF));
                n++;
            }
        }
        return n == 0 ? 0f : (float) (sum / (double) n) / 255f;
    }

    /** 文字颜色：深色模式下用近黑，白色模式保持纯白，alpha 由调用方给 */
    private static int textColor(Layout L, int alpha) {
        int a = (alpha & 0xFF) << 24;
        // 亮背景上要压暗，但保留色相（直接给纯黑会让彩色字变灰）
        if (L.darkText) {
            int c = L.textRgb;
            int r = Math.round(((c >> 16) & 0xFF) * 0.16f);
            int g = Math.round(((c >> 8) & 0xFF) * 0.16f);
            int b = Math.round((c & 0xFF) * 0.16f);
            return a | (r << 16) | (g << 8) | b;
        }
        return a | L.textRgb;
    }

    /** 把图像下部 ratio 之后的内容清成透明（蔡司底部的弧线不属于字样） */
    private static Bitmap dropBottom(Bitmap src, float ratio) {
        int w = src.getWidth(), h = src.getHeight();
        int cut = Math.max(1, Math.min(h, Math.round(h * ratio)));
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return src;
        int[] row = new int[w];
        for (int y = cut; y < h; y++) {
            java.util.Arrays.fill(row, 0);
            out.setPixels(row, 0, w, 0, y, w, 1);
        }
        if (out != src) src.recycle();
        return out;
    }

    /** 裁掉四周全透明的空白：各品牌图标的留白比例差别很大，不裁的话大小完全不统一 */
    private static Bitmap trimAlpha(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 8) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < x0 || y1 < y0) return null;
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return null;
        try {
            return Bitmap.createBitmap(src, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自定义图标（品牌 7），由用户从相册选，存 App 私有目录 */
    private static Bitmap customLogo;
    private static float customAspect = 1f;

    static void setCustomLogo(Bitmap b) {
        if (b == null) {
            customLogo = null;
            return;
        }
        Bitmap m = buildMask(b);
        if (m == null) m = b;
        Bitmap t = trimAlpha(m);
        if (t != null) m = t;
        customLogo = m;
        customAspect = m.getWidth() / (float) Math.max(1, m.getHeight());
    }

    static boolean hasCustomLogo() {
        return customLogo != null;
    }

    private static Bitmap logoBitmap(int brand) {
        if (brand == 7) return customLogo;
        if (logoBmps == null) return null;
        int i = brand - 1;
        if (i < 0 || i >= logoBmps.length) return null;
        Bitmap b = logoBmps[i];
        return (b != null && b.getWidth() > 1) ? b : null;
    }

    /**
     * Logo 允许的最大宽度（相对高度的倍数）。
     * 哈苏是 10:1 的横排字标，按 2.2 倍上限压下去高度只剩 0.22 倍字号，小得看不清；
     * 单独放宽到 7 倍，字标高度就能落在 1 倍字号附近。
     */
    private static float logoMaxW(int brand, float h) {
        return h * (brand == 2 ? 5.5f : 2.2f);
    }

    private static float logoWidth(int brand, float h) {
        Bitmap b = logoBitmap(brand);
        if (b != null) {
            float asp = brand == 7 ? customAspect : logoAspect[brand - 1];
            return Math.min(logoMaxW(brand, h), h * asp);
        }
        return vectorLogoWidth(brand, h);
    }

    /**
     * 用位图的 alpha 当蒙版、统一染成白色，并跟随「文字材质」：
     * 原图里的品牌色（徕卡红、蔡司蓝）直接贴到照片上会花，所以只取形状。
     */
    private static void drawLogoBitmap(Canvas c, Bitmap b, float x, float y,
                                       float w, float hh, String material, boolean dark) {
        RectF dst = new RectF(x, y, x + w, y + hh);
        float u = Math.max(0.6f, hh / 26f);   // 相对图标高度的一个小单位

        // 亮背景上图标也得跟着反色，否则白色字标直接消失
        if (dark) {
            if ("frost".equals(material)) {
                Paint glow = tint(0x33FFFFFF);
                for (float s = 1.10f; s > 1.001f; s -= 0.035f) {
                    float dw = w * (s - 1) / 2f, dh = hh * (s - 1) / 2f;
                    c.drawBitmap(b, null,
                            new RectF(dst.left - dw, dst.top - dh, dst.right + dw, dst.bottom + dh), glow);
                }
                c.drawBitmap(b, null, dst, tint(0xC2151515));
                return;
            }
            if ("liquid".equals(material)) {
                c.drawBitmap(b, null, offset(dst, 0, u * 1.1f), tint(0x33101010));
                c.drawBitmap(b, null, dst, tint(0xF2151515));
                c.drawBitmap(b, null, offset(dst, 0, -u * 1.2f), tint(0x59FFFFFF));
                return;
            }
            c.drawBitmap(b, null, offset(dst, 0, u * 0.9f), tint(0x40FFFFFF));
            c.drawBitmap(b, null, dst, tint(0xFF1A1A1A));
            return;
        }

        if ("frost".equals(material)) {
            // 磨砂：半透明 + 用几层放大的低透明副本模拟柔光
            Paint glow = tint(0x33FFFFFF);
            for (float s = 1.10f; s > 1.001f; s -= 0.035f) {
                float dw = w * (s - 1) / 2f, dh = hh * (s - 1) / 2f;
                c.drawBitmap(b, null,
                        new RectF(dst.left - dw, dst.top - dh, dst.right + dw, dst.bottom + dh), glow);
            }
            c.drawBitmap(b, null, dst, tint(0xA6FFFFFF));
            return;
        }

        if ("liquid".equals(material)) {
            // 液态玻璃：下缘冷色 + 主体渐变白 + 上缘硬高光
            c.drawBitmap(b, null, offset(dst, 0, u * 1.1f), tint(0x6690C8FF));
            c.drawBitmap(b, null, dst, tint(0xF2FFFFFF));
            c.drawBitmap(b, null, offset(dst, 0, -u * 1.2f), tint(0xD9FFFFFF));
            return;
        }

        c.drawBitmap(b, null, offset(dst, 0, u * 0.9f), tint(0x55000000));
        c.drawBitmap(b, null, dst, tint(0xFFFFFFFF));
    }

    /** 蔡司标志的外框：比内容略大一圈的圆角矩形描边 */
    private static void drawLogoFrame(Canvas c, float x, float y, float w, float hh,
                                      String material, boolean dark) {
        float pad = hh * 0.24f;
        RectF r = new RectF(x - pad, y - pad, x + w + pad, y + hh + pad);
        float rad = Math.min(r.width(), r.height()) * 0.03f;
        float stroke = Math.max(0.8f, hh * 0.034f);
        int color;
        if (dark) {
            color = "frost".equals(material) ? 0xB3151515
                    : ("liquid".equals(material) ? 0xD9151515 : 0xE61A1A1A);
        } else {
            color = "frost".equals(material) ? 0x9EFFFFFF
                    : ("liquid".equals(material) ? 0xE6FFFFFF : 0xFFFFFFFF);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        c.drawRoundRect(r, rad, rad, p);
    }

    private static RectF offset(RectF r, float dx, float dy) {
        return new RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy);
    }

    /** 只保留位图 alpha，颜色换成指定色 */
    private static Paint tint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.PorterDuffColorFilter(color,
                android.graphics.PorterDuff.Mode.SRC_IN));
        return p;
    }

    private static float vectorLogoWidth(int brand, float h) {
        switch (brand) {
            case 1: return h * 1.02f;   // 徕卡
            case 2: return h * 0.80f;   // 哈苏（H 形）
            case 3: return h * 0.80f;   // 哈苏（H 形，独立选项）
            case 4: return h;           // 蔡司
            case 5: return h * 1.66f;   // 理光
            case 6: return h * 0.86f;   // 苹果
            case 7: return h;           // 自定义
            default: return 0;
        }
    }

    private static void drawLogo(Canvas c, int brand, float x, float y, float h,
                                 String material, boolean dark) {
        Bitmap b = logoBitmap(brand);
        if (b != null) {
            float asp = brand == 7 ? customAspect : logoAspect[brand - 1];
            float w = Math.min(logoMaxW(brand, h), h * asp);
            float hh = w / asp;
            float top = y + (h - hh) / 2f;
            drawLogoBitmap(c, b, x, top, w, hh, material, dark);
            // 蔡司：只留 ZEISS 字样会丢掉标志性的方框，补一圈描边
            if (brand == 4) drawLogoFrame(c, x, top, w, hh, material, dark);
            return;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (brand) {
            case 1: {
                p.setColor(0xFFD6001C);
                c.drawCircle(x + h / 2f, y + h / 2f, h / 2f, p);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                t.setTextSize(h * 0.38f);
                t.setColor(0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText("Leica", x + h / 2f, y + h * 0.63f, t);
                break;
            }
            case 2:
            case 3: {
                p.setColor(0xFFFFFFFF);
                float bw = h * 0.155f;
                float w = h * 0.80f;
                c.save();
                c.translate(x, y);
                c.skew(-0.13f, 0);
                c.drawRect(0, 0, bw, h, p);
                c.drawRect(w - bw, 0, w, h, p);
                Path path = new Path();
                path.moveTo(-bw * 0.05f, h * 0.585f);
                path.lineTo(w + bw * 0.05f, h * 0.435f);
                path.lineTo(w + bw * 0.05f, h * 0.59f);
                path.lineTo(-bw * 0.05f, h * 0.74f);
                path.close();
                c.drawPath(path, p);
                c.restore();
                break;
            }
            case 4: {
                p.setColor(0xFF0B4EA2);
                c.drawRoundRect(new RectF(x, y, x + h, y + h), h * 0.13f, h * 0.13f, p);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                t.setTextSize(h * 0.225f);
                t.setColor(0xFFFFFFFF);
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText("ZEISS", x + h / 2f, y + h * 0.63f, t);
                break;
            }
            case 5: {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                t.setTextSize(h * 0.44f);
                t.setLetterSpacing(0.08f);
                t.setColor(0xFFFFFFFF);
                c.drawText("RICOH", x, y + h * 0.80f, t);
                break;
            }
            case 6: {
                // 苹果：两瓣果身 + 上方一片叶子
                float w = h * 0.86f;
                Path a = new Path();
                a.moveTo(x + w * 0.50f, y + h * 0.32f);
                a.cubicTo(x + w * 0.42f, y + h * 0.22f, x + w * 0.28f, y + h * 0.18f, x + w * 0.18f, y + h * 0.26f);
                a.cubicTo(x + w * 0.06f, y + h * 0.36f, x + w * 0.05f, y + h * 0.55f, x + w * 0.12f, y + h * 0.70f);
                a.cubicTo(x + w * 0.19f, y + h * 0.85f, x + w * 0.31f, y + h * 0.95f, x + w * 0.42f, y + h * 0.93f);
                a.cubicTo(x + w * 0.47f, y + h * 0.92f, x + w * 0.53f, y + h * 0.92f, x + w * 0.58f, y + h * 0.93f);
                a.cubicTo(x + w * 0.69f, y + h * 0.95f, x + w * 0.81f, y + h * 0.85f, x + w * 0.88f, y + h * 0.70f);
                a.cubicTo(x + w * 0.95f, y + h * 0.55f, x + w * 0.94f, y + h * 0.36f, x + w * 0.82f, y + h * 0.26f);
                a.cubicTo(x + w * 0.72f, y + h * 0.18f, x + w * 0.58f, y + h * 0.22f, x + w * 0.50f, y + h * 0.32f);
                a.close();
                p.setColor(0xFFFFFFFF);
                c.drawPath(a, p);

                Path leaf = new Path();
                leaf.moveTo(x + w * 0.52f, y + h * 0.26f);
                leaf.cubicTo(x + w * 0.54f, y + h * 0.16f, x + w * 0.62f, y + h * 0.08f, x + w * 0.72f, y + h * 0.06f);
                leaf.cubicTo(x + w * 0.74f, y + h * 0.16f, x + w * 0.68f, y + h * 0.25f, x + w * 0.58f, y + h * 0.28f);
                leaf.close();
                c.drawPath(leaf, p);
                break;
            }
            default:
                break;
        }
    }
}
