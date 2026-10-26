package com.vitrelens.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 给动态照片里的视频段打水印：解码 → 逐帧合成 → 重编码 → 重新封装 MP4。
 *
 * 流程刻意做得「笨」但可靠：
 *   解码走 MediaCodec(ByteBuffer) → getOutputImage() 拿 YUV → 自己转 ARGB，
 *   合成复用 Composer（和照片导出同一套代码，所以水印位置/质感完全一致），
 *   编码走 MediaCodec(COLOR_FormatSurface) + EGL 上传位图。
 *
 * 音轨原样搬运（AAC 样本直接复用），不重新编码。
 */
class VideoWatermark {

    private static final String TAG = "VitreLens";

    interface Progress {
        void onProgress(int frame, int total, String stage);
    }

    private static class Sample {
        byte[] data;
        long ptsUs;
        boolean video;
        int codecFlags;   // MediaCodec 的 BUFFER_FLAG_*，视频轨第一帧必须是 KEY_FRAME
    }

    /**
     * @return 输出文件长度
     */
    static long transcode(Context ctx, File in, File out, Composer composer,
                          WatermarkPainter.Style st, Progress cb) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());

        int vIdx = -1, aIdx = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (mime.startsWith("video/") && vIdx < 0) vIdx = i;
            else if (mime.startsWith("audio/") && aIdx < 0) aIdx = i;
        }
        if (vIdx < 0) {
            ex.release();
            throw new RuntimeException("视频里没有画面轨道");
        }

        MediaFormat vf = ex.getTrackFormat(vIdx);
        String vMime = vf.getString(MediaFormat.KEY_MIME);
        int srcW = getInt(vf, MediaFormat.KEY_WIDTH, 0);
        int srcH = getInt(vf, MediaFormat.KEY_HEIGHT, 0);
        int rotation = getInt(vf, "rotation-degrees", 0);
        if (srcW <= 0 || srcH <= 0) {
            ex.release();
            throw new RuntimeException("读不到视频尺寸");
        }
        boolean swapped = (rotation == 90 || rotation == 270);
        int outW = align2(swapped ? srcH : srcW);
        int outH = align2(swapped ? srcW : srcH);

        // ── 1. 先把音轨样本全读进内存（几 KB，安全）
        List<Sample> audio = new ArrayList<Sample>();
        MediaFormat aFmt = null;
        if (aIdx >= 0) {
            aFmt = ex.getTrackFormat(aIdx);
            ex.selectTrack(aIdx);
            ByteBuffer bb = ByteBuffer.allocateDirect(64 * 1024);
            while (true) {
                bb.clear();
                int n = ex.readSampleData(bb, 0);
                if (n <= 0) break;
                Sample s = new Sample();
                s.data = new byte[n];
                bb.position(0);
                bb.get(s.data, 0, n);
                s.ptsUs = ex.getSampleTime();
                audio.add(s);
                if (!ex.advance()) break;
            }
            ex.unselectTrack(aIdx);
        }

        // ── 2. 数帧、估算时长（决定编码器帧率与码率）
        ex.selectTrack(vIdx);
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        int totalFrames = 0;
        long lastPts = 0;
        long totalBytes = 0;
        while (true) {
            long t = ex.getSampleTime();
            if (t < 0) break;
            lastPts = t;
            totalFrames++;
            totalBytes += Math.max(0, ex.getSampleSize());
            if (!ex.advance()) break;
        }
        if (totalFrames <= 0) {
            ex.release();
            throw new RuntimeException("视频没有可解码的帧");
        }
        double durSec = Math.max(0.2, lastPts / 1e6);
        int fps = getInt(vf, MediaFormat.KEY_FRAME_RATE, 0);
        if (fps <= 0 || fps > 120) fps = (int) Math.round(totalFrames / durSec);
        if (fps <= 0) fps = 30;
        fps = Math.max(10, Math.min(60, fps));
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        Log.i(TAG, String.format("视频 %dx%d → %dx%d, %d 帧, %.2fs, %dfps, rot=%d",
                srcW, srcH, outW, outH, totalFrames, durSec, fps, rotation));
        if (cb != null) cb.onProgress(0, totalFrames, "准备编码器");

        // ── 3. 编码器
        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        MediaFormat ef = MediaFormat.createVideoFormat("video/avc", outW, outH);
        ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(outW, outH, fps));
        ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encSurface = enc.createInputSurface();
        enc.start();

        // ── 4. 解码器：输出 YUV ByteBuffer
        // 注意两点：
        //  1. 不能用 ImageReader(RGBA_8888) 当输出面 —— 硬解码器输出端口只认 YUV，
        //     让它写 RGBA 会 setPortMode(DynamicANWBuffer) 失败，configure 直接报错；
        //  2. 不强行指定 KEY_COLOR_FORMAT，让解码器自己挑它支持的 YUV 格式。
        //  3. 逐个解码器试：有的机器上首个（通常是硬解）会 configure 失败，要能回退到软解。
        MediaCodec dec = openDecoder(vMime, vf);
        dec.start();

        Bitmap frameRaw = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
        Bitmap frameWork = (rotation == 0) ? frameRaw
                : Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        int[] rowPixels = new int[srcW * srcH];
        Canvas workCanvas = (rotation == 0) ? null : new Canvas(frameWork);
        Paint blitPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        SurfaceBlitter blitter = new SurfaceBlitter(encSurface, outW, outH);
        List<Sample> video = new ArrayList<Sample>();
        MediaFormat encFmt = null;
        long basePts = -1;
        int done = 0;
        int guard = 0;
        // 视频取景比照片窄（没有片门全开）：实测 OPPO 约为照片的 94%，且贴着底边裁。
        // 直接按这个几何关系换算水印位置与宽度，用户还能再手动微调。
        WatermarkPainter.Style vst = st.copy();
        vst.gapPct = Math.max(0.2f, st.gapPct + st.videoGapShift);
        vst.widthPct = Math.min(99f, st.widthPct * st.videoWidthScale / 100f);
        Log.i(TAG, String.format("视频水印：距底 %.2f%%、宽度 %.1f%%（照片是 %.2f%% / %.1f%%）",
                vst.gapPct, vst.widthPct, st.gapPct, st.widthPct));

        try {
            boolean inputDone = false, outputDone = false;
            long lastRenderedPts = -1;
            while (!outputDone) {
                if (++guard > totalFrames * 4 + 2000) {
                    throw new RuntimeException("解码卡住了（已处理 " + done + " 帧）");
                }
                if (!inputDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        long need = ex.getSampleSize();
                        if (need > 0 && ib != null && need > ib.capacity()) {
                            throw new RuntimeException("关键帧 " + need + " 字节超过解码器输入缓冲 "
                                    + ib.capacity() + "，这台设备的解码器吃不下");
                        }
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            int fl = ex.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC
                                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            dec.queueInputBuffer(ii, 0, size, pts, fl);
                            ex.advance();
                        }
                    }
                }

                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                if (oi >= 0) {
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long pts = bi.presentationTimeUs;
                    boolean dup = (pts == lastRenderedPts);

                    if (!dup) {
                        if (basePts < 0) basePts = pts;
                        Image img = null;
                        try {
                            img = dec.getOutputImage(oi);
                        } catch (Exception ignored) { }
                        Box ok = new Box();
                        int nPlanes = -1;
                        if (img != null) {
                            nPlanes = img.getPlanes() == null ? 0 : img.getPlanes().length;
                            try {
                                ok.v = yuvToBitmap(img, frameRaw, rowPixels, srcW, srcH);
                            } finally {
                                img.close();
                            }
                        }
                        if (ok.v) {
                            Bitmap work = frameRaw;
                            if (rotation != 0) {
                                workCanvas.drawColor(0xFF000000);
                                workCanvas.save();
                                workCanvas.translate(outW / 2f, outH / 2f);
                                workCanvas.rotate(rotation);
                                workCanvas.translate(-srcW / 2f, -srcH / 2f);
                                workCanvas.drawBitmap(frameRaw, 0, 0, blitPaint);
                                workCanvas.restore();
                                work = frameWork;
                            }
                            // 水印（和照片导出完全同一套逻辑）
                            composer.composeOn(work, outW, outH, vst);
                            blitter.draw(work, pts);
                            lastRenderedPts = pts;
                            done++;
                            if (cb != null && (done % 3 == 0 || done == totalFrames)) {
                                cb.onProgress(done, totalFrames, "正在给视频加水印");
                            }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if (eos) outputDone = true;
                }
                encFmt = drain(enc, video, encFmt);
            }

            if (cb != null) cb.onProgress(totalFrames, totalFrames, "收尾编码");
            enc.signalEndOfInputStream();
            boolean encDone = false;
            int eosGuard = 0;
            while (!encDone) {
                if (++eosGuard > 100000) throw new RuntimeException("编码器没有正常收尾");
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                int oi = enc.dequeueOutputBuffer(bi, 10000);
                if (oi >= 0) {
                    if (bi.size > 0) {
                        ByteBuffer ob = enc.getOutputBuffer(oi);
                        if (ob == null) { enc.releaseOutputBuffer(oi, false); continue; }
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        Sample s = new Sample();
                        s.data = new byte[bi.size];
                        ob.get(s.data);
                        s.ptsUs = bi.presentationTimeUs;
                        s.video = true;
                        s.codecFlags = bi.flags;
                        video.add(s);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encFmt = enc.getOutputFormat();
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 继续等
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "解码循环中断：已合成 " + done + " 帧，guard=" + guard, t);
            throw t;
        } finally {
            try { blitter.release(); } catch (Exception ignored) { }
            try { dec.stop(); } catch (Exception ignored) { }
            try { dec.release(); } catch (Exception ignored) { }
            try { enc.stop(); } catch (Exception ignored) { }
            try { enc.release(); } catch (Exception ignored) { }
            try { encSurface.release(); } catch (Exception ignored) { }
            try { ex.release(); } catch (Exception ignored) { }
            frameRaw.recycle();
            if (frameWork != frameRaw) frameWork.recycle();
        }

        Log.i(TAG, "编码完成：视频样本 " + video.size() + " 个，音频样本 " + audio.size() + " 个");
        if (video.isEmpty()) throw new RuntimeException("编码器没有输出任何帧");
        if (encFmt == null) throw new RuntimeException("拿不到编码器输出格式");

        // ── 5. 重新封装：视频 + 原音轨，按时间戳排序后写入
        if (cb != null) cb.onProgress(totalFrames, totalFrames, "封装 MP4");
        List<Sample> all = new ArrayList<Sample>(video.size() + audio.size());
        all.addAll(video);
        all.addAll(audio);
        // 按时间戳排好再写，MediaMuxer 才不会因为跨轨乱序而报错
        Collections.sort(all, (a, b) -> Long.compare(a.ptsUs, b.ptsUs));

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vTrack = mux.addTrack(encFmt);
        int aTrack = -1;
        if (aFmt != null && !audio.isEmpty()) {
            try {
                aTrack = mux.addTrack(aFmt);
            } catch (Exception ignored) {
                aTrack = -1;
            }
        }
        mux.start();
        long vBytes = 0, aBytes = 0;
        for (Sample s : all) { if (s.video) vBytes += s.data.length; else aBytes += s.data.length; }
        Log.i(TAG, "轨道 v=" + vTrack + " a=" + aTrack
                + " 视频样本 " + video.size() + " 个/" + vBytes + " 字节"
                + " 音频 " + audio.size() + " 个/" + aBytes + " 字节"
                + " 编码格式=" + encFmt);
        MediaCodec.BufferInfo wbi = new MediaCodec.BufferInfo();
        int vWritten = 0, aWritten = 0;
        try {
            for (Sample s : all) {
                int track = s.video ? vTrack : aTrack;
                if (track < 0) continue;
                long t = s.ptsUs - (basePts > 0 ? basePts : 0);
                if (t < 0) t = 0;
                wbi.set(0, s.data.length, t, s.codecFlags);
                try {
                    // 必须用直接缓冲：MediaMuxer 底层的 GetDirectBufferAddress 对堆缓冲
                    // 会失败，而且**不一定抛异常**，表现就是样本被静默丢掉、成片 0 帧。
                    ByteBuffer bb = ByteBuffer.allocateDirect(s.data.length);
                    bb.put(s.data);
                    bb.flip();
                    mux.writeSampleData(track, bb, wbi);
                    if (s.video) vWritten++; else aWritten++;
                } catch (Throwable e) {
                    Log.e(TAG, "写入样本失败 track=" + track + " size=" + s.data.length + " pts=" + t, e);
                    throw e;
                }
            }
            Log.i(TAG, "写入完成：视频 " + vWritten + " 个，音频 " + aWritten + " 个");
        } finally {
            mux.stop();
            mux.release();
        }

        Log.i(TAG, "MP4 封装完成：" + out.length() + " 字节");
        return out.length();
    }

    /** 按优先顺序挑解码器：先系统默认（真机通常是硬解），configure 失败就换下一个 */
    private static MediaCodec openDecoder(String mime, MediaFormat fmt) {
        List<String> names = new ArrayList<String>();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) {
                        names.add(ci.getName());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "枚举解码器失败：" + t);
        }
        if (names.isEmpty()) names.add(mime);   // 交给系统按 mime 选

        StringBuilder tried = new StringBuilder();
        for (String n : names) {
            MediaCodec c = null;
            try {
                c = mime.equals(n) ? MediaCodec.createDecoderByType(mime)
                                   : MediaCodec.createByCodecName(n);
                c.configure(fmt, null, null, 0);
                Log.i(TAG, "解码器：" + n);
                return c;
            } catch (Throwable t) {
                tried.append(n).append("(").append(t.getMessage()).append(") ");
                if (c != null) {
                    try { c.release(); } catch (Exception ignored) { }
                }
            }
        }
        throw new RuntimeException("没有可用的视频解码器：" + tried);
    }


    /** 拉取编码器输出，返回（可能更新过的）输出格式 */
    private static MediaFormat drain(MediaCodec enc, List<Sample> sink, MediaFormat known)
            throws Exception {
        while (true) {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int oi = enc.dequeueOutputBuffer(bi, 0);
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return known;
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                known = enc.getOutputFormat();
                continue;
            }
            if (oi < 0) continue;
            if (bi.size > 0) {
                ByteBuffer ob = enc.getOutputBuffer(oi);
                if (ob != null) {
                    ob.position(bi.offset);
                    ob.limit(bi.offset + bi.size);
                    Sample s = new Sample();
                    s.data = new byte[bi.size];
                    ob.get(s.data);
                    s.ptsUs = bi.presentationTimeUs;
                    s.video = true;
                    s.codecFlags = bi.flags;
                    sink.add(s);
                }
            }
            enc.releaseOutputBuffer(oi, false);
        }
    }

    /** 小盒子，用来把「这帧转换成功了吗」带出 try 块 */
    private static class Box {
        boolean v;
    }

    /**
     * Image(YUV_420_888) → ARGB Bitmap。
     * BT.601 limited range，整数运算；先按行拷成数组再算，避免逐像素走 ByteBuffer.get。
     */
    private static boolean yuvToBitmap(Image img, Bitmap bmp, int[] scratch, int W, int H) {
        Image.Plane[] planes = img.getPlanes();
        if (planes == null || planes.length < 3) return false;
        if (img.getWidth() != W || img.getHeight() != H) return false;

        ByteBuffer yB = planes[0].getBuffer();
        ByteBuffer uB = planes[1].getBuffer();
        ByteBuffer vB = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();

        int cw = (W + 1) / 2;
        int yLen = W * yPix;
        int cLen = cw * Math.max(uPix, vPix);
        byte[] yLine = new byte[yLen];
        byte[] uLine = new byte[cLen];
        byte[] vLine = new byte[cLen];

        for (int y = 0; y < H; y++) {
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

                int c = Y - 16;
                int d = U - 128;
                int e = V - 128;
                int r = (298 * c + 409 * e + 128) >> 8;
                int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                int b = (298 * c + 516 * d + 128) >> 8;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                scratch[base + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        bmp.setPixels(scratch, 0, W, 0, 0, W, H);
        return true;
    }

    private static int bitrateFor(int w, int h, int fps) {
        // 约 0.13 bit/像素/帧，夹在 4~16 Mbps
        long b = (long) ((double) w * h * fps * 0.13);
        if (b < 4_000_000L) b = 4_000_000L;
        if (b > 16_000_000L) b = 16_000_000L;
        return (int) b;
    }

    private static int align2(int v) {
        return (v & 1) == 0 ? v : v - 1;
    }

    private static int getInt(MediaFormat f, String key, int def) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (Exception e) {
            return def;
        }
    }

    /* ── MP4 探测：在尾部里找到真正的 MP4 起点 ── */

    /** 在 byte[] 里找 ftyp box 的起点；找不到返回 -1 */
    static int findMp4(byte[] d, int from, int to) {
        for (int i = Math.max(0, from); i + 12 <= to; i++) {
            if (d[i + 4] == 'f' && d[i + 5] == 't' && d[i + 6] == 'y' && d[i + 7] == 'p') {
                int sz = ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16)
                        | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                if (sz >= 8 && i + sz <= to + 64) return i;
            }
        }
        return -1;
    }

    /** 从输入流里跳过 skip 字节，然后把 length 字节写到 dst */
    static void extractRange(java.io.InputStream in, long skip, long length, File dst) throws Exception {
        long left = skip;
        byte[] buf = new byte[1 << 16];
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() < 0) throw new RuntimeException("原图比预期短，取不到视频段");
                n = 1;
            }
            left -= n;
        }
        FileOutputStream out = new FileOutputStream(dst);
        try {
            long remain = length;
            while (remain > 0) {
                int want = (int) Math.min(buf.length, remain);
                int n = in.read(buf, 0, want);
                if (n < 0) throw new RuntimeException("视频段被截断（只读到 "
                        + (length - remain) + "/" + length + " 字节）");
                out.write(buf, 0, n);
                remain -= n;
            }
        } finally {
            out.close();
        }
    }

    /** 把 File 拷进输出流 */
    static void copyTo(File f, java.io.OutputStream out) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    static File tempFile(Context ctx, String name) {
        File d = ctx.getCacheDir();
        return new File(d, name);
    }


}
