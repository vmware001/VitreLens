package com.vitrelens.app;

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

        int brand = 2;                 // 0 无 1 徕卡 2 哈苏 3 蔡司 4 理光
        String align = "center";       // left / center / right
        String font = "condensed";     // sans / condensed / serif / mono
        String nameMaterial = "solid"; // liquid / frost / solid

        float widthPct = 92f;
        /** 玻璃高度（垂直长轴方向），100% = 刚好包住文字 */
        float glassHeightPct = 100f;
        /** 玻璃高度（垂直长轴方向），100% = 刚好包住文字 */
        float gapPct = 3.8f;
        float nameSize = 28f;          // 每 1000px 画幅
        float metaSize = 10f;
        boolean italic = true;

        // 玻璃（参数对齐 @specy/liquid-glass 的 GlassStyle）
        float ior = 1.55f;
        float radiusPct = 100f;
        float depthPct = 36f;      // 边缘收边宽度 = 玻璃深度
        float thicknessPct = 72f;  // 玻璃厚度
        float roughPct = 8f;       // 粗糙度
        float dispPct = 38f;       // 色散
        float reflPct = 90f;       // 反射率
        float transPct = 100f;     // 透射率
        float glossPct = 50f;      // 高光
        float shadowPct = 40f;     // 投影
        float whitePct = 0f;       // 加白
        boolean tintOn = true;     // 色调
        int segments = 64;         // 法线采样细分

        /** 视频水印高度微调（% 画幅宽度），正数=往上挪 */
        float videoGapShift = 5f;
        /** 视频水印宽度缩放（%），视频取景比照片窄，需要放大一点才和照片一致 */
        float videoWidthScale = 106f;

        Style copy() {
            Style s = new Style();
            s.name = name; s.subtitle = subtitle; s.params = params; s.meta = meta;
            s.showSubtitle = showSubtitle; s.showBrand = showBrand;
            s.showParams = showParams; s.showMeta = showMeta;
            s.brand = brand; s.align = align; s.font = font; s.nameMaterial = nameMaterial;
            s.widthPct = widthPct; s.gapPct = gapPct; s.glassHeightPct = glassHeightPct;
            s.nameSize = nameSize; s.metaSize = metaSize; s.italic = italic;
            s.ior = ior; s.radiusPct = radiusPct; s.depthPct = depthPct;
            s.thicknessPct = thicknessPct; s.roughPct = roughPct; s.dispPct = dispPct;
            s.reflPct = reflPct; s.transPct = transPct; s.glossPct = glossPct;
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
        boolean hasContent;
    }

    private static final float TRACK_NAME = 0.026f;
    private static final float TRACK_META = 0.02f;
    private static final float TRACK_SUB = 0.02f;

    static Layout layout(int W, int H, Style st) {
        Layout L = new Layout();
        if (st.name == null) st.name = "";
        if (st.subtitle == null) st.subtitle = "";
        if (st.params == null) st.params = "";
        if (st.meta == null) st.meta = "";
        L.u = W / 1000f;
        float u = L.u;
        L.padX = 32 * u;
        L.padY = 22 * u;
        L.slant = st.italic ? 0.115f : 0f;

        float scale = 1f;
        for (int attempt = 0; attempt < 3; attempt++) {
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

            float logoH = nameSize * 1.0f;
            float logoW = st.showBrand ? logoWidth(st.brand, logoH) : 0;

            float gapA = logoW > 0 ? nameSize * 0.55f : 0;
            float gapB = logoW > 0 ? nameSize * 0.55f : 0;
            float rightW = Math.max(paramsW, metaW);
            float rightH = (paramsOn ? lineH : 0) + (paramsOn && metaOn ? lineGap : 0) + (metaOn ? lineH : 0);
            float contentW = leftW + gapA + logoW + gapB + rightW;

            float margin = 38 * u;
            float panelW = Math.max(W * (st.widthPct / 100f), contentW + L.padX * 2);
            panelW = Math.min(panelW, W - margin * 2);
            float availW = panelW - L.padX * 2;

            if (contentW > availW + 0.5f && attempt < 2) {
                scale *= Math.max(0.55f, availW / contentW) * 0.985f;
                continue;
            }

            float contentH = Math.max(Math.max(leftH, rightH), logoW > 0 ? logoH : 0);
            float panelH = contentH * (st.glassHeightPct / 100f) + L.padY * 2;
            float panelX;
            if ("left".equals(st.align)) panelX = margin;
            else if ("right".equals(st.align)) panelX = W - margin - panelW;
            else panelX = (W - panelW) / 2f;

            float bottomGap = st.gapPct * W / 100f;
            float panelY = Math.max(0, H - bottomGap - panelH);

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

            if (paramsOn || metaOn) {
                float rightTop = cy - rightH / 2f;
                float y = rightTop;
                if (paramsOn) {
                    L.paramsBaseline = y - fmM.ascent;
                    L.paramsX = panelX + panelW - L.padX - paramsW;
                    y += lineH + lineGap;
                }
                if (metaOn) {
                    L.metaBaseline = y - fmM.ascent;
                    L.metaX = panelX + panelW - L.padX - metaW;
                }
            }

            if (L.logoOn) {
                float leftEnd = panelX + L.padX + leftW;
                float rightStart = panelX + panelW - L.padX - rightW;
                float space = rightStart - leftEnd;
                float gx = leftEnd + gapA;
                if (space > logoW + gapA + gapB * 0.4f) gx = leftEnd + (space - logoW) / 2f;
                L.logoX = gx;
                L.logoY = cy - logoH / 2f;
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
                drawLiquidText(c, st.name, L.nameX, L.nameBaseline, L.nameSize, L.slant, u, st.font);
            } else if ("frost".equals(st.nameMaterial)) {
                drawFrostText(c, st.name, L.nameX, L.nameBaseline, L.nameSize, L.slant, u, st.font);
            } else {
                p.setShadowLayer(Math.max(2f, u * 4.4f), 0, u * 1.0f, 0x66000000);
                c.save();
                c.translate(L.nameX, L.nameBaseline);
                if (L.slant != 0) c.skew(-L.slant, 0);
                c.drawText(st.name, 0, 0, p);
                c.restore();
            }
        }

        if (L.subOn) {
            Paint pSub = stylePaint(L.subSize, TRACK_SUB, st.font, false, st.subtitle);
            pSub.setColor(0xCCFFFFFF);
            pSub.setShadowLayer(Math.max(1f, u * 3f), 0, u * 0.8f, 0x66000000);
            c.save();
            float bx = L.subX + L.dotR;
            c.translate(bx, L.subBaseline - L.subSize * 0.34f);
            if (L.slant != 0) c.skew(-L.slant * 0.8f, 0);
            c.drawCircle(0, 0, L.dotR, pSub);
            c.drawText(st.subtitle, L.dotR + L.dotGap, L.subSize * 0.34f, pSub);
            c.restore();
        }

        Paint pRight = stylePaint(L.metaSize, TRACK_META, "sans", false, st.params);
        pRight.setShadowLayer(Math.max(1f, u * 3f), 0, u * 0.8f, 0x66000000);
        if (L.paramsOn) {
            c.save();
            c.translate(L.paramsX, L.paramsBaseline);
            if (L.slant != 0) c.skew(-L.slant * 0.7f, 0);
            c.drawText(st.params, 0, 0, pRight);
            c.restore();
        }
        if (L.metaOn) {
            Paint pMeta2 = stylePaint(L.metaSize, TRACK_META, "sans", false, st.meta);
            pMeta2.setColor(0xA6FFFFFF);
            pMeta2.setShadowLayer(Math.max(1f, u * 3f), 0, u * 0.8f, 0x55000000);
            c.save();
            c.translate(L.metaX, L.metaBaseline);
            if (L.slant != 0) c.skew(-L.slant * 0.7f, 0);
            c.drawText(st.meta, 0, 0, pMeta2);
            c.restore();
        }

        if (L.logoOn) drawLogo(c, st.brand, L.logoX, L.logoY, L.logoH);
    }

    /* ── 机型名的三种材质 ── */

    /** 液态玻璃：亮边 + 渐变白 + 上方高光，近似网页版的折射字 */
    private static void drawLiquidText(Canvas c, String text, float x, float baseline,
                                       float size, float slant, float u, String font) {
        c.save();
        c.translate(x, baseline);
        if (slant != 0) c.skew(-slant, 0);

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setTypeface(typeface(font, true, text));
        glow.setTextSize(size);
        glow.setLetterSpacing(TRACK_NAME);
        glow.setColor(0x40000000);
        glow.setMaskFilter(new BlurMaskFilter(Math.max(2f, size * 0.16f), BlurMaskFilter.Blur.NORMAL));
        c.drawText(text, 0, size * 0.02f, glow);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(typeface(font, true, text));
        p.setTextSize(size);
        p.setLetterSpacing(TRACK_NAME);
        p.setShader(new LinearGradient(0, -size * 0.78f, 0, -size * 0.05f,
                new int[]{0xFFFFFFFF, 0xF2FFFFFF, 0xD9F2FAFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawText(text, 0, 0, p);

        // 上缘一道硬高光
        Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        hi.setTypeface(typeface(font, true, text));
        hi.setTextSize(size);
        hi.setLetterSpacing(TRACK_NAME);
        hi.setStyle(Paint.Style.STROKE);
        hi.setStrokeWidth(Math.max(1f, size * 0.030f));
        hi.setColor(0xD9FFFFFF);
        c.drawText(text, 0, -size * 0.016f, hi);

        // 下缘一道淡蓝，模拟玻璃底部的冷色折射
        Paint lo = new Paint(Paint.ANTI_ALIAS_FLAG);
        lo.setTypeface(typeface(font, true, text));
        lo.setTextSize(size);
        lo.setLetterSpacing(TRACK_NAME);
        lo.setStyle(Paint.Style.STROKE);
        lo.setStrokeWidth(Math.max(1f, size * 0.026f));
        lo.setColor(0x6690C8FF);
        c.drawText(text, 0, size * 0.014f, lo);

        c.restore();
    }

    /** 磨砂玻璃：半透明 + 模糊描边，像磨过的字 */
    private static void drawFrostText(Canvas c, String text, float x, float baseline,
                                      float size, float slant, float u, String font) {
        c.save();
        c.translate(x, baseline);
        if (slant != 0) c.skew(-slant, 0);

        // 磨砂玻璃：明显更"糊"、更半透明，边缘散开
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setTypeface(typeface(font, true, text));
        halo.setTextSize(size);
        halo.setLetterSpacing(TRACK_NAME);
        halo.setColor(0x44FFFFFF);
        halo.setMaskFilter(new BlurMaskFilter(Math.max(3f, size * 0.22f), BlurMaskFilter.Blur.NORMAL));
        c.drawText(text, 0, 0, halo);
        c.drawText(text, 0, 0, halo);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(typeface(font, true, text));
        p.setTextSize(size);
        p.setLetterSpacing(TRACK_NAME);
        p.setColor(0xA6FFFFFF);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, size * 0.035f), BlurMaskFilter.Blur.NORMAL));
        c.drawText(text, 0, 0, p);

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

    private static float logoWidth(int brand, float h) {
        switch (brand) {
            case 1: return h * 1.02f;
            case 2: return h * 0.80f;
            case 3: return h;
            case 4: return h * 1.66f;
            default: return 0;
        }
    }

    private static void drawLogo(Canvas c, int brand, float x, float y, float h) {
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
            case 2: {
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
            case 3: {
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
            case 4: {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                t.setTextSize(h * 0.44f);
                t.setLetterSpacing(0.08f);
                t.setColor(0xFFFFFFFF);
                c.drawText("RICOH", x, y + h * 0.80f, t);
                break;
            }
            default:
                break;
        }
    }
}
