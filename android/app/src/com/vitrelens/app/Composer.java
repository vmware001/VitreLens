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
                // 渲染块四周各多留 1 像素：GL 回读出来的第一列/第一行不可靠
                // （实测首列是近黑色），多留一圈、画回时只用内部区域就能绕开。
                final int m = 1;
                int innerX = ox - padL;
                int innerW = ow + padL + padR;
                int cx = Math.max(0, innerX - m);
                int cy = Math.max(0, sy - m);
                int cw = Math.min(W - cx, innerW + 2 * m);
                int ch = Math.min(H - cy, sh + 2 * m);
                if (innerW <= 0 || sh <= 0 || cw <= 2 || ch <= 2) continue;

                Bitmap crop = Bitmap.createBitmap(base, cx, cy, cw, ch);
                Bitmap g;
                try {
                    WatermarkPainter.Panel p = new WatermarkPainter.Panel();
                    p.x = panel.x - cx;
                    p.y = panel.y - cy;
                    p.w = panel.w;
                    p.h = panel.h;
                    p.r = panel.r;
                    p.bevel = panel.bevel;
                    p.edgeW = panel.edgeW;
                    g = glass.render(crop, cx, cy, W, H, p, st);
                } finally {
                    crop.recycle();
                }
                int srcX = innerX - cx;
                int srcY = sy - cy;
                c.drawBitmap(g,
                        new Rect(srcX, srcY, srcX + innerW, srcY + sh),
                        new Rect(innerX, sy, innerX + innerW, sy + sh), null);
                g.recycle();
            }
        }

        // 画玻璃之前量背景亮度：玻璃本身会改变底色，先量才准
        if (st.autoText) {
            L.darkText = WatermarkPainter.measureLum(base, L) > 0.62f;
        }
        WatermarkPainter.draw(c, L, st);
        return base;
    }
}
