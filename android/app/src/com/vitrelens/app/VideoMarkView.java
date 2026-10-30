package com.vitrelens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 实况照片「动态部分」的水印覆盖层。
 *
 * 真正的玻璃折射要在 GL 里做（{@link GlassRenderer}），逐帧跑代价太高，
 * 预览时用一层半透明胶囊代替玻璃本体，文字仍然走 {@link WatermarkPainter#draw}，
 * 所以位置、字号、版式和导出结果一致，只是少了折射和色散。
 */
public class VideoMarkView extends View {

    private WatermarkPainter.Style style;
    private int videoW = 1, videoH = 1;
    private boolean closeUp;
    private float pan = 0.5f;

    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();

    public VideoMarkView(Context c) {
        super(c);
    }

    public VideoMarkView(Context c, AttributeSet a) {
        super(c, a);
    }

    public VideoMarkView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
    }

    void setStyle(WatermarkPainter.Style s) {
        this.style = s;
        invalidate();
    }

    void setVideoSize(int w, int h) {
        if (w > 0 && h > 0) {
            videoW = w;
            videoH = h;
            invalidate();
        }
    }

    void setCloseUp(boolean on, float pan) {
        this.closeUp = on;
        this.pan = pan;
        invalidate();
    }

    /**
     * 和 onDraw 里同一套变换，用来同步 TextureView 的画面。
     *
     * <p>TextureView 默认把视频缓冲**拉伸铺满**整个 View，所以必须自己补一个等比缩放，
     * 否则竖拍视频会被压成 View 的形状（看起来就像被拉成 1:1）。
     * 补法是先算 fitCenter 需要的额外缩放 (s/sx, s/sy)，再叠特写的 zoom/pan。
     */
    void applyTo(android.graphics.Matrix m, int viewW, int viewH) {
        if (viewW == 0 || viewH == 0 || videoW <= 0 || videoH <= 0) return;
        float sx = viewW / (float) videoW;
        float sy = viewH / (float) videoH;
        float s = Math.min(sx, sy);
        float dw = videoW * s, dh = videoH * s;
        float dx = (viewW - dw) / 2f, dy = (viewH - dh) / 2f;
        float zoom = closeUp ? 2.6f : 1f;

        m.reset();
        m.setScale(s / sx, s / sy);
        m.postTranslate(dx, dy);
        m.postTranslate(-viewW / 2f, -viewH / 2f);
        if (closeUp) m.postTranslate(-(pan - 0.5f) * dw * 1.2f, -dh * 0.34f);
        m.postScale(zoom, zoom);
        m.postTranslate(viewW / 2f, viewH / 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (style == null || getWidth() == 0 || getHeight() == 0) return;

        // 视频按 fitCenter 铺在 View 里，水印坐标系要跟着这个矩形走
        float vw = getWidth(), vh = getHeight();
        float scale = Math.min(vw / videoW, vh / videoH);
        float dw = videoW * scale, dh = videoH * scale;
        float dx = (vw - dw) / 2f, dy = (vh - dh) / 2f;

        // 特写：放大 2.6 倍，按 pan 平移
        float zoom = closeUp ? 2.6f : 1f;
        c.save();
        c.translate(vw / 2f, vh / 2f);
        c.scale(zoom, zoom);
        // 特写聚焦到底部（水印就在那里），否则放大后水印直接跑出视野
        if (closeUp) c.translate(-(pan - 0.5f) * dw * 1.2f, -dh * 0.34f);
        c.translate(-vw / 2f, -vh / 2f);
        c.translate(dx, dy);
        c.scale(scale, scale);

        WatermarkPainter.Style st = style.copy();
        // 视频取景比照片窄，和导出时用同一套换算
        st.gapPct = Math.max(0.2f, style.gapPct + style.videoGapShift);
        st.widthPct = Math.min(99f, style.widthPct * style.videoWidthScale / 100f);

        WatermarkPainter.Layout L = WatermarkPainter.layout(videoW, videoH, st);
        if (L.hasContent) {
            panelRect.set(L.panel.x, L.panel.y, L.panel.x + L.panel.w, L.panel.y + L.panel.h);
            float r = Math.min(L.panel.r, panelRect.height() / 2f);
            float u = L.u;

            // 近似玻璃：底色由当前预设的透射率 / 不透明度推出来，
            // 再补上顶部高光、边缘色散和投影 —— 之前是一个固定的 0x40FFFFFF，
            // 所以不管选什么预设，预览都是同一块白，和导出对不上。
            float trans = Math.min(1f, Math.max(0f, st.transPct / 100f));
            float opaque = Math.min(1f, Math.max(0f, st.whitePct / 100f));
            float solid = Math.min(1f, (1f - trans) * 2.2f + opaque * 0.55f);
            int baseA = Math.round(Math.min(0.72f, 0.10f + solid * 0.55f) * 255);

            // 投影
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(0x55000000);
            shadowPaint.setMaskFilter(new BlurMaskFilter(Math.max(2f, u * 9f),
                    BlurMaskFilter.Blur.NORMAL));
            c.save();
            c.translate(0, u * 3.5f);
            c.drawRoundRect(panelRect, r, r, shadowPaint);
            c.restore();
            shadowPaint.setMaskFilter(null);

            // 主体：上亮下暗
            glassPaint.setStyle(Paint.Style.FILL);
            glassPaint.setShader(new LinearGradient(0, panelRect.top, 0, panelRect.bottom,
                    new int[]{(baseA << 24) | 0x00FFFFFF,
                            (Math.round(baseA * 0.72f) << 24) | 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP));
            c.drawRoundRect(panelRect, r, r, glassPaint);
            glassPaint.setShader(null);

            // 顶部高光
            hiPaint.setStyle(Paint.Style.FILL);
            hiPaint.setShader(new LinearGradient(0, panelRect.top, 0,
                    panelRect.top + panelRect.height() * 0.55f,
                    new int[]{0x5AFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(panelRect, r, r, hiPaint);
            hiPaint.setShader(null);

            // 边缘：红/青各偏半像素再叠一道白细线，模仿色散描边
            float lw = Math.max(1f, u * 1.4f);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(lw);
            c.save();
            c.translate(-lw * 0.5f, 0);
            rimPaint.setColor(0x2EFF7A6B);
            c.drawRoundRect(panelRect, r, r, rimPaint);
            c.restore();
            c.save();
            c.translate(lw * 0.5f, 0);
            rimPaint.setColor(0x2E6BD4FF);
            c.drawRoundRect(panelRect, r, r, rimPaint);
            c.restore();
            rimPaint.setColor(0x59FFFFFF);
            c.drawRoundRect(panelRect, r, r, rimPaint);

            // 文字层走和导出完全相同的绘制逻辑
            WatermarkPainter.draw(c, L, st);
        }
        c.restore();
    }
}
