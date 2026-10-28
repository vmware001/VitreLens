package com.vitrelens.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 从 MP4 里读出视频轨 edit list 的起始偏移（微秒）。
 *
 * 动态照片的视频段常常带一条 elst：媒体时间 0 之前还有一段「预卷」，
 * 真正的第 0 帧在 media_time 处。转码时如果不处理这段预卷，它会被当成正片内容，
 * 结果视频相对音频整体超前同样的时长（实测 OPPO 是 100ms）。
 *
 * 只做最小解析：moov → trak（hdlr 为 vide）→ edts/elst 的第一条 + mdia/mdhd 的 timescale。
 * 任何一步不符合预期都返回 0（当作没有 edit list），不影响主流程。
 */
final class Mp4EditList {

    /** 超过这个大小就不解析了，避免把大文件整个读进内存 */
    private static final int MAX_BYTES = 96 << 20;

    private Mp4EditList() {
    }

    /** @return 视频轨要跳过的媒体时间（微秒）；读不到返回 0 */
    static long videoStartUs(File f) {
        byte[] d = readAll(f);
        if (d == null || d.length < 32) return 0;

        int moov = findBox(d, 0, d.length, "moov");
        if (moov < 0) return 0;
        int moovEnd = (int) Math.min(d.length, moov + boxSize(d, moov));

        int p = moov + headerSize(d, moov);
        while (p + 8 <= moovEnd) {
            if ("trak".equals(boxType(d, p))) {
                long us = parseTrak(d, p, moovEnd);
                if (us >= 0) return us;
            }
            int next = (int) Math.min(moovEnd, p + boxSize(d, p));
            if (next <= p) break;
            p = next;
        }
        return 0;
    }

    /** @return >=0 表示找到了视频轨的 edit 偏移；-1 表示这条 trak 不是我们要的 */
    private static long parseTrak(byte[] d, int trak, int limit) {
        int end = (int) Math.min(limit, trak + boxSize(d, trak));
        int p = trak + headerSize(d, trak);

        boolean isVideo = false;
        int timescale = 0;
        long mediaTime = -1;
        boolean hasElst = false;

        while (p + 8 <= end) {
            String t = boxType(d, p);
            int bEnd = (int) Math.min(end, p + boxSize(d, p));
            if ("mdia".equals(t)) {
                int q = p + headerSize(d, p);
                while (q + 8 <= bEnd) {
                    String t2 = boxType(d, q);
                    int qEnd = (int) Math.min(bEnd, q + boxSize(d, q));
                    if ("hdlr".equals(t2)) {
                        // version(1) flags(3) pre_defined(4) handler_type(4)
                        int h = q + headerSize(d, q) + 8;
                        if (h + 4 <= qEnd) {
                            isVideo = "vide".equals(new String(d, h, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
                        }
                    } else if ("mdhd".equals(t2)) {
                        int m = q + headerSize(d, q);
                        if (m + 24 <= qEnd) {
                            int ver = d[m] & 0xFF;
                            timescale = readInt(d, m + (ver == 1 ? 20 : 12));
                        }
                    }
                    if (qEnd <= q) break;
                    q = qEnd;
                }
            } else if ("edts".equals(t)) {
                int q = p + headerSize(d, p);
                while (q + 8 <= bEnd) {
                    String t2 = boxType(d, q);
                    int qEnd = (int) Math.min(bEnd, q + boxSize(d, q));
                    if ("elst".equals(t2)) {
                        int m = q + headerSize(d, q);
                        if (m + 8 <= qEnd) {
                            int ver = d[m] & 0xFF;
                            long count = readInt(d, m + 4) & 0xFFFFFFFFL;
                            int e = m + 8;
                            // 只看第一条：它是正片起始位置，后面的是后续剪辑段
                            if (count > 0) {
                                mediaTime = (ver == 1)
                                        ? readLong(d, e + 8)          // segment_duration(8) media_time(8)
                                        : readInt(d, e + 4);          // segment_duration(4) media_time(4)
                                hasElst = true;
                            }
                        }
                    }
                    if (qEnd <= q) break;
                    q = qEnd;
                }
            }
            if (bEnd <= p) break;
            p = bEnd;
        }

        // media_time = -1 是「空编辑」（该段无媒体），不是我们要的偏移
        if (!isVideo || !hasElst || mediaTime <= 0 || timescale <= 0) return -1;
        long us = mediaTime * 1_000_000L / timescale;
        // 超过 10 秒的偏移几乎不可能是预卷，当作解析错了
        return (us > 0 && us < 10_000_000L) ? us : -1;
    }

    /* ── box 基础读取 ── */

    private static String boxType(byte[] d, int o) {
        return new String(d, o + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static long boxSize(byte[] d, int o) {
        long sz = readInt(d, o) & 0xFFFFFFFFL;
        if (sz == 1) {
            sz = readLong(d, o + 8);
        } else if (sz == 0) {
            sz = d.length - o;   // 延伸到文件末尾
        }
        return sz;
    }

    private static int headerSize(byte[] d, int o) {
        return (readInt(d, o) & 0xFFFFFFFFL) == 1 ? 16 : 8;
    }

    private static int readInt(byte[] d, int o) {
        if (o < 0 || o + 4 > d.length) return 0;
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    private static long readLong(byte[] d, int o) {
        if (o < 0 || o + 8 > d.length) return 0;
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[o + i] & 0xFFL);
        return v;
    }

    private static int findBox(byte[] d, int from, int to, String type) {
        int p = from;
        while (p + 8 <= to) {
            long sz = boxSize(d, p);
            if (sz < 8) return -1;
            if (type.equals(boxType(d, p))) return p;
            int next = (int) Math.min(to, p + sz);
            if (next <= p) return -1;
            p = next;
        }
        return -1;
    }

    private static byte[] readAll(File f) {
        long len = f.length();
        if (len <= 0 || len > MAX_BYTES) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return off == buf.length ? buf : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }
}
