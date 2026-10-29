package com.vitrelens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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

    /** 和 onDraw 里同一套变换，用来同步 TextureView 的画面 */
    void applyTo(android.graphics.Matrix m, int viewW, int viewH) {
        if (style == null || viewW == 0 || viewH == 0) return;
        float scale = Math.min(viewW / (float) videoW, viewH / (float) videoH);
        float dw = videoW * scale, dh = videoH * scale;
        float zoom = closeUp ? 2.6f : 1f;
        m.setScale(zoom, zoom, viewW / 2f, viewH / 2f);
        if (closeUp) m.postTranslate(-(pan - 0.5f) * dw * 1.2f * zoom, -dh * 0.34f * zoom);
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

            // 玻璃本体的近似：半透明白 + 一点自上而下的明暗
            glassPaint.setStyle(Paint.Style.FILL);
            glassPaint.setColor(0x40FFFFFF);
            c.drawRoundRect(panelRect, r, r, glassPaint);

            // 边缘细线，让水印边界看得出来
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(Math.max(1f, L.u * 1.2f));
            rimPaint.setColor(0x66FFFFFF);
            c.drawRoundRect(panelRect, r, r, rimPaint);

            // 文字层走和导出完全相同的绘制逻辑
            WatermarkPainter.draw(c, L, st);
        }
        c.restore();
    }
}
