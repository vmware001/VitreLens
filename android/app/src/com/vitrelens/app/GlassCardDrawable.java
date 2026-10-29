package com.vitrelens.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 液态玻璃卡片背景。
 *
 * 参考 Kyant0/AndroidLiquidGlass 的视觉语言（Apache-2.0），但用原生 View 的
 * Drawable 重写，不引入 Compose 依赖。四层叠加：
 *
 *   1. 半透明底 + 轻微纵向渐变（上亮下暗，模拟玻璃厚度）
 *   2. 顶部高光：一条从白到透明的窄渐变，边缘处最亮
 *   3. 色散描边：红色与青色各偏移 1px 描一遍，形成棱镜边缘
 *   4. 内侧下缘暗影：玻璃底部聚集的暗部
 */
class GlassCardDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF inner = new RectF();

    private final float radius;
    private final int baseFill;

    GlassCardDrawable(float radiusDp, int baseFillColor) {
        this.radius = radiusDp;
        this.baseFill = baseFillColor;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.2f);
        gloss.setStyle(Paint.Style.STROKE);
        gloss.setStrokeWidth(1.6f);
    }

    @Override
    public void draw(Canvas c) {
        r.set(getBounds());
        if (r.width() <= 0 || r.height() <= 0) return;
        float rad = Math.min(radius, Math.min(r.width(), r.height()) / 2f);

        // 1. 底：上亮下暗，模拟玻璃的厚度渐变
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                new int[]{0x26FFFFFF, 0x14FFFFFF, 0x0DFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setShader(null);

        // 4. 内侧下缘暗影（先画，再叠高光）
        inner.set(r.left + 1, r.top + 1, r.right - 1, r.bottom - 1);
        fill.setShader(new LinearGradient(0, r.bottom - r.height() * 0.35f, 0, r.bottom,
                new int[]{0x00000000, 0x1A000000}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(inner, Math.max(0, rad - 1), Math.max(0, rad - 1), fill);
        fill.setShader(null);

        // 2. 顶部高光
        gloss.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(r.height() * 0.5f, 64f),
                new int[]{0x7AFFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(inner, Math.max(0, rad - 1), Math.max(0, rad - 1), gloss);
        gloss.setShader(null);

        // 3. 色散描边：红 / 青 各偏 1px，模拟棱镜边缘
        float d = 1.1f;
        stroke.setColor(0x2EFF7A6B);
        c.save();
        c.translate(-d, 0);
        c.drawRoundRect(inner, rad, rad, stroke);
        c.restore();
        stroke.setColor(0x2E6BD4FF);
        c.save();
        c.translate(d, 0);
        c.drawRoundRect(inner, rad, rad, stroke);
        c.restore();

        // 常规描边
        stroke.setColor(baseFill);
        c.drawRoundRect(inner, rad, rad, stroke);
    }

    /** 供 View.setClipToOutline 使用：滚动区用圆角裁掉溢出的内容 */
    @Override
    public void getOutline(android.graphics.Outline o) {
        android.graphics.Rect b = getBounds();
        float rad = Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        o.setRoundRect(b, rad);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
