package com.vitrelens.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * JPEG 容器解析与重建。刻意不依赖 Android API，便于在 JVM 上直接跑测试。
 *
 * 保留：EXIF / XMP / ICC（Display P3 广色域）/ 增益图标记（ProXDR）/ MPF，
 * 以及 EOI 之后的尾部（增益图 JPEG + 动态照片 MP4）。
 *
 * 重建顺序：SOI + 原元数据段 + 重编码后的图像段 + 尾部。
 * 重编码出来的 APP0/APP1/APP2 一律丢弃，避免 ICC 重复导致偏色。
 */
class Meta {

    List<byte[]> segs = new ArrayList<>();
    byte[] mpf;
    int mpfLittle = 0;
    int mpfTiff = 8;
    int[] mpfOffPos = new int[0];
    int[] mpfSizePos = new int[0];
    long[] mpfSizes = new long[0];

    long eoiEnd;          // 原文件中 EOI 之后的位置
    /** 尾部数据的绝对起点：JPEG 是 EOI 之后，HEIC 是最后一个 box 之后 */
    long trailerStart;
    long trailerLength;   // 尾部长度
    boolean motion;
    boolean hdr;
    boolean hasIcc;

    /** 尾部构成：视频之前的那段（一般是增益图 JPEG）字节数 */
    long trailerLeadLength;
    /** 尾部里视频段的字节数，0 表示没有视频 */
    long trailerVideoLength;
    /** XMP 文本（已去掉 namespace 前缀），用于改写容器长度 */
    String xmpText;
    /** XMP 段的 namespace 前缀原始字节，重建段时原样拼回去 */
    byte[] xmpPrefix;
    int xmpSegIndex = -1;

    String make = "", model = "", date = "";
    double fNumber, exposure, focal, focal35;
    int iso;
    int orientation = 1;

    private int exifSegIndex = -1;
    private int orientTiffPos = -1;
    private boolean exifLE;

