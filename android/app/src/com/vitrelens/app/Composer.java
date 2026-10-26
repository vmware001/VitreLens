package com.vitrelens.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * 把水印合成到一张位图上（照片导出和视频逐帧合成共用同一份逻辑）。
 * 尺寸全部按画幅宽度等比缩放，所以 4096 宽的照片和 1728 宽的视频看起来完全一致。
 */
class Composer {

    private final GlassRenderer glass;

    Composer(GlassRenderer glass) {
        this.glass = glass;
    }

    boolean hasGlass() {
        return glass != null;
    }

    /** base 必须可变；结果为 base 本身 */
    Bitmap composeOn(Bitmap base, int W, int H, WatermarkPainter.Style st) {
        Canvas c = new Canvas(base);
        WatermarkPainter.Layout L = WatermarkPainter.layout(W, H, st);
        WatermarkPainter.Panel panel = L.panel;

        if (glass != null && panel.w > 4 && panel.h > 2) {
            float u = W / 1000f;
            // 留白要够：投影的高斯扩散约 20u，留 70u/90u 才能保证块边界处已经完全无变化
            int sx = Math.max(0, Math.round(panel.x - 70 * u));
            int sy = Math.max(0, Math.round(panel.y - 90 * u));
            int sw = (int) Math.min(W - sx, Math.round(panel.w + 140 * u));
            int sh = (int) Math.min(H - sy, Math.round(panel.h + 180 * u));

            // 超过纹理上限才分条渲染；每条左右各多带一段重叠，
            // 输出时只取中间部分，避免接缝把玻璃和投影切断
            int pad = Math.min(Math.round(140 * u), Math.max(8, sw / 4));
            int stripMax = Math.max(1024, glass.maxTexture());
            int cols = (int) Math.ceil(sw / (double) stripMax);
            for (int i = 0; i < cols; i++) {
                int ox = sx + i * stripMax;
                int ow = Math.min(stripMax, sw - i * stripMax);
                if (ow < 4) break;

                int padL = (i > 0) ? Math.min(pad, Math.max(0, ox - sx)) : 0;
                int padR = (i < cols - 1) ? Math.min(pad, Math.max(0, sx + sw - (ox + ow))) : 0;
                int cx = ox - padL;
                int cw = ow + padL + padR;
                if (cw <= 0 || cw > W || sh <= 0) continue;

                Bitmap crop = Bitmap.createBitmap(base, cx, sy, cw, sh);
                Bitmap g;
                try {
                    WatermarkPainter.Panel p = new WatermarkPainter.Panel();
                    p.x = panel.x - cx;
                    p.y = panel.y - sy;
                    p.w = panel.w;
                    p.h = panel.h;
                    p.r = panel.r;
                    p.bevel = panel.bevel;
                    p.edgeW = panel.edgeW;
                    g = glass.render(crop, cx, sy, W, H, p, st);
                } finally {
                    crop.recycle();
                }
                if (padL == 0 && padR == 0) {
                    c.drawBitmap(g, ox, sy, null);
                } else {
                    c.drawBitmap(g,
                            new Rect(padL, 0, padL + ow, sh),
                            new Rect(ox, sy, ox + ow, sy + sh), null);
                }
                g.recycle();
            }
        }

        WatermarkPainter.draw(c, L, st);
        return base;
    }
}
