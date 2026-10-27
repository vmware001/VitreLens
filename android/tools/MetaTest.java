package com.vitrelens.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 JVM 测试：直接跑 Meta.java（该类不依赖 Android API），
 * 用 reference 里的真实 OPPO 样张验证「解析 → 重组 → 再解析」是否自洽。
 *
 * 编译运行见 android/run-tests.sh
 */
public class MetaTest {

    static int pass = 0, fail = 0;

    static void ok(String msg) { pass++; System.out.println("  [OK]   " + msg); }
    static void bad(String msg) { fail++; System.out.println("  [FAIL] " + msg); }
    static void chk(boolean c, String msg) { if (c) ok(msg); else bad(msg); }

    /** 模拟「重编码后的 JPEG 主体」；withIcc 用来覆盖编码器写/不写 ICC 两条分支 */
    static byte[] fakeReencoded(int payload, boolean withIcc) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        o.write(0xFF); o.write(0xE0); o.write(0x00); o.write(0x10);
        for (int i = 0; i < 14; i++) o.write(0x4A);
        if (withIcc) {
            byte[] iccName = "ICC_PROFILE\u0000".getBytes();
            int iccLen = iccName.length + 20;
            o.write(0xFF); o.write(0xE2);
            o.write(((iccLen + 2) >> 8) & 0xFF); o.write((iccLen + 2) & 0xFF);
            o.write(iccName, 0, iccName.length);
            for (int i = 0; i < 20; i++) o.write(0x33);
        }
        o.write(0xFF); o.write(0xDB); o.write(0x00); o.write(0x06);
        o.write(0x00); o.write(1); o.write(2); o.write(3);
        o.write(0xFF); o.write(0xDA); o.write(0x00); o.write(0x04); o.write(0x00); o.write(0x00);
        for (int i = 0; i < payload; i++) o.write((i * 7) & 0x7F);
        o.write(0xFF); o.write(0xD9);
        return o.toByteArray();
    }

    /** 段扫描：返回 {marker, absOffset, segTotalLen} */
    static List<int[]> scan(byte[] d) {
        List<int[]> out = new ArrayList<int[]>();
        int i = 2;
        while (i + 3 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) { out.add(new int[]{-1, i, 0}); return out; }
            int m = d[i + 1] & 0xFF;
            if (m == 0xD9) { out.add(new int[]{0xD9, i, 2}); return out; }
            if (m == 0xDA) { out.add(new int[]{0xDA, i, 0}); return out; }
            if (m >= 0xD0 && m <= 0xD8) { i += 2; continue; }
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            out.add(new int[]{m, i, 2 + len});
            i += 2 + len;
        }
        return out;
    }

    static String segHead(byte[] d, int off, int n) {
        int len = Math.min(n, d.length - off - 4);
        if (len <= 0) return "";
        try { return new String(d, off + 4, len, "ISO-8859-1"); } catch (Exception e) { return ""; }
    }

    static void testFile(File f, String label) throws Exception {
        System.out.println("\n=== " + label + " ===");
        System.out.println("  " + f.getName() + "  " + f.length() + " 字节");
        if (!f.isFile() || f.length() == 0) { bad("文件不存在"); return; }

        long size = f.length();
        Meta src;
        InputStream in = new FileInputStream(f);
        try { src = Meta.parse(in, size); } finally { in.close(); }

        System.out.println("  eoiEnd=" + src.eoiEnd + " trailer=" + src.trailerLength
                + " motion=" + src.motion + " hdr=" + src.hdr + " icc=" + src.hasIcc
                + " orient=" + src.orientation + " segs=" + src.segs.size()
                + " mpfEntries=" + src.mpfOffPos.length);
        System.out.println("  make=" + src.make + " | model=" + src.model
                + " | focal=" + src.focal + " focal35=" + src.focal35
                + " | f=" + src.fNumber + " | iso=" + src.iso
                + " | exp=" + src.exposure + " | date=" + src.date);

        chk(src.eoiEnd > 0, "定位到 EOI：" + src.eoiEnd);
        chk(src.eoiEnd <= size, "EOI 不超过文件长度");
        chk(src.trailerLength == size - src.eoiEnd, "尾部长度 = 文件长 - EOI");

        byte[] body = fakeReencoded(5000, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream orig = new FileInputStream(f);
        try { src.assemble(bos, body, orig, true, null, 0); } finally { orig.close(); }
        byte[] out = bos.toByteArray();
        System.out.println("  重组输出 " + out.length + " 字节（编码器自带 ICC）");

        // 1) 段结构合法
        List<int[]> segs = scan(out);
        StringBuilder sb = new StringBuilder();
        boolean sawSos = false, broken = false;
        int iccCount = 0, mpfAbs = -1;
        for (int[] s : segs) {
            if (s[0] < 0) { broken = true; break; }
            sb.append(String.format("%02X ", s[0]));
            if (s[0] == 0xDA) sawSos = true;
            if (s[0] == 0xE2) {
                String h = segHead(out, s[1], 11);
                if (h.startsWith("ICC_PROFILE")) iccCount++;
                if (h.startsWith("MPF")) mpfAbs = s[1];
            }
        }
        chk(!broken, "段结构完整：" + sb.toString().trim());
        chk(sawSos, "重编码主体接上了 SOS");

        // 2) ICC 不重复
        if (src.hasIcc) chk(iccCount == 1, "ICC 恰好 1 段（沿用编码器写的，原图那段已去掉）");
        else chk(iccCount == 0, "原图无 ICC，输出也没有");

        // 3) 用同一个解析器读回来
        Meta back;
        InputStream bi2 = new ByteArrayInputStream(out);
        try { back = Meta.parse(bi2, out.length); } finally { bi2.close(); }

        chk(back.motion == src.motion, "motion 保持 " + back.motion);
        chk(back.hdr == src.hdr, "hdr(gain map) 保持 " + back.hdr);
        chk(back.segs.size() == src.segs.size(), "元数据段数一致 " + back.segs.size());
        chk(back.model.equals(src.model), "机型保持 " + back.model);
        chk(back.eoiEnd == out.length - src.trailerLength,
                "新 EOI(" + back.eoiEnd + ") = 输出长 - 尾部长");

        // 4) MPF 必须指向新的尾部起点，且每个副图偏移落在 FF D8 上
        if (src.mpfOffPos.length > 1) {
            chk(mpfAbs >= 0, "输出里找到 MPF 段 @" + mpfAbs);
            if (mpfAbs >= 0) {
                int tiff = mpfAbs + 8;                  // 2 marker + 2 len + 4 "MPF\0"
                boolean le = out[tiff] == 'I' && out[tiff + 1] == 'I';
                int ifd = tiff + Meta.readInt(out, tiff + 4, le);
                int n = Meta.readShort(out, ifd, le);
                int arr = -1, count = 0;
                for (int k = 0; k < n; k++) {
                    int e = ifd + 2 + k * 12;
                    if (Meta.readShort(out, e, le) == 0xB002) {
                        count = Meta.readInt(out, e + 4, le);
                        arr = tiff + Meta.readInt(out, e + 8, le);
                        break;
                    }
                }
                chk(arr > 0 && count >= 32, "找到 Individual Image 数组，count=" + count);
                if (arr > 0) {
                    int imgs = count / 16;
                    long size0 = Meta.readInt(out, arr + 4, le) & 0xFFFFFFFFL;
                    chk(size0 == back.eoiEnd,
                            "主图 size(" + size0 + ") = 尾部起点(" + back.eoiEnd + ")");

                    long cursor = back.eoiEnd;
                    boolean allGood = true;
                    for (int j = 1; j < imgs; j++) {
                        long sz = Meta.readInt(out, arr + j * 16 + 4, le) & 0xFFFFFFFFL;
                        long off = Meta.readInt(out, arr + j * 16 + 8, le) & 0xFFFFFFFFL;
                        long abs = tiff + off;
                        boolean sig = abs + 2 < out.length
                                && (out[(int) abs] & 0xFF) == 0xFF && (out[(int) abs + 1] & 0xFF) == 0xD8;
                        boolean pos = abs == cursor;
                        System.out.println("    副图" + j + " size=" + sz + " off=" + off
                                + " -> abs=" + abs + " sig=" + sig + " posOk=" + pos);
                        if (!sig || !pos) allGood = false;
                        cursor += sz;
                    }
                    chk(allGood, "所有副图（增益图/视频封面）偏移都精确指向 FF D8 且首尾相接");
                }
            }
        }

        // 5) 尾部必须逐字节一致
        byte[] tailOut = new byte[(int) (out.length - back.eoiEnd)];
        System.arraycopy(out, (int) back.eoiEnd, tailOut, 0, tailOut.length);
        byte[] tailSrc = new byte[(int) src.trailerLength];
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        raf.seek(src.eoiEnd);
        raf.readFully(tailSrc);
        raf.close();
        chk(java.util.Arrays.equals(tailOut, tailSrc),
                "尾部 " + tailSrc.length + " 字节与原图逐字节一致");

        // 6) 编码器没写 ICC 时：必须把原图的 ICC 带过去（广色域不丢）
        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        InputStream o2 = new FileInputStream(f);
        try { src.assemble(b2, fakeReencoded(5000, false), o2, true, null, 0); } finally { o2.close(); }
        byte[] out2 = b2.toByteArray();
        int icc2 = 0;
        for (int[] s : scan(out2)) {
            if (s[0] == 0xE2 && segHead(out2, s[1], 11).startsWith("ICC_PROFILE")) icc2++;
        }
        if (src.hasIcc) chk(icc2 == 1, "编码器未写 ICC → 原图 ICC 被带上（" + icc2 + " 段）");
        else chk(icc2 == 0, "原图与编码器都无 ICC");
        Meta back2;
        InputStream bi3 = new ByteArrayInputStream(out2);
        try { back2 = Meta.parse(bi3, out2.length); } finally { bi3.close(); }
        chk(back2.motion == src.motion && back2.hdr == src.hdr, "无 ICC 分支下 motion/hdr 仍保持");
    }


    /** 动态照片：把视频段换成一段新视频，容器目录/MPF/尾部必须全部自洽 */
    static void testVideoReplace(File f) throws Exception {
        System.out.println("\n=== 动态照片：替换视频段 ===");
        long size = f.length();
        InputStream in = new FileInputStream(f);
        Meta src;
        try { src = Meta.parse(in, size); } finally { in.close(); }

        chk(src.trailerVideoLength == 9398907L, "XMP 里读出视频长度 " + src.trailerVideoLength);
        chk(src.trailerLeadLength == 603883L, "XMP 里读出增益图长度 " + src.trailerLeadLength);
        chk(src.trailerLeadLength + src.trailerVideoLength == src.trailerLength,
                "增益图 + 视频 = 整个尾部 " + src.trailerLength);

        // 造一段「新视频」，长度故意和原来差很多
        int fakeLen = 2_345_678;
        byte[] fake = new byte[fakeLen];
        byte[] mp4head = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
        System.arraycopy(mp4head, 0, fake, 0, mp4head.length);
        for (int i = 12; i < fakeLen; i++) fake[i] = (byte) (i * 31);
        File tmp = File.createTempFile("fakevideo", ".mp4");
        java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
        try { fo.write(fake); } finally { fo.close(); }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream orig = new FileInputStream(f);
        try { src.assemble(bos, fakeReencoded(4000, false), orig, true, tmp, fakeLen); }
        finally { orig.close(); }
        tmp.delete();
        byte[] out = bos.toByteArray();
        System.out.println("  重组输出 " + out.length + " 字节");

        // 1) XMP 里的视频长度必须换成新值
        Meta back;
        InputStream bi = new ByteArrayInputStream(out);
        try { back = Meta.parse(bi, out.length); } finally { bi.close(); }
        chk(back.trailerVideoLength == fakeLen,
                "XMP 视频长度已改写为 " + back.trailerVideoLength);
        chk(back.trailerLeadLength == 603883L,
                "增益图长度未变 " + back.trailerLeadLength);
        chk(back.motion, "仍是动态照片");
        chk(back.hdr, "增益图标记仍在");

        // 2) 增益图那段必须逐字节一致
        byte[] leadOut = new byte[(int) back.trailerLeadLength];
        System.arraycopy(out, (int) back.eoiEnd, leadOut, 0, leadOut.length);
        byte[] leadSrc = new byte[(int) src.trailerLeadLength];
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        raf.seek(src.eoiEnd);
        raf.readFully(leadSrc);
        raf.close();
        chk(java.util.Arrays.equals(leadOut, leadSrc),
                "增益图 " + leadSrc.length + " 字节原样保留");

        // 3) 新视频必须原样接在后面
        byte[] vidOut = new byte[fakeLen];
        System.arraycopy(out, (int) (back.eoiEnd + back.trailerLeadLength), vidOut, 0, fakeLen);
        chk(java.util.Arrays.equals(vidOut, fake), "新视频 " + fakeLen + " 字节完整写入");

        // 4) MPF 仍然指向新的增益图起点
        int mpfAbs = -1;
        for (int[] sg : scan(out)) {
            if (sg[0] == 0xE2 && segHead(out, sg[1], 3).startsWith("MPF")) { mpfAbs = sg[1]; break; }
        }
        if (mpfAbs >= 0) {
            int tiff = mpfAbs + 8;
            boolean le = out[tiff] == 'I' && out[tiff + 1] == 'I';
            int ifd = tiff + Meta.readInt(out, tiff + 4, le);
            int n = Meta.readShort(out, ifd, le);
            int arr = -1;
            for (int k = 0; k < n; k++) {
                int e = ifd + 2 + k * 12;
                if (Meta.readShort(out, e, le) == 0xB002) {
                    arr = tiff + Meta.readInt(out, e + 8, le);
                    break;
                }
            }
            long size0 = Meta.readInt(out, arr + 4, le) & 0xFFFFFFFFL;
            long sz1 = Meta.readInt(out, arr + 16 + 4, le) & 0xFFFFFFFFL;
            long off1 = Meta.readInt(out, arr + 16 + 8, le) & 0xFFFFFFFFL;
            chk(size0 == back.eoiEnd, "MPF 主图 size(" + size0 + ") = 新的增益图起点");
            chk(sz1 == 603883L, "MPF 增益图 size 未变 " + sz1);
            chk(tiff + off1 == back.eoiEnd, "MPF 增益图偏移指向 " + (tiff + off1));
        } else {
            bad("找不到 MPF 段");
        }
        chk(back.eoiEnd + back.trailerLeadLength + back.trailerVideoLength == out.length,
                "EOI + 增益图 + 新视频 = 文件长度（尾部无残留）");
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "reference");
        if (!dir.isDirectory()) dir = new File("../reference");
        // 样张是用户自己的照片，不进仓库；没有样张时整体跳过而不是报失败
        String[] need = {"IMG20261025201847_config.jpg", "dynamic_photo.jpg", "IMG20261025201847.heic"};
        int have = 0;
        for (String n : need) if (new File(dir, n).isFile()) have++;
        if (have == 0) {
            System.out.println("跳过元数据测试：未找到参考样张目录 " + dir.getPath());
            System.out.println("  放三张样张进去即可运行（见 reference/README.md）");
            return;
        }
        System.out.println("参考样张目录：" + dir.getCanonicalPath() + "（找到 " + have + "/" + need.length + "）");
        if (new File(dir, "IMG20261025201847_config.jpg").isFile()) {
            testFile(new File(dir, "IMG20261025201847_config.jpg"),
                    "ProXDR 静图（ICC + 增益图标记 + MPF 双图）");
        }
        if (new File(dir, "dynamic_photo.jpg").isFile()) {
            testFile(new File(dir, "dynamic_photo.jpg"),
                    "OPPO 动态照片（MPF + 13MB 视频尾）");
            testVideoReplace(new File(dir, "dynamic_photo.jpg"));
        }
        if (new File(dir, "IMG20261025201847.heic").isFile()) {
            testHeic(new File(dir, "IMG20261025201847.heic"));
        }
        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** HEIC：从 meta→iloc 的 Exif 项里取出 TIFF，并验证能转成可写入 JPEG 的 APP1 段 */
    static void testHeic(File f) throws Exception {
        System.out.println("\n=== HEIC（6144×8192 OPPO Find X9 Ultra）===");
        System.out.println("  " + f.getName() + "  " + f.length() + " 字节");
        if (!f.isFile() || f.length() == 0) { bad("文件不存在"); return; }

        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        byte[] tiff = Meta.heifExifTiff(all);
        chk(tiff != null && tiff.length > 32, "从 iloc 里取出 Exif TIFF 块 " +
                (tiff == null ? "null" : tiff.length + " 字节"));
        if (tiff == null) return;
        boolean le = tiff[0] == 'I' && tiff[1] == 'I';
        chk((tiff[0] == 'I' && tiff[1] == 'I') || (tiff[0] == 'M' && tiff[1] == 'M'),
                "TIFF 字节序合法 (" + (le ? "II" : "MM") + ")");

        Meta m;
        InputStream in = new ByteArrayInputStream(all);
        try { m = Meta.parse(in, all.length); } finally { in.close(); }
        System.out.println("  make=" + m.make + " | model=" + m.model
                + " | focal=" + m.focal + " focal35=" + m.focal35
                + " | f=" + m.fNumber + " | iso=" + m.iso
                + " | date=" + m.date + " | orient=" + m.orientation
                + " | segs=" + m.segs.size());

        chk(!m.model.isEmpty() || !m.make.isEmpty(), "解析出机型：" + m.model);
        chk(m.focal35 > 0 || m.focal > 0, "解析出焦距：" + (m.focal35 > 0 ? m.focal35 : m.focal));
        chk(m.iso > 0, "解析出 ISO：" + m.iso);
        chk(m.date.length() >= 19, "解析出拍摄时间：" + m.date);
        chk(m.segs.size() >= 1, "生成元数据段 " + m.segs.size() + " 个，可直接写进导出的 JPEG");
        chk(m.segs.size() >= 1 && m.segs.get(0).length - 10 == tiff.length,
                "首个 APP1 段负载 = TIFF 块 + 'Exif\\0\\0' 头");
        // HEIC 的色彩配置（colr）必须原样带过去，否则只能用编码器生成的替代 profile
        byte[] heicIcc = Meta.heifIcc(all);
        chk(heicIcc != null && heicIcc.length > 100, "从 colr 里取出 ICC " +
                (heicIcc == null ? "null" : heicIcc.length + " 字节"));
        boolean iccSeg = false;
        for (byte[] sg : m.segs) {
            if (heicIcc != null && sg.length > heicIcc.length + 14 && sg[0] == (byte) 0xFF && sg[1] == (byte) 0xE2
                    && sg[4] == 'I' && sg[5] == 'C') {
                byte[] got = new byte[sg.length - 18];
                System.arraycopy(sg, 18, got, 0, got.length);
                iccSeg = java.util.Arrays.equals(got, heicIcc);
            }
        }
        chk(iccSeg, "ICC 段字节与原 HEIC 的 colr 完全一致");

        // OPPO 把 HDR 增益图放在最后一个 box 之后的私有尾块里，必须被识别并搬运
        chk(m.trailerStart > 0, "识别出 HDR 尾块起点 " + m.trailerStart);
        chk(m.trailerLength > 0, "尾块长度 " + m.trailerLength);
        chk(m.trailerStart + m.trailerLength == all.length, "尾块一直延伸到文件末尾");
        chk(m.hdr, "HEIC 带 HDR 尾块 → hdr 标记为 true");

        int soi = -1;
        for (int k = (int) m.trailerStart; k + 3 < all.length; k++) {
            if ((all[k] & 0xFF) == 0xFF && (all[k + 1] & 0xFF) == 0xD8 && (all[k + 2] & 0xFF) == 0xFF) {
                soi = k;
                break;
            }
        }
        chk(soi > 0, "尾块里找到内嵌增益图 JPEG，偏移 " + (soi - m.trailerStart));

        // 走一遍 assemble：HEIC 没有尾部，不能把整个 HEIC 当成 trailer 拼进去
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream orig = new ByteArrayInputStream(all);
        try { m.assemble(bos, fakeReencoded(3000, false), orig, true, null, 0); } finally { orig.close(); }
        byte[] out = bos.toByteArray();
        // 现在会主动搬运 HEIC 的 HDR 尾块（而不是把整个 HEIC 当 trailer），
        // 所以输出长度 = 新主图 + 尾块，且尾块必须逐字节一致
        long expect = out.length - m.trailerLength;
        chk(expect > 0 && expect < 64 * 1024, "主图部分只有 " + expect + " 字节（没把整个 HEIC 拼进去）");
        byte[] tailOut = new byte[(int) m.trailerLength];
        System.arraycopy(out, out.length - tailOut.length, tailOut, 0, tailOut.length);
        byte[] tailSrc = new byte[(int) m.trailerLength];
        System.arraycopy(all, (int) m.trailerStart, tailSrc, 0, tailSrc.length);
        chk(java.util.Arrays.equals(tailOut, tailSrc),
                "HDR 尾块 " + tailSrc.length + " 字节逐字节原样搬过去");
        List<int[]> segs = scan(out);
        chk(segs.size() > 0 && segs.get(0)[0] == 0xE1, "首段是 EXIF APP1");
        Meta back;
        InputStream bi = new ByteArrayInputStream(out);
        try { back = Meta.parse(bi, out.length); } finally { bi.close(); }
        chk(back.model.equals(m.model), "回读机型一致：" + back.model);
        chk(back.hdr && back.trailerLength == m.trailerLength,
                "回读仍识别为带 HDR 尾块（" + back.trailerLength + " 字节）");
    }
}