    static Meta parse(InputStream raw, long fileLen) throws Exception {
        Meta m = new Meta();
        if (raw == null) return m;
        BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);

        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) { in.close(); return m; }

        if (b0 != 0xFF || b1 != 0xD8) {
            // 不是 JPEG：按 HEIF/HEIC 处理（EXIF 塞在 meta→iloc 的 Exif 项里）
            byte[] all = readAll(in, b0, b1);
            in.close();
            return parseHeif(all);
        }
        long pos = 2;

        byte[] xmpBytes = null;
        byte[] exifTiff = null;

        while (true) {
            int c0 = in.read();
            if (c0 < 0) break;
            pos++;
            if (c0 != 0xFF) continue;
            int marker = in.read();
            pos++;
            if (marker < 0) break;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
            if (marker == 0xD9) break;
            if (marker == 0xDA) break;   // SOS 段头之后是熵编码数据

            int lenHi = in.read(), lenLo = in.read();
            pos += 2;
            int len = (lenHi << 8) | lenLo;
            if (len < 2) break;
            byte[] payload = new byte[len - 2];
            int read = 0;
            while (read < payload.length) {
                int n = in.read(payload, read, payload.length - read);
                if (n < 0) break;
                read += n;
            }
            pos += read;

            byte[] full = new byte[2 + len];
            full[0] = (byte) 0xFF;
            full[1] = (byte) marker;
            full[2] = (byte) lenHi;
            full[3] = (byte) lenLo;
            System.arraycopy(payload, 0, full, 4, payload.length);

            if (marker == 0xE1) {
                if (startsWith(payload, new byte[]{'E', 'x', 'i', 'f', 0, 0})) {
                    m.exifSegIndex = m.segs.size();
                    m.segs.add(full);
                    exifTiff = new byte[payload.length - 6];
                    System.arraycopy(payload, 6, exifTiff, 0, exifTiff.length);
                } else if (startsWith(payload, "http://ns.adobe.com/xap/1.0/\u0000".getBytes("UTF-8"))) {
                    m.xmpSegIndex = m.segs.size();
                    m.segs.add(full);
                    xmpBytes = payload;
                }
            } else if (marker == 0xE2) {
                if (startsWith(payload, new byte[]{'M', 'P', 'F', 0})) {
                    m.mpf = full;
                    m.segs.add(full);
                    m.parseMpf(full);
                } else if (startsWith(payload, "ICC_PROFILE\u0000".getBytes("UTF-8"))) {
                    // 广色域（Display P3）靠这段 ICC 描述，丢了就偏色
                    m.hasIcc = true;
                    m.segs.add(full);
                } else if (startsWith(payload, "urn:iso:std:iso".getBytes("UTF-8"))) {
                    m.hdr = true;
                    m.segs.add(full);
                }
            }
        }

        // 找 EOI
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) break;
            pos++;
            if (prev == 0xFF && c == 0xD9) break;
            prev = c;
        }
        m.eoiEnd = pos;

        m.trailerStart = m.eoiEnd;
        if (fileLen > 0) m.trailerLength = Math.max(0, fileLen - m.eoiEnd);

        // 先读尾部开头 4 字节：OPPO 的 HDR 尾块是自描述的（这 4 字节大端就等于它自己的长度），
        // 这个特征比找标记段可靠 —— 这种尾块既不带 MPF 也不带 XMP。
        byte[] b4 = new byte[4];
        int n4 = -1;
        try {
            n4 = in.read(b4, 0, 4);
        } catch (Exception ignored) {
        }

        if (fileLen <= 0) {
            // 管道类 provider（openAssetFileDescriptor 也拿不到长度）会走到这里。
            // 之前这里直接让 trailerLength 归零，导出时尾部一个字节都不写 ——
            // 动态照片静默变成静态图，HDR 增益图也没了。这里把剩余字节自己数出来。
            long n = Math.max(0, n4);
            if (n4 == 4) {
                byte[] buf = new byte[1 << 16];
                int k;
                while ((k = in.read(buf)) > 0) n += k;
            }
            m.trailerLength = n;
            m.trailerStart = m.eoiEnd;
        }

        if (m.trailerLength >= 8 && n4 == 4) {
            long v = ((b4[0] & 0xFFL) << 24) | ((b4[1] & 0xFFL) << 16)
                    | ((b4[2] & 0xFFL) << 8) | (b4[3] & 0xFFL);
            if (v == m.trailerLength) m.hdr = true;
        }

        if (xmpBytes != null) {
            String xmp = new String(xmpBytes, "UTF-8");
            int xmlAt = xmp.indexOf("<x:xmpmeta");
            if (xmlAt < 0) xmlAt = xmp.indexOf("<?xpacket");
            if (xmlAt < 0) xmlAt = 0;
            m.xmpPrefix = new byte[xmlAt];
            System.arraycopy(xmpBytes, 0, m.xmpPrefix, 0, xmlAt);
            m.xmpText = xmp.substring(xmlAt);

            if (xmp.contains("MotionPhoto") || xmp.contains("MicroVideo")) {
                m.motion = m.trailerLength > 512;
            }
            // 增益图有两种声明方式：ISO 的 APP2 标记段，或 Google 的 XMP 容器目录。
            // 只认前者会漏掉一大批机器（例如 OPPO 动态照片），状态栏就会误报"无增益图"。
            if (!m.hdr && m.xmpText != null && m.xmpText.contains("Semantic=\"GainMap\"")) {
                m.hdr = true;
            }
            if (m.motion) {
                long gain = itemLength(m.xmpText, "GainMap");
                long video = itemLength(m.xmpText, "MotionPhoto");
                if (video <= 0) {
                    // Google 老格式：MicroVideoOffset 是从文件尾往回数的偏移
                    long off = attrLong(m.xmpText, "MicroVideoOffset");
                    if (off > 0 && off < m.trailerLength) video = off;
                }
                if (video > 0 && video <= m.trailerLength) {
                    m.trailerVideoLength = video;
                    m.trailerLeadLength = gain > 0 ? gain : (m.trailerLength - video);
                    if (m.trailerLeadLength + video > m.trailerLength) {
                        m.trailerLeadLength = m.trailerLength - video;
                    }
                    if (m.trailerLeadLength < 0) m.trailerLeadLength = 0;
                }
            }
        }

        if (exifTiff != null) m.parseExif(exifTiff);
        in.close();
        return m;
    }


    /* ── XMP 容器目录 ── */

    /** 取 Container:Directory 里某个 semantic 的 Item:Length */
    static long itemLength(String xmp, String semantic) {
        if (xmp == null) return -1;
        int i = xmp.indexOf("Item:Semantic=\"" + semantic + "\"");
        if (i < 0) return -1;
        return lengthAfter(xmp, i);
    }

    /** 取任意属性名的数字值（如 MicroVideoOffset） */
    static long attrLong(String xmp, String name) {
        if (xmp == null) return -1;
        int i = xmp.indexOf(name + "=\"");
        if (i < 0) return -1;
        return digitsAt(xmp, i + name.length() + 2);
    }

    private static long lengthAfter(String xmp, int from) {
        int j = xmp.indexOf("Item:Length=\"", from);
        if (j < 0) return -1;
        return digitsAt(xmp, j + 13);
    }

    private static long digitsAt(String s, int at) {
        int end = at;
        while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') end++;
        if (end == at) return -1;
        try {
            return Long.parseLong(s.substring(at, end));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 把某个 semantic 的 Item:Length 换成新值；返回新文本，失败返回 null */
    static String replaceItemLength(String xmp, String semantic, long value) {
        if (xmp == null) return null;
        int i = xmp.indexOf("Item:Semantic=\"" + semantic + "\"");
        if (i < 0) return null;
        int j = xmp.indexOf("Item:Length=\"", i);
        if (j < 0) return null;
        int numStart = j + 13;
        int numEnd = numStart;
        while (numEnd < xmp.length() && xmp.charAt(numEnd) >= '0' && xmp.charAt(numEnd) <= '9') numEnd++;
        if (numEnd == numStart) return null;
        return xmp.substring(0, numStart) + value + xmp.substring(numEnd);
    }

    /** 用新的 XMP 文本重建 APP1 段 */
    private byte[] rebuildXmpSegment(String text) {
        byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int prefixLen = xmpPrefix == null ? 0 : xmpPrefix.length;
        int payloadLen = prefixLen + body.length;
        if (payloadLen + 2 > 65533) return null;
        byte[] seg = new byte[4 + payloadLen];
        seg[0] = (byte) 0xFF;
        seg[1] = (byte) 0xE1;
        seg[2] = (byte) ((payloadLen + 2) >> 8);
        seg[3] = (byte) (payloadLen + 2);
        if (prefixLen > 0) System.arraycopy(xmpPrefix, 0, seg, 4, prefixLen);
        System.arraycopy(body, 0, seg, 4 + prefixLen, body.length);
        return seg;
    }

    /* ── HEIF / HEIC ── */

    private static byte[] readAll(InputStream in, int b0, int b1) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        out.write(b0);
        out.write(b1);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > (128 << 20)) break;   // 保险，别把内存吃穿
        }
        return out.toByteArray();
    }

    /** 只取 HEIC 的 EXIF，并转成一个能写进 JPEG 的 APP1 段 */
    /**
     * HEIC 末尾的私有尾块起点。OPPO 把 HDR 增益图放在最后一个 box 之后，
     * 结构是 [4B 长度][4B 增益上限][头部][内嵌 JPEG 增益图]。
     * 判断依据：box 类型必须是 4 个可打印 ASCII，否则就是尾块。
     */
    static long heifTrailerStart(byte[] f) {
        int o = 0;
        int end = f.length;
        while (o + 8 <= end) {
            long sz = readInt(f, o, false) & 0xFFFFFFFFL;
            if (sz == 1) {
                if (o + 16 > end) break;
                sz = ((long) readInt(f, o + 8, false) << 32)
                        | (readInt(f, o + 12, false) & 0xFFFFFFFFL);
            }
            if (sz < 8 || o + sz > end) break;
            boolean printable = true;
            for (int i = 4; i < 8; i++) {
                int c = f[o + i] & 0xFF;
                if (c < 0x20 || c > 0x7E) { printable = false; break; }
            }
            if (!printable) break;
            o += (int) sz;
        }
        return (o > 0 && o < end) ? o : 0;
    }

    static Meta parseHeif(byte[] f) {
        Meta m = new Meta();
        try {
            byte[] tiff = heifExifTiff(f);
            if (tiff == null) return m;
            byte[] seg = new byte[10 + tiff.length];
            int len = 2 + 6 + tiff.length;
            if (len > 65535) return m;
            seg[0] = (byte) 0xFF;
            seg[1] = (byte) 0xE1;
            seg[2] = (byte) (len >> 8);
            seg[3] = (byte) len;
            seg[4] = 'E'; seg[5] = 'x'; seg[6] = 'i'; seg[7] = 'f'; seg[8] = 0; seg[9] = 0;
            System.arraycopy(tiff, 0, seg, 10, tiff.length);
            m.segs.add(seg);
            m.parseExif(tiff);

            // HEIC/HEIF 的色彩配置在 meta→iprp→ipco→colr 里，必须原样带进 JPEG，
            // 否则只能用编码器生成的替代 profile，OPPO 的 ProXDR/宽色域判定会不认。
            byte[] icc = heifIcc(f);
            if (icc != null) {
                m.hasIcc = true;
                int max = 65533 - 14;
                int chunks = (icc.length + max - 1) / max;
                for (int ci = 0; ci < chunks; ci++) {
                    int off = ci * max;
                    int chunkLen = Math.min(max, icc.length - off);
                    byte[] seg2 = new byte[4 + 14 + chunkLen];
                    seg2[0] = (byte) 0xFF;
                    seg2[1] = (byte) 0xE2;
                    int sl = 2 + 14 + chunkLen;
                    seg2[2] = (byte) (sl >> 8);
                    seg2[3] = (byte) sl;
                    byte[] hdr = "ICC_PROFILE\u0000".getBytes("ISO-8859-1");
                    System.arraycopy(hdr, 0, seg2, 4, hdr.length);
                    seg2[4 + 11] = (byte) (ci + 1);
                    seg2[4 + 12] = (byte) chunks;
                    System.arraycopy(icc, off, seg2, 4 + 14, chunkLen);
                    m.segs.add(seg2);
                }
            }
        } catch (Exception ignored) { }
        // 最后一个 box 之后若还有数据，那是 OPPO 的 HDR 私有尾块（增益图），
        // 必须原样搬到导出的 JPEG 里，否则相册的 ProXDR 就没了。
        long ts = heifTrailerStart(f);
        if (ts > 0) {
            m.trailerStart = ts;
            m.trailerLength = f.length - ts;
            m.hdr = true;
        }
        return m;
    }

    /** 走 meta → iinf(Exif 项 id) → iloc(位置) 把 TIFF 块取出来 */
    static byte[] heifExifTiff(byte[] f) {
        int[] meta = findBox(f, 0, f.length, "meta");
        if (meta == null) {
            int[] moov = findBox(f, 0, f.length, "moov");
            if (moov != null) meta = findBox(f, moov[0], moov[0] + moov[1], "meta");
        }
        if (meta == null) return null;
        int ms = meta[0], me = meta[0] + meta[1];

        int[] iinf = findBox(f, ms + 4, me, "iinf");
        int[] iloc = findBox(f, ms + 4, me, "iloc");
        if (iinf == null || iloc == null) return null;

        int exifId = heifExifItemId(f, iinf[0], iinf[1]);
        if (exifId < 0) return null;

        long[] loc = heifItemLoc(f, iloc[0], iloc[1], exifId);
        if (loc == null) return null;
        int off = (int) loc[0];
        int len = (int) loc[1];
        if (off < 0 || len <= 8 || off + len > f.length) return null;

        // Exif 项开头 4 字节是到 TIFF 头的偏移
        int tiffOff = readInt(f, off, false);
        int start = off + 4 + tiffOff;
        if (start + 8 > off + len) return null;
        byte[] tiff = new byte[off + len - start];
        System.arraycopy(f, start, tiff, 0, tiff.length);
        return tiff;
    }

    /** 取 HEIF 的 colr 色彩配置（'prof' / 'rICC'） */
    static byte[] heifIcc(byte[] f) {
        int[] meta = findBox(f, 0, f.length, "meta");
        if (meta == null) {
            int[] moov = findBox(f, 0, f.length, "moov");
            if (moov != null) meta = findBox(f, moov[0], moov[0] + moov[1], "meta");
        }
        if (meta == null) return null;
        int[] iprp = findBox(f, meta[0] + 4, meta[0] + meta[1], "iprp");
        if (iprp == null) return null;
        // findBox 返回的已经是 payload 起点，iprp / ipco 都不是 FullBox，子盒直接从 payload 开始
        int[] ipco = findBox(f, iprp[0], iprp[0] + iprp[1] - 8, "ipco");
        if (ipco == null) return null;

        int o = ipco[0];
        int end = ipco[0] + ipco[1] - 8;
        while (o + 8 <= end) {
            long sz = readInt(f, o, false) & 0xFFFFFFFFL;
            if (sz < 8 || o + sz > end) break;
            String t = new String(f, o + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (t.equals("colr") && sz > 12) {
                String ct = new String(f, o + 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (ct.equals("prof") || ct.equals("rICC")) {
                    byte[] icc = new byte[(int) sz - 12];
                    System.arraycopy(f, o + 12, icc, 0, icc.length);
                    return icc;
                }
            }
            o += sz;
        }
        return null;
    }

    static int heifExifItemId(byte[] f, int boxStart, int boxLen) {
        int ver = f[boxStart] & 0xFF;       // boxStart 是 FullBox 的 payload 起点
        int p = boxStart + 4;
        p += (ver == 0) ? 2 : 4;          // entry_count
        int end = boxStart + boxLen;
        while (p + 8 <= end) {
            int sz = readInt(f, p, false);
            String type = new String(f, p + 4, Math.min(4, end - p - 4), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (sz < 8 || p + sz > end) break;
            if (type.equals("infe")) {
                int v = f[p + 8] & 0xFF;
                if (v >= 2) {
                    int q = p + 12;
                    int id = (v == 2) ? readShort(f, q, false) : readInt(f, q, false);
                    q += (v == 2) ? 2 : 4;
                    q += 2;                                  // protection index
                    if (q + 4 <= p + sz) {
                        String itype = new String(f, q, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                        if (itype.equals("Exif")) return id;
                    }
                }
            }
            p += sz;
        }
        return -1;
    }

    /** 返回 {绝对偏移, 长度}；只支持 construction_method = 0（文件内偏移，最常见） */
    static long[] heifItemLoc(byte[] f, int boxStart, int boxLen, int wantId) {
        int ver = f[boxStart] & 0xFF;
        int p = boxStart + 4;
        int b = f[p] & 0xFF; p++;
        int offSize = b >> 4, lenSize = b & 0xF;
        b = f[p] & 0xFF; p++;
        int baseSize = b >> 4, idxSize = (ver == 1 || ver == 2) ? (b & 0xF) : 0;
        int cnt = (ver < 2) ? readShort(f, p, false) : readInt(f, p, false);
        p += (ver < 2) ? 2 : 4;
        int end = boxStart + boxLen;

        for (int i = 0; i < cnt && p < end; i++) {
            int id = (ver < 2) ? readShort(f, p, false) : readInt(f, p, false);
            p += (ver < 2) ? 2 : 4;
            int cm = 0;
            if (ver == 1 || ver == 2) { cm = readShort(f, p, false) & 0xF; p += 2; }
            p += 2;                                        // data_reference_index
            long base = readN(f, p, baseSize); p += baseSize;
            int ec = readShort(f, p, false); p += 2;
            long firstOff = -1, firstLen = 0;
            for (int e = 0; e < ec && p < end; e++) {
                if (idxSize > 0) p += idxSize;
                long eo = readN(f, p, offSize); p += offSize;
                long el = readN(f, p, lenSize); p += lenSize;
                if (e == 0) { firstOff = eo; firstLen = el; }
            }
            if (id == wantId) {
                if (cm != 0) return null;
                return new long[]{base + firstOff, firstLen};
            }
        }
        return null;
    }

    static long readN(byte[] d, int o, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (d[o + i] & 0xFF);
        return v;
    }

    /** 在一个区间里找指定类型的 box，返回 {payloadStart, size} */
    static int[] findBox(byte[] f, int start, int end, String type) {
        int o = start;
        while (o + 8 <= end) {
            long sz = readInt(f, o, false) & 0xFFFFFFFFL;
            if (sz == 0) sz = end - o;
            int hdr = 8;
            if (sz == 1) {
                if (o + 16 > end) return null;
                sz = ((long) readInt(f, o + 8, false) << 32) | (readInt(f, o + 12, false) & 0xFFFFFFFFL);
                hdr = 16;
            }
            if (sz < hdr || o + sz > end) return null;
            String t = new String(f, o + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (t.equals(type)) return new int[]{o + hdr, (int) sz};
            o += sz;
        }
        return null;
    }

    private void parseExif(byte[] t) {
        try {
            boolean le = t[0] == 'I' && t[1] == 'I';
            exifLE = le;
            int ifd = readInt(t, 4, le);
            int n = readShort(t, ifd, le);
            for (int i = 0; i < n; i++) {
                int e = ifd + 2 + i * 12;
                if (e + 12 > t.length) break;
                int tag = readShort(t, e, le);
                int type = readShort(t, e + 2, le);
                int cnt = readInt(t, e + 4, le);
                int size = typeSize(type) * cnt;
                int vo = size > 4 ? readInt(t, e + 8, le) : e + 8;
                if (vo < 0 || vo + size > t.length) continue;
                if (tag == 0x010F) this.make = readAscii(t, vo, cnt);
                if (tag == 0x0110) this.model = readAscii(t, vo, cnt);
                if (tag == 0x0132 && this.date.isEmpty()) this.date = readAscii(t, vo, cnt);
                if (tag == 0x0112) {
                    this.orientation = readShort(t, e + 8, le);
                    this.orientTiffPos = e + 8;
                }
                if (tag == 0x8769) {
                    int sub = readInt(t, e + 8, le);
                    if (sub + 2 > t.length) continue;
                    int sn = readShort(t, sub, le);
                    for (int k = 0; k < sn; k++) {
                        int se = sub + 2 + k * 12;
                        if (se + 12 > t.length) break;
                        int st = readShort(t, se, le);
                        int stype = readShort(t, se + 2, le);
                        int scnt = readInt(t, se + 4, le);
                        int ssize = typeSize(stype) * scnt;
                        int sv = ssize > 4 ? readInt(t, se + 8, le) : se + 8;
                        if (sv < 0 || sv + ssize > t.length) continue;
                        if (st == 0x829A) this.exposure = readRational(t, sv, le);
                        if (st == 0x829D) this.fNumber = readRational(t, sv, le);
                        if (st == 0x8827) this.iso = readShort(t, sv, le);
                        if (st == 0x920A) this.focal = readRational(t, sv, le);
                        if (st == 0xA405) this.focal35 = readShort(t, sv, le);
                        if (st == 0x9003 && this.date.isEmpty()) this.date = readAscii(t, sv, scnt);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private void parseMpf(byte[] seg) {
        try {
            int tiff = 8;
            mpfTiff = tiff;
            boolean le = seg[tiff] == 'I' && seg[tiff + 1] == 'I';
            mpfLittle = le ? 1 : 0;
            int ifd = tiff + readInt(seg, tiff + 4, le);
            int n = readShort(seg, ifd, le);
            List<Integer> off = new ArrayList<>();
            List<Integer> sz = new ArrayList<>();
            List<Long> sizes = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                int e = ifd + 2 + k * 12;
                if (readShort(seg, e, le) != 0xB002) continue;
                int count = readInt(seg, e + 4, le);
                int arr = tiff + readInt(seg, e + 8, le);
                int imgs = count / 16;
                for (int i = 0; i < imgs; i++) {
                    sz.add(arr + i * 16 + 4);
                    off.add(arr + i * 16 + 8);
                    sizes.add((long) readInt(seg, arr + i * 16 + 4, le));
                }
            }
            mpfOffPos = new int[off.size()];
            mpfSizePos = new int[sz.size()];
            mpfSizes = new long[sizes.size()];
            for (int i = 0; i < off.size(); i++) {
                mpfOffPos[i] = off.get(i);
                mpfSizePos[i] = sz.get(i);
                mpfSizes[i] = sizes.get(i);
            }
        } catch (Exception ignored) { }
    }

    /**
     * 组装新文件：原元数据段 + 重编码图像数据 + 尾部
     *
     * @param upright    解码时已经按 EXIF 旋正了像素，那么输出必须把 Orientation 改成 1，
     *                   否则相册会再转一次
     * @param newVideo   换掉尾部里的视频段（已加水印的新 MP4）；null 表示原样搬运
     * @param newVideoLen newVideo 的字节数（要写回 XMP 的容器目录）
     */
    void assemble(OutputStream out, byte[] newJpeg, InputStream orig, boolean upright,
                  java.io.File newVideo, long newVideoLen) throws Exception {
        boolean replaceVideo = newVideo != null && trailerVideoLength > 0;
        boolean bodyHasIcc = hasIccSegment(newJpeg);
        boolean origHasIcc = false;
        for (byte[] s : segs) {
            if (s.length > 5 && s[0] == (byte) 0xFF && s[1] == (byte) 0xE2 && s[4] == 'I' && s[5] == 'C') {
                origHasIcc = true;
                break;
            }
        }

        // ICC 优先用**原图那段**：解码时保持了源色彩空间，原 profile 才是和像素严格对应的，
        // 而且 OPPO 的 ProXDR 认的就是原厂 profile（Display P3 Gamut with sRGB Transfer）。
        // 只有原图没有 ICC 时，才退而用重编码器自己写的那段。
        boolean keepBodyIcc = bodyHasIcc && !origHasIcc;
        List<byte[]> keepSegs = new ArrayList<>(segs);

        // 视频换了长度，XMP 容器目录必须跟着改，否则播放器按老长度取视频会截断/越界
        if (replaceVideo && xmpText != null) {
            String patched = replaceItemLength(xmpText, "MotionPhoto", newVideoLen);
            if (patched != null) {
                byte[] seg = rebuildXmpSegment(patched);
                if (seg != null) {
                    for (int i = 0; i < keepSegs.size(); i++) {
                        byte[] s = keepSegs.get(i);
                        if (s.length > 5 && s[0] == (byte) 0xFF && s[1] == (byte) 0xE1
                                && s[4] == 'h' && s[5] == 't') {
                            keepSegs.set(i, seg);
                            break;
                        }
                    }
                }
            }
        }

        byte[] body = stripReencodedMeta(newJpeg, 2, keepBodyIcc);

        // 若已旋正，复制一份把 Orientation 置 1 的 EXIF
        byte[] exifPatched = null;
        int exifIdx = -1;
        for (int i = 0; i < keepSegs.size(); i++) {
            byte[] s = keepSegs.get(i);
            if (s.length > 10 && s[0] == (byte) 0xFF && s[1] == (byte) 0xE1
                    && s[4] == 'E' && s[5] == 'x' && s[6] == 'i' && s[7] == 'f') {
                exifIdx = i;
                break;
            }
        }
        if (upright && exifIdx >= 0 && orientTiffPos >= 0 && orientation != 1) {
            exifPatched = keepSegs.get(exifIdx).clone();
            int p = 10 + orientTiffPos;
            if (p + 2 < exifPatched.length) {
                if (exifLE) { exifPatched[p] = 1; exifPatched[p + 1] = 0; }
                else { exifPatched[p] = 0; exifPatched[p + 1] = 1; }
            }
        }

        int metaLen = 0;
        for (byte[] s : keepSegs) metaLen += s.length;
        long trailerStart = 2L + metaLen + body.length;

        // ── 修补 MPF：第二张以后（增益图 / 视频封面）的偏移都相对 MPF TIFF 头
        byte[] mpfPatched = null;
        if (mpfOffPos.length > 1) {
            int mpfStart = 2;
            int idx = -1;
            for (int i = 0; i < keepSegs.size(); i++) {
                byte[] s = keepSegs.get(i);
                if (s.length > 5 && s[0] == (byte) 0xFF && s[1] == (byte) 0xE2 && s[4] == 'M' && s[5] == 'P') {
                    idx = i;
                    break;
                }
                mpfStart += s.length;
            }
            if (idx >= 0) {
                mpfPatched = keepSegs.get(idx).clone();
                long tiffAbs = mpfStart + mpfTiff;
                boolean le = mpfLittle == 1;
                writeInt(mpfPatched, mpfSizePos[0], trailerStart, le);
                long cursor = trailerStart;
                for (int m = 1; m < mpfOffPos.length; m++) {
                    writeInt(mpfPatched, mpfOffPos[m], cursor - tiffAbs, le);
                    if (m < mpfSizes.length && mpfSizes[m] > 0) cursor += mpfSizes[m];
                }
            }
        }

        out.write(0xFF);
        out.write(0xD8);
        for (int i = 0; i < keepSegs.size(); i++) {
            byte[] s = keepSegs.get(i);
            if (exifPatched != null && i == exifIdx) {
                out.write(exifPatched);
            } else if (mpfPatched != null && s.length > 5 && s[0] == (byte) 0xFF && s[1] == (byte) 0xE2
                    && s[4] == 'M' && s[5] == 'P') {
                out.write(mpfPatched);
            } else {
                out.write(s);
            }
        }
        out.write(body);

        // ── 尾部（增益图 + 动态照片视频），原样流式拷贝
        if (orig != null && trailerLength > 0) {
            BufferedInputStream bi = new BufferedInputStream(orig, 1 << 16);
            long skip = this.trailerStart > 0 ? this.trailerStart : eoiEnd;
            while (skip > 0) {
                long n = bi.skip(skip);
                if (n <= 0) {
                    if (bi.read() < 0) break;
                    n = 1;
                }
                skip -= n;
            }
            byte[] buf = new byte[1 << 16];
            if (replaceVideo) {
                // 视频之前的增益图原样搬，然后换成加水印的新视频
                long lead = trailerLeadLength;
                while (lead > 0) {
                    int want = (int) Math.min(buf.length, lead);
                    int n = bi.read(buf, 0, want);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    lead -= n;
                }
                // 这里不能调 VideoWatermark（那个类依赖 Android），自己搬
                java.io.FileInputStream vin = new java.io.FileInputStream(newVideo);
                try {
                    int n;
                    while ((n = vin.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    vin.close();
                }
            } else {
                int n;
                while ((n = bi.read(buf)) > 0) out.write(buf, 0, n);
            }
            bi.close();
        }
        out.flush();
    }

    /** JPEG 头部里是否已有 ICC_PROFILE 段（判断编码器写没写 ICC） */
    static boolean hasIccSegment(byte[] jpeg) {
        int o = 2;
        while (o + 3 < jpeg.length) {
            if ((jpeg[o] & 0xFF) != 0xFF) return false;
            int marker = jpeg[o + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) return false;
            int len = ((jpeg[o + 2] & 0xFF) << 8) | (jpeg[o + 3] & 0xFF);
            if (len < 2) return false;
            if (marker == 0xE2 && o + 15 < jpeg.length) {
                if (jpeg[o + 4] == 'I' && jpeg[o + 5] == 'C' && jpeg[o + 6] == 'C'
                        && jpeg[o + 7] == '_' && jpeg[o + 8] == 'P') {
                    return true;
                }
            }
            o += 2 + len;
        }
        return false;
    }

    /**
     * 丢掉重编码产生的 APP0/APPn/COM 段（避免和原图那几段重复 → 双 ICC 偏色），只留必需段。
     *
     * @param keepBodyIcc true 表示编码器自己写了 ICC 且我们决定沿用它的 —— 这段要留下
     */
    private static byte[] stripReencodedMeta(byte[] jpeg, int from, boolean keepBodyIcc) {
        ByteArrayOutputStream keep = new ByteArrayOutputStream(jpeg.length);
        int o = from;
        while (o + 3 < jpeg.length) {
            if ((jpeg[o] & 0xFF) != 0xFF) break;
            int marker = jpeg[o + 1] & 0xFF;
            if (marker == 0xDA) {
                keep.write(jpeg, o, jpeg.length - o);
                return keep.toByteArray();
            }
            if (marker >= 0xD0 && marker <= 0xD9) { o += 2; continue; }
            int len = ((jpeg[o + 2] & 0xFF) << 8) | (jpeg[o + 3] & 0xFF);
            if (len < 2) break;
            int segLen = 2 + len;
            boolean isMeta = (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE;
            if (isMeta && keepBodyIcc && marker == 0xE2 && o + 8 < jpeg.length
                    && jpeg[o + 4] == 'I' && jpeg[o + 5] == 'C' && jpeg[o + 6] == 'C') {
                isMeta = false;      // 这一段就是我们要沿用的 ICC
            }
            if (!isMeta) keep.write(jpeg, o, segLen);
            o += segLen;
        }
        // 解析中断（异常输入）：已处理的前缀 + 剩余字节原样接上，
        // 绝不能整体回退，否则重编码的 ICC 会和原图那段一起留在文件里。
        if (o < jpeg.length) keep.write(jpeg, o, jpeg.length - o);
        return keep.toByteArray();
    }

    /* ── 小工具 ── */

    private static boolean startsWith(byte[] a, byte[] b) {
        if (a.length < b.length) return false;
        for (int i = 0; i < b.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static int typeSize(int t) {
        switch (t) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: return 8;
            default: return 1;
        }
    }

    static int readShort(byte[] d, int o, boolean le) {
        int a = d[o] & 0xFF, b = d[o + 1] & 0xFF;
        return le ? (a | (b << 8)) : ((a << 8) | b);
    }

    static int readInt(byte[] d, int o, boolean le) {
        int a = d[o] & 0xFF, b = d[o + 1] & 0xFF, c = d[o + 2] & 0xFF, e = d[o + 3] & 0xFF;
        return le ? (a | (b << 8) | (c << 16) | (e << 24)) : ((a << 24) | (b << 16) | (c << 8) | e);
    }

    static void writeInt(byte[] d, int o, long v, boolean le) {
        if (le) {
            d[o] = (byte) (v & 0xFF);
            d[o + 1] = (byte) ((v >> 8) & 0xFF);
            d[o + 2] = (byte) ((v >> 16) & 0xFF);
            d[o + 3] = (byte) ((v >> 24) & 0xFF);
        } else {
            d[o] = (byte) ((v >> 24) & 0xFF);
            d[o + 1] = (byte) ((v >> 16) & 0xFF);
            d[o + 2] = (byte) ((v >> 8) & 0xFF);
            d[o + 3] = (byte) (v & 0xFF);
        }
    }

    private static String readAscii(byte[] d, int o, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && o + i < d.length; i++) {
            int c = d[o + i];
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    private static double readRational(byte[] d, int o, boolean le) {
        long num = readInt(d, o, le) & 0xFFFFFFFFL;
        long den = readInt(d, o + 4, le) & 0xFFFFFFFFL;
        return den == 0 ? 0 : (double) num / den;
    }
}
