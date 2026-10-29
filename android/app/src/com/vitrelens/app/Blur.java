package com.vitrelens.app;

/**
 * 可分离盒式模糊。3 次迭代已经非常接近高斯，且滑窗实现是 O(1)/像素。
 *
 * <p>两处用得到：导出时给玻璃生成真高斯背景（{@link GlassRenderer}），
 * 以及主界面的整屏背景图（{@code MainActivity.makeBackdrop}）。
 * 之前两处各写一份，现在统一到这里。
 */
final class Blur {

    private Blur() {
    }

    /** 3 次可分离盒式 ≈ 高斯。宽度 w0 = 2r+1 时 sigma ≈ w0/2。 */
    static void boxBlur(int[] px, int w, int h, int r) {
        if (r < 1 || w < 2 || h < 2) return;
        int[] line = new int[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            blurH(px, line, w, h, r);
            blurV(px, line, w, h, r);
        }
    }

    private static void blurH(int[] px, int[] line, int w, int h, int r) {
        final int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            final int base = y * w;
            int a = 0, cr = 0, cg = 0, cb = 0;
            for (int i = -r; i <= r; i++) {
                int c = px[base + clamp(i, w)];
                a += c >>> 24; cr += (c >> 16) & 0xFF; cg += (c >> 8) & 0xFF; cb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                line[x] = ((a / div) << 24) | ((cr / div) << 16) | ((cg / div) << 8) | (cb / div);
                int out = px[base + clamp(x - r, w)];
                int in = px[base + clamp(x + r + 1, w)];
                a += (in >>> 24) - (out >>> 24);
                cr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                cg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                cb += (in & 0xFF) - (out & 0xFF);
            }
            System.arraycopy(line, 0, px, base, w);
        }
    }

    private static void blurV(int[] px, int[] line, int w, int h, int r) {
        final int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, cr = 0, cg = 0, cb = 0;
            for (int i = -r; i <= r; i++) {
                int c = px[clamp(i, h) * w + x];
                a += c >>> 24; cr += (c >> 16) & 0xFF; cg += (c >> 8) & 0xFF; cb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                line[y] = ((a / div) << 24) | ((cr / div) << 16) | ((cg / div) << 8) | (cb / div);
                int out = px[clamp(y - r, h) * w + x];
                int in = px[clamp(y + r + 1, h) * w + x];
                a += (in >>> 24) - (out >>> 24);
                cr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                cg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                cb += (in & 0xFF) - (out & 0xFF);
            }
            for (int y = 0; y < h; y++) px[y * w + x] = line[y];
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }
}
