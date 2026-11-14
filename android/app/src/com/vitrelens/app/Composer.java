package com.vitrelens.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * 把水印合成到一张位图上。照片保留原路径；视频显式使用快速模糊，
 * 并缓存整段视频中不变的 Layout 与文字/Logo 覆盖层。
 */
class Composer {

    private final GlassRenderer glass;

    private WatermarkPainter.Style videoStyle;
    private WatermarkPainter.Layout videoLayout;
    private int videoW, videoH;

    private WatermarkPainter.Style overlayStyle;
    private WatermarkPainter.Layout overlayLayout;
    private Bitmap overlay;
    private int overlayX, overlayY, overlayKey = -1;

    /** 复用条带裁剪缓冲：视频每帧都会裁一条带，别每帧 allocate 十几 MB */
    private Bitmap cropBuf;
    private Canvas cropCanvas;

    Composer(GlassRenderer glass) {
        this.glass = glass;
    }

    boolean hasGlass() {
        return glass != null;
    }

    String backendLabel() {
        return glass == null ? "无 GL 渲染" : glass.backendLabel();
    }

    String backendDetail() {
        return glass == null ? "none" : glass.backendDetail();
    }

    /** base 必须可变；结果为 base 本身 */
    Bitmap composeOn(Bitmap base, int W, int H, WatermarkPainter.Style st) {
        return composeOn(base, W, H, st, 0f, 0f, 0f);
    }

    /** jsx/jsy 是拖动形变（-1~1），只有预览会传非 0 */
    Bitmap composeOn(Bitmap base, int W, int H, WatermarkPainter.Style st, float jsx, float jsy) {
        return composeOn(base, W, H, st, jsx, jsy, 0f);
    }

    Bitmap composeOn(Bitmap base, int W, int H, WatermarkPainter.Style st,
                     float jsx, float jsy, float jp) {
        return composeInternal(base, W, H, st, jsx, jsy, jp, false);
    }

    /** 视频禁止按宽度误入逐帧 CPU 高斯，并复用静态布局/文字层。 */
    Bitmap composeVideoOn(Bitmap base, int W, int H, WatermarkPainter.Style st) {
        return composeInternal(base, W, H, st, 0f, 0f, 0f, true);
    }

