import java.util.Locale;

/**
 * 生成色彩管线基准数据。
 *
 * 这里的公式是 Android `YuvConverter` 的**逐行拷贝**（只去掉 android.* 依赖），
 * 目的不是重写一遍，而是让 Swift 移植版有一个可验证的对照源：
 * 输出数组直接贴进 Swift 测试，任何一位不同都会失败。
 *
 * 用法：
 *   javac ColorReference.java && java ColorReference
 */
public final class ColorReference {

    private static final int XFER_LINEAR = 1;
    private static final int XFER_SDR = 3;
    private static final int XFER_ST2084 = 6;
    private static final int XFER_HLG = 7;

    private static final int STD_BT709 = 1;
    private static final int STD_BT601_PAL = 2;
    private static final int STD_BT601_NTSC = 4;
    private static final int STD_BT2020 = 6;

    private static final int RANGE_FULL = 2;

    /* ───── 以下与 YuvConverter 完全一致 ───── */

    private static byte[] buildToneMapLut(int transfer) {
        final double hlgA = 0.17883277, hlgB = 0.28466892, hlgC = 0.55991073;
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

    private static int[] buildSrgbToLinear() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            double v = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
            t[i] = (int) Math.round(v * 4096.0);
        }
        return t;
    }

    private static byte[] buildLinearToSrgb() {
        byte[] t = new byte[4097];
        for (int i = 0; i < t.length; i++) {
            double v = i / 4096.0;
            double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            int n = (int) Math.round(s * 255.0);
            t[i] = (byte) Math.max(0, Math.min(255, n));
        }
        return t;
    }

    private static final int[] SRGB_TO_LINEAR = buildSrgbToLinear();
    private static final byte[] LINEAR_TO_SRGB = buildLinearToSrgb();

    private static int gamutPack(int r, int g, int b) {
        int rl = SRGB_TO_LINEAR[r], gl = SRGB_TO_LINEAR[g], bl = SRGB_TO_LINEAR[b];
        int nr = (6801 * rl - 2407 * gl - 298 * bl + 2048) >> 12;
        int ng = (-510 * rl + 4640 * gl - 34 * bl + 2048) >> 12;
        int nb = (-75 * rl - 412 * gl + 4581 * bl + 2048) >> 12;
        nr = Math.max(0, Math.min(4096, nr));
        ng = Math.max(0, Math.min(4096, ng));
        nb = Math.max(0, Math.min(4096, nb));
        return ((LINEAR_TO_SRGB[nr] & 0xFF) << 16)
                | ((LINEAR_TO_SRGB[ng] & 0xFF) << 8) | (LINEAR_TO_SRGB[nb] & 0xFF);
    }

    /** 与 YuvConverter 构造函数的系数表一致 */
    private static int[] coeffs(int std, boolean full) {
        int yScale = full ? 1024 : 1192;
        int yOffset = full ? 0 : 16;
        int rCr, gCb, gCr, bCb;
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
        return new int[]{yScale, yOffset, rCr, gCb, gCr, bCb};
    }

    private static int pixel(int std, int transfer, boolean full, boolean keepHdr,
                             byte[] lut, int Y, int U, int V) {
        int[] c = coeffs(std, full);
        int yScale = c[0], yOffset = c[1], rCr = c[2], gCb = c[3], gCr = c[4], bCb = c[5];
        int cc = Y - yOffset;
        int d = U - 128;
        int e = V - 128;
        int r = (yScale * cc + rCr * e + 512) >> 10;
        int g = (yScale * cc - gCb * d - gCr * e + 512) >> 10;
        int b = (yScale * cc + bCb * d + 512) >> 10;
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;
        if (lut != null) {
            r = lut[r] & 0xFF;
            g = lut[g] & 0xFF;
            b = lut[b] & 0xFF;
        }
        boolean gamut = std == STD_BT2020 && !keepHdr;
        return gamut ? gamutPack(r, g, b) : (r << 16) | (g << 8) | b;
    }

    private static String hex(byte[] t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (i > 0 && i % 32 == 0) sb.append("\n");
            sb.append(String.format(Locale.ROOT, "0x%02X, ", t[i] & 0xFF));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        byte[] hlg = buildToneMapLut(XFER_HLG);
        byte[] pq = buildToneMapLut(XFER_ST2084);
        System.out.println("=== HLG LUT ===");
        System.out.println(hex(hlg));
        System.out.println("=== PQ LUT ===");
        System.out.println(hex(pq));

        System.out.println("=== 像素 ===");
        int[][] samples = {
                {0, 0, 255}, {128, 128, 128}, {255, 128, 128},
                {235, 128, 128}, {16, 128, 128}, {100, 60, 200}, {180, 90, 40}
        };
        String[] combos = {
                "709/full/sdr", "709/limited/sdr", "601PAL/full/sdr", "601PAL/limited/sdr",
                "601NTSC/full/sdr", "2020/full/sdr", "2020/limited/sdr",
                "2020/full/hlg", "2020/full/pq", "2020/limited/hlg"
        };
        for (String combo : combos) {
            String[] p = combo.split("/");
            int std = p[0].equals("2020") ? STD_BT2020
                    : p[0].equals("601PAL") ? STD_BT601_PAL
                    : p[0].equals("601NTSC") ? STD_BT601_NTSC : STD_BT709;
            boolean full = p[1].equals("full");
            int xfer = p[2].equals("hlg") ? XFER_HLG : p[2].equals("pq") ? XFER_ST2084 : XFER_SDR;
            byte[] lut = (xfer == XFER_HLG) ? hlg : (xfer == XFER_ST2084) ? pq : null;
            StringBuilder line = new StringBuilder("        (\"" + combo + "\", [");
            for (int[] s : samples) {
                int rgb = pixel(std, xfer, full, false, lut, s[0], s[1], s[2]);
                line.append(String.format(Locale.ROOT, "0x%06X, ", rgb));
            }
            line.append("]),");
            System.out.println(line);
        }
    }
}
