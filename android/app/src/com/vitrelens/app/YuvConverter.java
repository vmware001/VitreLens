package com.vitrelens.app;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * YUV_420_888 → ARGB，按源视频的真实色彩特性转换。
 *
 * 这里要同时处理两件事，缺一个画面就不对：
 *
 *   1. **矩阵**：BT.601 / BT.709 / BT.2020 的 YCbCr→RGB 系数完全不同。
 *      一直按 BT.601 硬解，遇到 BT.2020 的源就会整体偏色（OPPO 的动态照片正是 BT.2020）。
 *   2. **传输函数**：HLG（transfer=18）和 PQ 是 HDR 的编码曲线，参考白在 75% / 58% 信号处，
 *      直接当 SDR 显示会让整个画面发灰、对比度塌掉。必须解码到线性光、按参考白归一、
 *      再重新编码成 sRGB，才得到正确的 SDR 观感。
 *
 * 矩阵用 1024 定点整数，传输函数用 256 项查找表——每帧上千万像素，不能走浮点。
 */
final class YuvConverter {

    /** 解码后的逐像素转换是 CPU 热点；按行并行，GL 合成仍留在原单线程队列。 */
    private static final java.util.concurrent.ExecutorService CPU_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), r -> {
                        Thread t = new Thread(r, "vl-yuv");
                        t.setDaemon(true);
                        return t;
                    });

    // MediaFormat.COLOR_STANDARD_* 的取值
    private static final int STD_BT709 = 1;
    private static final int STD_BT601_PAL = 2;
    private static final int STD_BT601_NTSC = 4;
    private static final int STD_BT2020 = 6;

    // MediaFormat.COLOR_TRANSFER_*
    private static final int XFER_LINEAR = 1;
    private static final int XFER_SDR = 3;
    private static final int XFER_ST2084 = 6;   // PQ
    private static final int XFER_HLG = 7;

    // MediaFormat.COLOR_RANGE_*
    private static final int RANGE_FULL = 1;

    private final int yScale;   // 亮度缩放：limited 1192 / full 1024
    private final int yOffset;  // 亮度黑电平：limited 16 / full 0
    private final int rCr, gCb, gCr, bCb;
    private final byte[] lut;   // null 表示源本身就是 SDR，无需转换

    /** 给人看的描述，写进日志 */
    final String label;
    /** 源是不是 HDR（HLG / PQ），导出提示要用 */
    final boolean hdrSource;

    YuvConverter(int colorStandard, int colorTransfer, int colorRange) {
        this(colorStandard, colorTransfer, colorRange, false);
    }

    /**
     * @param keepHdrSignal true 时不做色调映射，直接输出 HLG / PQ 信号本身。
     *        用于输出 HDR 视频：像素里存的就是 HLG 编码值，配合 HLG 标签交给播放器还原。
     */
    YuvConverter(int colorStandard, int colorTransfer, int colorRange, boolean keepHdrSignal) {
        boolean full = colorRange == RANGE_FULL;
        int std = colorStandard;

        // 未标注时按 BT.709 处理：现在绝大多数是 HD 以上素材，
        // 早期按 BT.601 兜底会在 BT.709 的源上产生可见偏色。
        if (std != STD_BT601_PAL && std != STD_BT601_NTSC && std != STD_BT2020) std = STD_BT709;

        yScale = full ? 1024 : 1192;
        yOffset = full ? 0 : 16;

        if (std == STD_BT2020) {
            if (full) { rCr = 1510; gCb = 169; gCr = 585; bCb = 1926; }
            else { rCr = 1719; gCb = 192; gCr = 666; bCb = 2193; }
        } else if (std == STD_BT601_PAL || std == STD_BT601_NTSC) {
            if (full) { rCr = 1436; gCb = 352; gCr = 731; bCb = 1815; }
            else { rCr = 1634; gCb = 401; gCr = 832; bCb = 2065; }
        } else {
            if (full) { rCr = 1613; gCb = 192; gCr = 479; bCb = 1900; }
            else { rCr = 1836; gCb = 218; gCr = 546; bCb = 2163; }
        }

        hdrSource = colorTransfer == XFER_HLG || colorTransfer == XFER_ST2084;
        // lut == null 表示「YUV 转 RGB 之后原样输出」：SDR 源本来就是直通，
        // HDR 源在 keepHdrSignal 时也走这条，从而保住 HDR 信号
        lut = (hdrSource && !keepHdrSignal) ? buildToneMapLut(colorTransfer) : null;

        String stdName = std == STD_BT2020 ? "BT.2020"
                : (std == STD_BT601_PAL || std == STD_BT601_NTSC) ? "BT.601" : "BT.709";
        String xferName = colorTransfer == XFER_HLG ? "HLG"
                : colorTransfer == XFER_ST2084 ? "PQ"
                : colorTransfer == XFER_LINEAR ? "linear" : "SDR";
        label = stdName + " / " + xferName + " / " + (full ? "full" : "limited");
    }

    /**
     * 把 HDR 的传输曲线映射到 sRGB。
     *
     * HLG 用 ARIB STD-B67 的反 OETF，参考白取 75% 信号；PQ 用 SMPTE ST 2084，
     * 参考白取 203nit。归一之后做一次软拐点压缩，避免高光直接切平，
     * 最后用 sRGB 曲线重新编码。
     */
    private static byte[] buildToneMapLut(int transfer) {
        final double hlgA = 0.17883277, hlgB = 0.28466892, hlgC = 0.55991073;
        // 白点。HLG 的参考白在 75% 信号（203nit），但直接把它映射到 SDR 的 1.0 会让
        // 整幅画面偏亮约 10 个百分点、高光挤成一片白 —— 实测与标准的 HLG→sRGB 转换对齐后，
        // 白点落在 82% 信号处。低于这个值画面发灰，高于它整体发暗。
        final double hlgWhite = (Math.exp((0.82 - hlgC) / hlgA) + hlgB) / 12.0;
        final double pqM1 = 0.1593017578125, pqM2 = 78.84375;
        final double pqC1 = 0.8359375, pqC2 = 18.8515625, pqC3 = 18.6875;

        byte[] t = new byte[256];
        for (int i = 0; i < 256; i++) {
            double e = i / 255.0;
            double lin;
            if (transfer == XFER_HLG) {
                lin = (e <= 0.5) ? (e * e / 3.0)
                        : ((Math.exp((e - hlgC) / hlgA) + hlgB) / 12.0);
                lin /= hlgWhite;
            } else {
                double p = Math.pow(e, 1.0 / pqM2);
                double den = pqC2 - pqC3 * p;
                double num = Math.max(p - pqC1, 0.0);
                double nits = (den > 1e-9) ? Math.pow(num / den, 1.0 / pqM1) * 10000.0 : 10000.0;
                lin = nits / 203.0;
            }
            if (lin < 0) lin = 0;
            // 软拐点：0.9 以上才收敛，且更平缓。
            // 起点定在 0.8 会把本该还有层次的天空、白墙一并推到纯白。
            if (lin > 0.9) lin = 0.9 + 0.1 * (1.0 - Math.exp(-(lin - 0.9) / 0.35));
            if (lin > 1) lin = 1;

            double s = (lin <= 0.0031308) ? 12.92 * lin
                    : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
            int v = (int) Math.round(s * 255.0);
            if (v < 0) v = 0; else if (v > 255) v = 255;
            t[i] = (byte) v;
        }
        return t;
    }

    boolean toBitmap(Image img, Bitmap bmp, int[] scratch, int W, int H) {
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length < 3) return false;
        if (img.getWidth() != W || img.getHeight() != H) return false;

        ByteBuffer yB = planes[0].getBuffer();
        ByteBuffer uB = planes[1].getBuffer();
        ByteBuffer vB = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        int workers = W * H >= 1000000 ? Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors())) : 1;
        if (workers == 1) {
            convertRows(yB, uB, vB, yRow, yPix, uRow, uPix, vRow, vPix,
                    scratch, W, H, 0, H);
        } else {
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(workers);
            final java.util.concurrent.atomic.AtomicBoolean ok =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            for (int i = 0; i < workers; i++) {
                final int from = H * i / workers;
                final int to = H * (i + 1) / workers;
                // duplicate() 共享内容但各自拥有 position，避免多线程争抢缓冲区游标。
                final ByteBuffer fy = yB.duplicate(), fu = uB.duplicate(), fv = vB.duplicate();
                CPU_POOL.execute(() -> {
                    try {
                        convertRows(fy, fu, fv, yRow, yPix, uRow, uPix, vRow, vPix,
                                scratch, W, H, from, to);
                    } catch (Throwable t) {
                        ok.set(false);
                    } finally {
                        done.countDown();
                    }
                });
            }
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!ok.get()) return false;
        }
        bmp.setPixels(scratch, 0, W, 0, 0, W, H);
        return true;
    }

    /** 每个工作线程只写自己负责的行，读取共享 YUV，写回互不重叠的 ARGB 区域。 */
    private void convertRows(ByteBuffer yB, ByteBuffer uB, ByteBuffer vB,
                             int yRow, int yPix, int uRow, int uPix, int vRow, int vPix,
                             int[] scratch, int W, int H, int fromY, int toY) {
        int cw = (W + 1) / 2;
        int yLen = W * yPix;
        int cLen = cw * Math.max(uPix, vPix);
        byte[] yLine = new byte[yLen];
        byte[] uLine = new byte[cLen];
        byte[] vLine = new byte[cLen];
        final byte[] lut = this.lut;

        for (int y = fromY; y < toY; y++) {
            yB.position(y * yRow);
            int n = Math.min(yLen, yB.remaining());
            yB.get(yLine, 0, n);
            if ((y & 1) == 0) {
                int cy = (y >> 1) * uRow;
                uB.position(cy);
                int un = Math.min(cLen, uB.remaining());
                uB.get(uLine, 0, un);
                vB.position(cy);
                int vn = Math.min(cLen, vB.remaining());
                vB.get(vLine, 0, vn);
            }
            int base = y * W;
            for (int x = 0; x < W; x++) {
                int Y = yLine[x * yPix] & 0xFF;
                int ci = (x >> 1) * uPix;
                int U = uLine[ci] & 0xFF;
                int V = vLine[(x >> 1) * vPix] & 0xFF;
                int c = Y - yOffset;
                int d = U - 128;
                int e = V - 128;
                int r = (yScale * c + rCr * e + 512) >> 10;
                int g = (yScale * c - gCb * d - gCr * e + 512) >> 10;
                int b = (yScale * c + bCb * d + 512) >> 10;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                if (lut != null) {
                    r = lut[r] & 0xFF;
                    g = lut[g] & 0xFF;
                    b = lut[b] & 0xFF;
                }
                scratch[base + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /**
     * 解码后的直接降采样：不创建完整源尺寸 ARGB。
     * Y 用双线性，U/V 用四点平均；颜色矩阵与 HDR LUT 和原路径完全一致。
     */
    boolean toBitmapScaled(Image img, Bitmap bmp, int[] scratch,
                           int srcW, int srcH, int dstW, int dstH) {
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length < 3) return false;
        if (img.getWidth() != srcW || img.getHeight() != srcH) return false;
        if (dstW <= 0 || dstH <= 0) return false;

        ByteBuffer yB = planes[0].getBuffer(), uB = planes[1].getBuffer(), vB = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        int[] x0 = new int[dstW], x1 = new int[dstW], fx = new int[dstW];
        for (int x = 0; x < dstW; x++) {
            float cx = (x + 0.5f) * srcW / dstW - 0.5f;
            int a = (int) Math.floor(cx);
            float f = cx - a;
            x0[x] = Math.max(0, Math.min(srcW - 1, a));
            x1[x] = Math.max(0, Math.min(srcW - 1, a + 1));
            fx[x] = Math.max(0, Math.min(1024, Math.round(f * 1024)));
        }

        int workers = dstW * dstH >= 500000 ? Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors())) : 1;
        if (workers == 1) {
            convertRowsScaled(yB, uB, vB, yRow, yPix, uRow, uPix, vRow, vPix,
                    scratch, srcW, srcH, dstW, dstH, 0, dstH, x0, x1, fx);
        } else {
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(workers);
            final java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean(true);
            for (int i = 0; i < workers; i++) {
                final int from = dstH * i / workers, to = dstH * (i + 1) / workers;
                final ByteBuffer fy = yB.duplicate(), fu = uB.duplicate(), fv = vB.duplicate();
                CPU_POOL.execute(() -> {
                    try {
                        convertRowsScaled(fy, fu, fv, yRow, yPix, uRow, uPix, vRow, vPix,
                                scratch, srcW, srcH, dstW, dstH, from, to, x0, x1, fx);
                    } catch (Throwable t) {
                        ok.set(false);
                    } finally {
                        done.countDown();
                    }
                });
            }
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!ok.get()) return false;
        }
        bmp.setPixels(scratch, 0, dstW, 0, 0, dstW, dstH);
        return true;
    }

    private static void readRow(ByteBuffer b, int stride, int row, byte[] line, int len) {
        b.position(row * stride);
        int n = Math.min(len, b.remaining());
        b.get(line, 0, n);
    }

    private void convertRowsScaled(ByteBuffer yB, ByteBuffer uB, ByteBuffer vB,
                                   int yRow, int yPix, int uRow, int uPix, int vRow, int vPix,
                                   int[] scratch, int srcW, int srcH, int dstW, int dstH,
                                   int fromY, int toY, int[] x0, int[] x1, int[] fx) {
        int cw = (srcW + 1) / 2;
        int yLen = srcW * yPix, cLen = cw * Math.max(uPix, vPix);
        byte[] y0 = new byte[yLen], y1 = new byte[yLen];
        byte[] u0 = new byte[cLen], u1 = new byte[cLen];
        byte[] v0 = new byte[cLen], v1 = new byte[cLen];
        final byte[] lut = this.lut;

        for (int y = fromY; y < toY; y++) {
            float cy = (y + 0.5f) * srcH / dstH - 0.5f;
            int ay = (int) Math.floor(cy);
            float fyf = cy - ay;
            int sy0 = Math.max(0, Math.min(srcH - 1, ay));
            int sy1 = Math.max(0, Math.min(srcH - 1, ay + 1));
            int fy = Math.max(0, Math.min(1024, Math.round(fyf * 1024)));
            readRow(yB, yRow, sy0, y0, yLen);
            readRow(yB, yRow, sy1, y1, yLen);
            readRow(uB, uRow, sy0 >> 1, u0, cLen);
            readRow(uB, uRow, sy1 >> 1, u1, cLen);
            readRow(vB, vRow, sy0 >> 1, v0, cLen);
            readRow(vB, vRow, sy1 >> 1, v1, cLen);

            int base = y * dstW;
            for (int x = 0; x < dstW; x++) {
                int xa = x0[x] * yPix, xb = x1[x] * yPix, f = fx[x];
                int y00 = y0[xa] & 0xFF, y01 = y0[xb] & 0xFF;
                int y10 = y1[xa] & 0xFF, y11 = y1[xb] & 0xFF;
                int yt = (y00 * (1024 - f) + y01 * f + 512) >> 10;
                int yb = (y10 * (1024 - f) + y11 * f + 512) >> 10;
                int Y = (yt * (1024 - fy) + yb * fy + 512) >> 10;

                int ca = (x0[x] >> 1) * uPix, cb = (x1[x] >> 1) * uPix;
                int va = (x0[x] >> 1) * vPix, vb = (x1[x] >> 1) * vPix;
                int U = ((u0[ca] & 0xFF) + (u0[cb] & 0xFF)
                        + (u1[ca] & 0xFF) + (u1[cb] & 0xFF) + 2) >> 2;
                int V = ((v0[va] & 0xFF) + (v0[vb] & 0xFF)
                        + (v1[va] & 0xFF) + (v1[vb] & 0xFF) + 2) >> 2;

                int c = Y - yOffset, d = U - 128, e = V - 128;
                int r = (yScale * c + rCr * e + 512) >> 10;
                int g = (yScale * c - gCb * d - gCr * e + 512) >> 10;
                int b = (yScale * c + bCb * d + 512) >> 10;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                if (lut != null) {
                    r = lut[r] & 0xFF;
                    g = lut[g] & 0xFF;
                    b = lut[b] & 0xFF;
                }
                scratch[base + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }
}