    private Bitmap composeInternal(Bitmap base, int W, int H, WatermarkPainter.Style st,
                                   float jsx, float jsy, float jp, boolean videoFrame) {
        Canvas c = new Canvas(base);
        WatermarkPainter.Layout L;
        if (videoFrame && videoStyle == st && videoW == W && videoH == H && videoLayout != null) {
            L = videoLayout;
        } else {
            L = WatermarkPainter.layout(W, H, st, jsx, jsy, jp);
            if (videoFrame) {
                videoStyle = st;
                videoW = W;
                videoH = H;
                videoLayout = L;
            }
        }
        WatermarkPainter.Panel panel = L.panel;

        // 亮度要在画玻璃之前量：玻璃本身会改变底色。
        if (st.autoText) {
            float lum = WatermarkPainter.measureLum(base, L);
            L.darkText = st.textHue < 0 && lum > 0.62f;
            L.darkLogo = lum > 0.62f;
            st.themeDark = lum < 0.48f;
        }

        if (glass != null && panel.w > 4 && panel.h > 2) {
            float u = W / 1000f;
            int sx = Math.max(0, Math.round(panel.x - 70 * u));
            int sy = Math.max(0, Math.round(panel.y - 90 * u));
            int sw = (int) Math.min(W - sx, Math.round(panel.w + 140 * u));
            int sh = (int) Math.min(H - sy, Math.round(panel.h + 180 * u));

            int pad = Math.min(Math.round(140 * u), Math.max(8, sw / 4));
            int stripMax = Math.max(1024, glass.maxTexture());
            int cols = (int) Math.ceil(sw / (double) stripMax);
            for (int i = 0; i < cols; i++) {
                int ox = sx + i * stripMax;
                int ow = Math.min(stripMax, sw - i * stripMax);
                if (ow < 4) break;

                int padL = (i > 0) ? Math.min(pad, Math.max(0, ox - sx)) : 0;
                int padR = (i < cols - 1) ? Math.min(pad, Math.max(0, sx + sw - (ox + ow))) : 0;
                final int m = 1;
                int innerX = ox - padL;
                int innerW = ow + padL + padR;
                int cx = Math.max(0, innerX - m);
                int cy = Math.max(0, sy - m);
                int cw = Math.min(W - cx, innerW + 2 * m);
                int ch = Math.min(H - cy, sh + 2 * m);
                if (innerW <= 0 || sh <= 0 || cw <= 2 || ch <= 2) continue;

                Bitmap crop = ensureCropBuf(cw, ch);
                if (crop == null) continue;
                cropCanvas.drawBitmap(base, new Rect(cx, cy, cx + cw, cy + ch),
                        new Rect(0, 0, cw, ch), null);
                Bitmap g;
                WatermarkPainter.Panel p = new WatermarkPainter.Panel();
                p.x = panel.x - cx;
                p.y = panel.y - cy;
                p.w = panel.w;
                p.h = panel.h;
                p.r = panel.r;
                p.bevel = panel.bevel;
                p.edgeW = panel.edgeW;
                // 照片仍按原规则使用高斯；视频始终使用 GPU 友好的快路径。
                boolean hq = !videoFrame && W >= 1600;
                // renderShared 返回内部复用位图：画完即可，不能 recycle
                g = glass.renderShared(crop, cx, cy, W, H, p, st, hq);
                int srcX = innerX - cx;
                int srcY = sy - cy;
                c.drawBitmap(g,
                        new Rect(srcX, srcY, srcX + innerW, srcY + sh),
                        new Rect(innerX, sy, innerX + innerW, sy + sh), null);
            }
        }

        if (videoFrame) drawVideoOverlay(c, L, st, W, H);
        else WatermarkPainter.draw(c, L, st);
        return base;
    }

    /** 只在样式或明暗状态变化时重绘文字/Logo，背景玻璃仍逐帧渲染。 */
    private void drawVideoOverlay(Canvas c, WatermarkPainter.Layout L,
                                  WatermarkPainter.Style st, int W, int H) {
        int key = (L.darkText ? 1 : 0) | (L.darkLogo ? 2 : 0) | (st.themeDark ? 4 : 0);
        if (overlay == null || overlayStyle != st || overlayLayout != L || overlayKey != key) {
            float u = W / 1000f;
            WatermarkPainter.Panel p = L.panel;
            int x = Math.max(0, Math.round(p.x - 40 * u));
            int y = Math.max(0, Math.round(p.y - 50 * u));
            int w = Math.max(1, (int) Math.min(W - x, Math.round(p.w + 80 * u)));
            int h = Math.max(1, (int) Math.min(H - y, Math.round(p.h + 100 * u)));
            Bitmap next = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas oc = new Canvas(next);
            oc.translate(-x, -y);
            WatermarkPainter.draw(oc, L, st);
            if (overlay != null) overlay.recycle();
            overlay = next;
            overlayX = x;
            overlayY = y;
            overlayStyle = st;
            overlayLayout = L;
            overlayKey = key;
        }
        c.drawBitmap(overlay, overlayX, overlayY, null);
    }

    /** 取一张正好 cw×ch 的复用裁剪位图（尺寸变化才重新分配；条带尺寸逐帧稳定）。 */
    private Bitmap ensureCropBuf(int w, int h) {
        if (cropBuf == null || cropBuf.isRecycled()
                || cropBuf.getWidth() != w || cropBuf.getHeight() != h) {
            if (cropBuf != null) cropBuf.recycle();
            try {
                cropBuf = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                cropBuf = null;
                return null;
            }
            cropCanvas = new Canvas(cropBuf);
        }
        return cropBuf;
    }

    /** Activity 销毁时释放复用的缓冲与覆盖层 */
    void release() {
        if (cropBuf != null) {
            cropBuf.recycle();
            cropBuf = null;
            cropCanvas = null;
        }
        if (overlay != null) {
            overlay.recycle();
            overlay = null;
        }
    }
}
