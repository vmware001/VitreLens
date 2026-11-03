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

    /** 最近一次转码的色彩处理结论，供导出提示使用（null 表示还没跑过） */
    static volatile String lastColorNote;
    /** 最近一次转码的后端与分阶段耗时，供进度框和完成提示显示 */
    static volatile String lastBackendNote;
    static volatile String lastTimingNote;

    interface Progress {
        void onProgress(int frame, int total, String stage);
    }

    private static class Sample {
        byte[] data;
        long ptsUs;
        boolean video;
        int codecFlags;   // MediaCodec 的 BUFFER_FLAG_*，视频轨第一帧必须是 KEY_FRAME
        SampleSpool spool;
        long offset;
        int size;
    }

    /** 视频编码样本落盘，避免长视频把全部 byte[] 留在 Java 堆。 */
    private static class SampleSpool {
        final File file;
        final java.io.RandomAccessFile raf;

        SampleSpool(File file) throws Exception {
            this.file = file;
            if (file.exists() && !file.delete()) throw new RuntimeException("清不掉样本缓存");
            raf = new java.io.RandomAccessFile(file, "rw");
        }

        void write(Sample s, ByteBuffer src) throws Exception {
            s.spool = this;
            s.offset = raf.getFilePointer();
            s.size = src.remaining();
            byte[] chunk = new byte[256 * 1024];
            while (src.hasRemaining()) {
                int n = Math.min(chunk.length, src.remaining());
                src.get(chunk, 0, n);
                raf.write(chunk, 0, n);
            }
        }

        void read(Sample s, ByteBuffer dst) throws Exception {
            raf.seek(s.offset);
            byte[] chunk = new byte[256 * 1024];
            int left = s.size;
            while (left > 0) {
                int n = Math.min(chunk.length, left);
                raf.readFully(chunk, 0, n);
                dst.put(chunk, 0, n);
                left -= n;
            }
            dst.flip();
        }

        void close() {
            try { raf.close(); } catch (Exception ignored) { }
            if (file.exists() && !file.delete()) file.deleteOnExit();
        }
    }

    /**
     * @return 输出文件长度
     */
    static long transcode(Context ctx, File in, File out, Composer composer,
                          WatermarkPainter.Style st, Progress cb) throws Exception {
        return transcode(ctx, in, out, composer, st, cb, 0);
    }

    /**
     * @param maxDimension 输出最长边上限；0 表示优先保持原尺寸。
     *        部分设备 configure 接受 8K、真正编码时才失败，调用方可据此降级重试。
     */
    static long transcode(Context ctx, File in, File out, Composer composer,
                          WatermarkPainter.Style st, Progress cb, int maxDimension) throws Exception {
        return transcode(ctx, in, out, composer, st, cb, maxDimension, 0, true, true);
    }

    /**
     * @param codecMode 0 = 系统默认编解码器，1 = 强制 CPU 软件编解码器
     * @param allowDownscale 配置编码器失败时是否允许内部继续降尺寸
     */
    static long transcode(Context ctx, File in, File out, Composer composer,
                          WatermarkPainter.Style st, Progress cb, int maxDimension,
                          int codecMode, boolean allowDownscale,
                          boolean motionPhotoLayout) throws Exception {
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
        if (maxDimension > 0) {
            float k = Math.min(1f, maxDimension / (float) Math.max(outW, outH));
            outW = align2(Math.round(outW * k));
            outH = align2(Math.round(outH * k));
        }

        // 源视频的真实色彩特性。之前一律按 BT.601 + SDR 处理，
        // 遇到 BT.2020/HLG 的源（OPPO 的动态照片就是）会整体偏色发灰，而且不会报错。
        final int sourceTransfer = getInt(vf, MediaFormat.KEY_COLOR_TRANSFER, 0);
        final long sourceBitrate = Math.max(0, getInt(vf, MediaFormat.KEY_BIT_RATE, 0));
        final long sourcePixels = (long) srcW * srcH;
        final boolean dolbySource = "video/dolby-vision".equalsIgnoreCase(vMime);
        final boolean hdrSource = isHdrTransfer(sourceTransfer) || dolbySource;
        // 恢复 HDR 输出：有 HEVC 能力时保留源 HDR 信号，否则才回退 SDR。
        final boolean hdrOut = hdrSource && hdrEncodeSupported();
        if (hdrSource && !hdrOut) {
            Log.i(TAG, "源是 HDR，但本机没有可用 HEVC 编码器，回退 SDR");
        }
        YuvConverter yuv = new YuvConverter(
                getInt(vf, MediaFormat.KEY_COLOR_STANDARD, 0),
                getInt(vf, MediaFormat.KEY_COLOR_TRANSFER, 0),
                getInt(vf, MediaFormat.KEY_COLOR_RANGE, 0),
                hdrOut);

        // edit list：媒体时间 0 之前可能还有一段预卷，真正的第 0 帧在 media_time 处。
        // 不跳过的话成片会多出这段内容，相对音频整体超前（实测 OPPO 是 100ms）。
        long videoEditUs = Mp4EditList.videoStartUs(in);
        lastColorNote = null;
        Log.i(TAG, "视频色彩：" + yuv.label + "，edit list 偏移 " + videoEditUs + "us");

        // ── 1. 先把音轨样本全读进内存（几 KB，安全）
        List<Sample> audio = new ArrayList<Sample>();
        MediaFormat aFmt = null;
        if (aIdx >= 0) {
            // 某些带杜比视界/额外 data 轨的 MP4，音轨读完再 seek 会让视频轨计数变 0。
            // 音频使用独立 extractor，视频轨的 seek/样本游标完全不受影响。
            MediaExtractor ax = new MediaExtractor();
            ax.setDataSource(in.getAbsolutePath());
            try {
                aFmt = ax.getTrackFormat(aIdx);
                ax.selectTrack(aIdx);
                ByteBuffer bb = ByteBuffer.allocateDirect(64 * 1024);
                while (true) {
                    bb.clear();
                    int n = ax.readSampleData(bb, 0);
                    if (n <= 0) break;
                    Sample s = new Sample();
                    s.data = new byte[n];
                    bb.position(0);
                    bb.get(s.data, 0, n);
                    s.ptsUs = ax.getSampleTime();
                    audio.add(s);
                    if (!ax.advance()) break;
                }
            } finally {
                ax.release();
            }
        }

        // ── 2. 数帧、估算时长（决定编码器帧率与码率）
        ex.selectTrack(vIdx);
        // 新 extractor 选中轨道后天然位于首样本；部分 DV 文件 seekTo(0) 反而会得到 -1。
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
            // 部分 DV/厂商 MP4 的样本时间轴无法被 MediaExtractor 枚举，但帧数和时长可读。
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(in.getAbsolutePath());
                String fc = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                String du = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (fc != null) totalFrames = Integer.parseInt(fc);
                if (du != null) lastPts = Long.parseLong(du) * 1000L;
                if (totalFrames > 0) {
                    Log.i(TAG, "样本时间轴不可枚举，使用元数据帧数=" + totalFrames + "，解码时补齐 PTS");
                }
            } catch (Throwable t) {
                Log.w(TAG, "读取视频帧数元数据失败：" + t);
            } finally {
                try { mmr.release(); } catch (Throwable ignored) { }
            }
        }
        if (totalFrames <= 0) {
            ex.release();
            if (dolbySource) {
                // 某些 Android 版本看到 DV 配置盒后拒绝枚举整个轨道的样本。
                // 仅修改临时副本中的盒类型，视频码流逐字节不动，由 HEVC 解码基层。
                File compat = new File(out.getPath() + ".hevc-compat.mp4");
                try {
                    int patched = makeHevcCompatCopy(in, compat);
                    if (patched > 0) {
                        Log.i(TAG, "DV 轨道无法枚举，改用 HEVC 兼容副本，修复盒数=" + patched);
                        return transcode(ctx, compat, out, composer, st, cb, maxDimension,
                                codecMode, allowDownscale, motionPhotoLayout);
                    }
                } finally {
                    if (compat.exists()) compat.delete();
                }
            }
            throw new RuntimeException("视频没有可解码的帧");
        }
        double durSec = Math.max(0.2, lastPts / 1e6);
        int fps = getInt(vf, MediaFormat.KEY_FRAME_RATE, 0);
        if (fps <= 0 || fps > 120) fps = (int) Math.round(totalFrames / durSec);
        if (fps <= 0) fps = 30;
        fps = Math.max(10, Math.min(60, fps));

        // 不少容器（本机这个杜比视界文件就是）不暴露 KEY_BIT_RATE，也没有 btrt 盒。
        // 这时用「文件大小 ÷ 时长」估算，否则 88Mbps 的素材会按 40Mbps 重编码，
        // 成片只有原片一半大小，细节和暗部层次明显丢失。
        // 传入的 in 已经是纯视频轨（实况照片的 MP4 段已提前取出），因此偏差很小。
        long sourceBits = sourceBitrate;
        boolean bitrateEstimated = false;
        if (sourceBits <= 0) {
            long audioGuess = aFmt != null ? 192_000L : 0L;
            long est = (long) (in.length() * 8.0 / durSec) - audioGuess;
            if (est > 0) {
                sourceBits = est;
                bitrateEstimated = true;
            }
        }

        // 计数已走到文件尾。重新建立解码游标，不依赖该容器的 seekTo(0)。
        ex.release();
        ex = new MediaExtractor();
        ex.setDataSource(in.getAbsolutePath());
        ex.selectTrack(vIdx);

        Log.i(TAG, String.format("视频 %dx%d → %dx%d, %d 帧, %.2fs, %dfps, rot=%d",
                srcW, srcH, outW, outH, totalFrames, durSec, fps, rotation));
        if (bitrateEstimated) {
            Log.i(TAG, "源码率未标注，按文件大小估算为 " + (sourceBits / 1000000) + "Mbps");
        }
        if (cb != null) cb.onProgress(0, totalFrames, "准备编码器");

        // ── 3. 编码器。优先保持原分辨率；只有设备明确拒绝时才等比降档。
        final String encMime = hdrOut ? "video/hevc" : "video/avc";
        final int originalW = outW, originalH = outH;
        MediaCodec enc = null;
        Throwable configureError = null;
        // 原尺寸 → 最长边 1920 → 1280 → 864。逐级尝试，不在第一台设备上就牺牲清晰度。
        // 中间保留 1080p 一档：4K 素材直接被砍到 720p 的话，成片体积和观感都会断崖式下降。
        final int[] ladder = {0, 1920, 1280, 864};
        final int configureAttempts = allowDownscale ? ladder.length : 1;
        for (int attempt = 0; attempt < configureAttempts && enc == null; attempt++) {
            int limit = ladder[Math.min(attempt, ladder.length - 1)];
            float k = limit == 0 ? 1f : Math.min(1f, limit / (float) Math.max(originalW, originalH));
            outW = align2(Math.round(originalW * k));
            outH = align2(Math.round(originalH * k));
            MediaCodec candidate = null;
            Log.i(TAG, "编码尝试 " + outW + "x" + outH + "（第 " + (attempt + 1) + " 档）");
            try {
                candidate = createEncoder(encMime, codecMode == 1);
                int bitrate = bitrateFor(outW, outH, fps, sourceBits, sourcePixels, encMime);
                try {
                    candidate.configure(encoderFormat(encMime, outW, outH, fps, hdrOut,
                            sourceTransfer, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                } catch (Throwable bitrateError) {
                    // 码率超出该编码器能力时不能直接降分辨率：先退回按像素估算再试一次，
                    // 只有分辨率本身不被接受时才继续往下走降档。
                    final int fallback = bitrateFor(outW, outH, fps, 0, 0, encMime);
                    if (fallback >= bitrate) throw bitrateError;
                    Log.w(TAG, "编码器不接受 " + (bitrate / 1000000) + "Mbps，退回 "
                            + (fallback / 1000000) + "Mbps");
                    try { candidate.release(); } catch (Throwable ignored) { }
                    candidate = createEncoder(encMime, codecMode == 1);
                    candidate.configure(encoderFormat(encMime, outW, outH, fps, hdrOut,
                            sourceTransfer, fallback), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    bitrate = fallback;
                }
                enc = candidate;
                Log.i(TAG, "编码成功：" + outW + "x" + outH + " @" + (bitrate / 1000000)
                        + "Mbps（源码率 " + (sourceBits / 1000000) + "Mbps"
                        + (bitrateEstimated ? "，估算" : "") + "）");
                if (attempt > 0) Log.w(TAG, "编码器不接受原尺寸，等比降为 " + outW + "x" + outH);
            } catch (Throwable t) {
                configureError = t;
                if (candidate != null) {
                    try { candidate.release(); } catch (Throwable ignored) { }
                }
            }
        }
        if (enc == null) throw new Exception("没有可用的视频编码器", configureError);
        Surface encSurface = enc.createInputSurface();
        enc.start();

        // ── 4. 解码器：输出 YUV ByteBuffer
        // 注意两点：
        //  1. 不能用 ImageReader(RGBA_8888) 当输出面 —— 硬解码器输出端口只认 YUV，
        //     让它写 RGBA 会 setPortMode(DynamicANWBuffer) 失败，configure 直接报错；
        //  2. 不强行指定 KEY_COLOR_FORMAT，让解码器自己挑它支持的 YUV 格式。
        //  3. 逐个解码器试：有的机器上首个（通常是硬解）会 configure 失败，要能回退到软解。
        MediaCodec dec = openDecoder(vMime, vf, codecMode == 1);
        dec.start();

        boolean resizeFrame = rotation != 0 || outW != srcW || outH != srcH;
        // 旋转前目标尺寸。降档时直接在 YUV 阶段降采样，避免每帧生成完整 8K ARGB。
        int convertW = resizeFrame ? (swapped ? outH : outW) : srcW;
        int convertH = resizeFrame ? (swapped ? outW : outH) : srcH;
        SurfaceBlitter blitter = new SurfaceBlitter(encSurface, outW, outH);
        lastBackendNote = (softwareCodec(dec.getName()) ? "CPU 解码" : "GPU 解码")
                + " · " + composer.backendLabel()
                + " · " + (softwareCodec(enc.getName()) ? "CPU 编码" : "GPU 编码");
        Log.i(TAG, "运行后端：" + lastBackendNote + "｜解码器=" + dec.getName()
                + "，GL=" + composer.backendDetail() + "，编码器=" + enc.getName());
        if (cb != null) cb.onProgress(0, totalFrames, lastBackendNote);
        long transcodeStartNs = System.nanoTime();
        long yuvNs = 0, composeNs = 0, blitNs = 0;
        List<Sample> video = new ArrayList<Sample>();
        SampleSpool spool = new SampleSpool(new File(out.getPath() + ".samples"));
        MediaFormat encFmt = null;
        int done = 0;
        WatermarkPainter.Style vst = st.copy();
        if (motionPhotoLayout) {
            // 仅动态照片需要取景补偿：它的静态照片与视频段视野不同。
            // 普通视频没有这层关系，必须原样使用预览里的位置与宽度。
            vst.gapPct = Math.max(0.2f, st.gapPct + st.videoGapShift);
            vst.widthPct = Math.min(99f, st.widthPct * st.videoWidthScale / 100f);
        }
        Log.i(TAG, String.format("视频水印：距底 %.2f%%、宽度 %.1f%%（照片是 %.2f%% / %.1f%%）",
                vst.gapPct, vst.widthPct, st.gapPct, st.widthPct));

        try {
            FrameStats fs = processParallel(dec, ex, yuv, composer, blitter, enc, video, spool, encFmt,
                    srcW, srcH, outW, outH, rotation, swapped, convertW, convertH, resizeFrame,
                    vst, totalFrames, videoEditUs, fps, cb);
            done = fs.done;
            encFmt = fs.encFmt;
            yuvNs = fs.yuvNs;
            composeNs = fs.composeNs;
            blitNs = fs.blitNs;

            if (cb != null) cb.onProgress(totalFrames, totalFrames, lastBackendNote + " · 收尾编码");
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
                        s.ptsUs = bi.presentationTimeUs;
                        s.video = true;
                        s.codecFlags = bi.flags;
                        spool.write(s, ob);
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
            Log.e(TAG, "解码循环中断：已合成 " + done + " 帧", t);
            throw t;
        } finally {
            try { blitter.release(); } catch (Exception ignored) { }
            try { dec.stop(); } catch (Exception ignored) { }
            try { dec.release(); } catch (Exception ignored) { }
            try { enc.stop(); } catch (Exception ignored) { }
            try { enc.release(); } catch (Exception ignored) { }
            try { encSurface.release(); } catch (Exception ignored) { }
            try { ex.release(); } catch (Exception ignored) { }
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
        // MediaMuxer 依据「编码器输出格式」写 colr box。HDR 直通时必须保留 BT.2020/HLG，
        // 不能一律覆盖成 SDR，否则文件里是 HLG 信号、播放器却按 BT.709 解码，颜色会失真。
        try {
            if (hdrOut) {
                encFmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                encFmt.setInteger("color-transfer", sourceTransfer == 6 ? 6 : 7); // HLG
            } else {
                encFmt.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                encFmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            encFmt.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        } catch (Throwable ignored) {
        }
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
        for (Sample s : all) { if (s.video) vBytes += s.size; else aBytes += s.data.length; }
        Log.i(TAG, "轨道 v=" + vTrack + " a=" + aTrack
                + " 视频样本 " + video.size() + " 个/" + vBytes + " 字节"
                + " 音频 " + audio.size() + " 个/" + aBytes + " 字节"
                + " 编码格式=" + encFmt);
        // 时间基准：视频要减掉 edit list 的预卷，音频用它自己的时间轴；
        // 两者都减同一个「全局最早时刻」，相对关系才不会被破坏。
        long origin = Long.MAX_VALUE;
        for (Sample s : all) {
            long disp = s.video ? s.ptsUs - videoEditUs : s.ptsUs;
            if (disp < origin) origin = disp;
        }
        if (origin == Long.MAX_VALUE || origin < 0) origin = 0;
        Log.i(TAG, "时间基准 origin=" + origin + "us（视频预卷 " + videoEditUs + "us）");

        MediaCodec.BufferInfo wbi = new MediaCodec.BufferInfo();
        int vWritten = 0, aWritten = 0;
        try {
            for (Sample s : all) {
                int track = s.video ? vTrack : aTrack;
                if (track < 0) continue;
                long t = (s.video ? s.ptsUs - videoEditUs : s.ptsUs) - origin;
                if (t < 0) t = 0;
                int sampleSize = s.video ? s.size : s.data.length;
                wbi.set(0, sampleSize, t, s.codecFlags);
                try {
                    // 必须用直接缓冲：MediaMuxer 底层的 GetDirectBufferAddress 对堆缓冲
                    // 会失败，而且**不一定抛异常**，表现就是样本被静默丢掉、成片 0 帧。
                    ByteBuffer bb = ByteBuffer.allocateDirect(sampleSize);
                    if (s.spool != null) s.spool.read(s, bb);
                    else { bb.put(s.data); bb.flip(); }
                    mux.writeSampleData(track, bb, wbi);
                    if (s.video) vWritten++; else aWritten++;
                } catch (Throwable e) {
                    Log.e(TAG, "写入样本失败 track=" + track + " size=" + sampleSize + " pts=" + t, e);
                    throw e;
                }
            }
            Log.i(TAG, "写入完成：视频 " + vWritten + " 个，音频 " + aWritten + " 个");
        } finally {
            mux.stop();
            mux.release();
        }

        String colorNote = !hdrSource
                ? "视频 " + yuv.label
                : (hdrOut
                    ? "视频 " + yuv.label + " → 保留 HDR（HEVC / "
                        + (sourceTransfer == 6 ? "PQ" : "HLG") + "）"
                    : "视频 " + yuv.label + " → 已色调映射为 SDR（BT.709）");
        if (dolbySource) {
            // 杜比视界的动态元数据是逐帧 RPU，绑在原始像素上；只要重新编码就必然失效，
            // 也不能通过 MediaMuxer 写回 dvvC 盒。必须如实告诉用户，而不是含糊成"HDR"。
            colorNote += hdrOut
                    ? "\n杜比视界无法在重编码中保留动态元数据；已输出 "
                        + (sourceTransfer == 6 ? "PQ" : "HLG") + " HDR"
                    : "\n杜比视界无法在重编码中保留动态元数据；已输出 SDR";
        }
        lastColorNote = colorNote;
        lastTimingNote = String.format(java.util.Locale.US,
                "总耗时 %.1fs｜YUV %.1fs｜玻璃 %.1fs｜编码上传 %.1fs",
                (System.nanoTime() - transcodeStartNs) / 1e9,
                yuvNs / 1e9, composeNs / 1e9, blitNs / 1e9);
        Log.i(TAG, "转码性能：" + lastTimingNote);
        Log.i(TAG, "MP4 封装完成：" + out.length() + " 字节，" + lastColorNote);
        spool.close();
        return out.length();
    }

    /** 两个帧槽循环复用：生产线程做解码/YUV，GL 线程做合成/编码。 */
    private static class FrameSlot {
        Bitmap raw, work;
        int[] pixels;
        Canvas canvas;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        long ptsUs;
        boolean eos;

        FrameSlot() { eos = true; }

        FrameSlot(int cw, int ch, int outW, int outH, boolean resize) {
            raw = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            pixels = new int[cw * ch];
            work = resize ? Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888) : raw;
            canvas = resize ? new Canvas(work) : null;
        }

        void recycle() {
            if (raw != null) raw.recycle();
            if (work != null && work != raw) work.recycle();
        }
    }

    private static class FrameStats {
        int done;
        long yuvNs, composeNs, blitNs;
        MediaFormat encFmt;
    }

    private static FrameStats processParallel(MediaCodec dec, MediaExtractor ex, YuvConverter yuv,
            Composer composer, SurfaceBlitter blitter, MediaCodec enc, List<Sample> video,
            SampleSpool spool, MediaFormat initialFmt, int srcW, int srcH, int outW, int outH, int rotation,
            boolean swapped, int convertW, int convertH, boolean resizeFrame,
            WatermarkPainter.Style vst, int totalFrames, long videoEditUs, int fps, Progress cb) throws Exception {
        final java.util.concurrent.ArrayBlockingQueue<FrameSlot> free =
                new java.util.concurrent.ArrayBlockingQueue<FrameSlot>(2);
        final java.util.concurrent.ArrayBlockingQueue<FrameSlot> ready =
                new java.util.concurrent.ArrayBlockingQueue<FrameSlot>(2);
        long bytesPerSlot = 8L * convertW * convertH + 4L * outW * outH;
        final boolean dual = bytesPerSlot * 2 <= 384L * 1024 * 1024;
        final FrameSlot slotA = new FrameSlot(convertW, convertH, outW, outH, resizeFrame);
        final FrameSlot slotB = dual
                ? new FrameSlot(convertW, convertH, outW, outH, resizeFrame) : null;
        final FrameSlot eosSlot = new FrameSlot();
        free.put(slotA);
        if (slotB != null) free.put(slotB);
        Log.i(TAG, "帧流水线：" + (dual ? "双缓冲" : "单缓冲（控制 8K 内存）")
                + "，单槽约 " + (bytesPerSlot / 1048576) + "MB");
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final FrameStats stats = new FrameStats();
        stats.encFmt = initialFmt;

        Thread producer = new Thread(() -> {
            long yuvTotal = 0;
            try {
                boolean inputDone = false, outputDone = false;
                int guard = 0;
                long lastQueuedPts = -1;
                long syntheticPts = 0;
                final long frameUs = Math.max(1, Math.round(1000000.0 / Math.max(1, fps)));
                while (!outputDone) {
                    if (++guard > totalFrames * 4 + 2000) {
                        throw new RuntimeException("解码卡住了");
                    }
                    if (!inputDone) {
                        int ii = dec.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(ii);
                            long need = ex.getSampleSize();
                            if (need > 0 && ib != null && need > ib.capacity()) {
                                throw new RuntimeException("关键帧超过解码器输入缓冲");
                            }
                            int size = ex.readSampleData(ib, 0);
                            if (size < 0) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = ex.getSampleTime();
                                if (pts < 0) pts = syntheticPts;
                                syntheticPts += frameUs;
                                int fl = ex.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC
                                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                                dec.queueInputBuffer(ii, 0, size, pts, fl);
                                ex.advance();
                            }
                        }
                    }

                    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                    int oi = dec.dequeueOutputBuffer(bi, 10000);
                    if (oi < 0) continue;
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long pts = bi.presentationTimeUs;
                    boolean skip = pts == lastQueuedPts || pts < videoEditUs;
                    Image img = null;
                    FrameSlot slot = null;
                    boolean converted = false;
                    try {
                        if (!skip) {
                            try { img = dec.getOutputImage(oi); } catch (Exception ignored) { }
                            if (img != null) {
                                slot = free.take();
                                long t0 = System.nanoTime();
                                try {
                                    if (convertW == srcW && convertH == srcH) {
                                        converted = yuv.toBitmap(img, slot.raw, slot.pixels, srcW, srcH);
                                    } else {
                                        converted = yuv.toBitmapScaled(img, slot.raw, slot.pixels,
                                                srcW, srcH, convertW, convertH);
                                    }
                                } finally {
                                    yuvTotal += System.nanoTime() - t0;
                                    img.close();
                                }
                            }
                        }
                    } finally {
                        dec.releaseOutputBuffer(oi, false);
                    }
                    if (converted && slot != null) {
                        slot.ptsUs = pts;
                        ready.put(slot);
                        lastQueuedPts = pts;
                    } else if (slot != null) {
                        free.put(slot);
                    }
                    if (eos) outputDone = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "解码生产线程失败", t);
                failure.compareAndSet(null, t);
            } finally {
                stats.yuvNs = yuvTotal;
                try { ready.put(eosSlot); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "vl-decode");
        producer.start();

        try {
            while (true) {
                FrameSlot slot = ready.take();
                if (slot.eos) break;
                Bitmap work = slot.raw;
                if (resizeFrame) {
                    slot.canvas.drawColor(0xFF000000);
                    slot.canvas.save();
                    slot.canvas.translate(outW / 2f, outH / 2f);
                    slot.canvas.rotate(rotation);
                    float rw = swapped ? convertH : convertW;
                    float rh = swapped ? convertW : convertH;
                    float fit = Math.min(outW / rw, outH / rh);
                    slot.canvas.scale(fit, fit);
                    slot.canvas.translate(-convertW / 2f, -convertH / 2f);
                    slot.canvas.drawBitmap(slot.raw, 0, 0, slot.paint);
                    slot.canvas.restore();
                    work = slot.work;
                }
                long cs = System.nanoTime();
                composer.composeVideoOn(work, outW, outH, vst);
                stats.composeNs += System.nanoTime() - cs;
                long bs = System.nanoTime();
                blitter.draw(work, slot.ptsUs);
                stats.blitNs += System.nanoTime() - bs;
                stats.done++;
                if (cb != null && (stats.done % 3 == 0 || stats.done == totalFrames)) {
                    cb.onProgress(stats.done, totalFrames, lastBackendNote + " · 双缓冲流水线");
                }
                stats.encFmt = drain(enc, video, stats.encFmt, spool);
                free.put(slot);
            }
            producer.join();
            if (failure.get() != null) throw new RuntimeException(failure.get());
            stats.encFmt = drain(enc, video, stats.encFmt, spool);
            return stats;
        } catch (Throwable t) {
            Log.e(TAG, "合成/编码线程失败", t);
            throw t;
        } finally {
            if (producer.isAlive()) producer.join(5000);
            slotA.recycle();
            if (slotB != null) slotB.recycle();
        }
    }

    private static boolean softwareCodec(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.startsWith("c2.android.") || n.startsWith("omx.google.")
                || n.contains("software") || n.contains("soft");
    }

    /** 强制 CPU 时只创建软件编码器；找不到就明确失败，不能偷偷又退回硬件。 */
    private static MediaCodec createEncoder(String mime, boolean software) throws Exception {
        if (!software) return MediaCodec.createEncoderByType(mime);
        List<String> names = new ArrayList<String>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo ci : list.getCodecInfos()) {
            if (!ci.isEncoder() || !softwareCodec(ci.getName())) continue;
            for (String type : ci.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    names.add(ci.getName());
                    break;
                }
            }
        }
        if (names.isEmpty()) throw new RuntimeException("设备没有 " + mime + " 软件编码器");
        // 同一设备常同时暴露 OMX.google 与 c2.android 两套软件实现，优先更新的 C2。
        String name = names.get(0);
        for (String n : names) {
            if (n.toLowerCase(java.util.Locale.US).startsWith("c2.android.")) {
                name = n;
                break;
            }
        }
        Log.i(TAG, "使用 CPU 软件编码器：" + name + "（候选 " + names + "）");
        return MediaCodec.createByCodecName(name);
    }

    /** 把 DV 配置盒改成 free，兼容只接受普通 HEVC 的 MediaExtractor；不碰 mdat 码流。 */
    private static int makeHevcCompatCopy(File in, File out) throws Exception {
        long moovFrom = -1, moovTo = -1, ftypFrom = -1, ftypTo = -1;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(in, "r");
        try {
            long len = raf.length(), pos = 0;
            byte[] h = new byte[16];
            while (pos + 8 <= len) {
                raf.seek(pos);
                raf.readFully(h, 0, 8);
                long size = ((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16)
                        | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL);
                String type = new String(h, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int header = 8;
                if (size == 1) {
                    raf.readFully(h, 8, 8);
                    size = 0;
                    for (int i = 8; i < 16; i++) size = (size << 8) | (h[i] & 0xffL);
                    header = 16;
                } else if (size == 0) {
                    size = len - pos;
                }
                if (size < header || pos + size > len) break;
                if ("moov".equals(type)) { moovFrom = pos; moovTo = pos + size; }
                if ("ftyp".equals(type)) { ftypFrom = pos; ftypTo = pos + size; }
                pos += size;
            }
        } finally {
            raf.close();
        }
        if (moovFrom < 0) return 0;

        FileInputStream src = new FileInputStream(in);
        FileOutputStream dst = new FileOutputStream(out);
        int patched = 0;
        try {
            byte[] buf = new byte[4 * 1024 * 1024];
            long base = 0;
            int n;
            while ((n = src.read(buf)) >= 0) {
                if (n == 0) continue;
                for (int i = 0; i + 4 <= n; i++) {
                    long at = base + i;
                    if (at >= moovFrom && at + 4 <= moovTo
                            && ((buf[i] == 'd' && buf[i + 1] == 'v'
                            && (buf[i + 2] == 'v' || buf[i + 2] == 'c') && buf[i + 3] == 'C'))) {
                        buf[i] = 'f'; buf[i + 1] = 'r'; buf[i + 2] = 'e'; buf[i + 3] = 'e';
                        patched++;
                    } else if (at >= ftypFrom && at + 4 <= ftypTo
                            && buf[i] == 'd' && buf[i + 1] == 'b' && buf[i + 2] == 'y' && buf[i + 3] == '1') {
                        buf[i] = 'm'; buf[i + 1] = 'p'; buf[i + 2] = '4'; buf[i + 3] = '2';
                        patched++;
                    }
                }
                dst.write(buf, 0, n);
                base += n;
            }
            dst.flush();
        } finally {
            src.close();
            dst.close();
        }
        return patched;
    }

    /** 按优先顺序挑解码器：先系统默认（真机通常是硬解），configure 失败就换下一个 */
    static MediaCodec openDecoder(String mime, MediaFormat fmt) {
        return openDecoder(mime, fmt, false);
    }

    static MediaCodec openDecoder(String mime, MediaFormat fmt, boolean software) {
        final boolean dolby = "video/dolby-vision".equalsIgnoreCase(mime);
        List<String> names = new ArrayList<String>();
        names.add(mime);
        // 杜比视界 profile 8 等兼容文件的 BL 可由普通 HEVC 解码器读取。
        // 没有 DV 专用解码器时，不能把“找不到 video/dolby-vision”当作整个文件不可解码。
        // 不先插通用 video/hevc 包装器；直接尝试下面枚举出的具体 HEVC 解码器。
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (ci.isEncoder() || (software && !softwareCodec(ci.getName()))) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime) || (dolby && t.equalsIgnoreCase("video/hevc"))) {
                        if (!names.contains(ci.getName())) names.add(ci.getName());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "枚举解码器失败：" + t);
        }

        StringBuilder tried = new StringBuilder();
        for (String n : names) {
            MediaCodec c = null;
            boolean hevcFallback = dolby && !mime.equals(n);
            String originalMime = null;
            try {
                c = n.startsWith("video/") ? MediaCodec.createDecoderByType(n)
                                           : MediaCodec.createByCodecName(n);
                if (hevcFallback) {
                    originalMime = fmt.getString(MediaFormat.KEY_MIME);
                    fmt.setString(MediaFormat.KEY_MIME, "video/hevc");
                    // profile=256 / csd-2 / bl_compatibility_id 属于 DV 私有配置，
                    // 普通 HEVC 解码器会因此在 start 前后进入 Released 状态。
                    fmt.removeKey("profile");
                    fmt.removeKey("level");
                    fmt.removeKey("csd-2");
                    fmt.removeKey("bl_compatibility_id");
                }
                if (hevcFallback) Log.i(TAG, "HEVC 兼容配置：" + fmt);
                c.configure(fmt, null, null, 0);
                Log.i(TAG, "解码器：" + n + (hevcFallback ? "（杜比视界 HEVC 基层）" : ""));
                return c;
            } catch (Throwable t) {
                tried.append(n).append("(").append(t.getMessage()).append(") ");
                if (c != null) {
                    try { c.release(); } catch (Exception ignored) { }
                }
            } finally {
                if (originalMime != null) fmt.setString(MediaFormat.KEY_MIME, originalMime);
            }
        }
        throw new RuntimeException("没有可用的视频解码器：" + tried);
    }


    /** 拉取编码器输出，返回（可能更新过的）输出格式 */
    private static MediaFormat drain(MediaCodec enc, List<Sample> sink, MediaFormat known,
                                     SampleSpool spool) throws Exception {
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
                    s.ptsUs = bi.presentationTimeUs;
                    s.video = true;
                    s.codecFlags = bi.flags;
                    spool.write(s, ob);
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

    private static MediaFormat encoderFormat(String mime, int w, int h, int fps,
                                             boolean hdrOut, int sourceTransfer, int bitrate) {
        MediaFormat ef = MediaFormat.createVideoFormat(mime, w, h);
        ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            if (hdrOut) {
                ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                ef.setInteger("color-transfer", sourceTransfer == 6 ? 6 : 7);
            } else {
                ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                ef.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            ef.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        } catch (Throwable ignored) {
        }
        return ef;
    }

    /**
     * 目标码率。
     *
     * 只按像素估算（0.13 bit/像素/帧）会系统性地压掉高码率素材：4K60 的杜比视界/HLG
     * 原片常在 80Mbps 以上，按公式算出来 64Mbps 再被旧上限截到 40Mbps，成片只有原片的
     * 一半不到，细节和暗部层次明显退化——用户看到的就是「文件小了很多」。
     *
     * 因此：输出分辨率接近原片时，以源码率为基准（85%）取较大者；降档输出不套用源码率，
     * 否则 8K 降到 720p 也会被要求 80Mbps。
     */
    private static int bitrateFor(int w, int h, int fps, long sourceBitrate,
                                  long sourcePixels, String mime) {
        long px = (long) w * h;
        long target = (long) ((double) px * fps * 0.13);

        boolean sameScale = sourcePixels > 0 && px >= sourcePixels * 0.8;
        if (sourceBitrate > 0 && sameScale) {
            long fromSource = (long) (sourceBitrate * 0.85);
            if (fromSource > target) target = fromSource;
        }

        if (target < 4_000_000L) target = 4_000_000L;
        // 编码器自己还有上限，超出会 configure 失败或静默压回去
        long cap = encoderBitrateMax(mime);
        if (cap <= 0) cap = 120_000_000L;
        if (cap > 160_000_000L) cap = 160_000_000L;
        if (target > cap) target = cap;
        return (int) target;
    }

    /** 该 MIME 编码器声明支持的码率上限（取所有编码器的最大值）；拿不到返回 0 */
    private static long encoderBitrateMax(String mime) {
        long max = 0;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(mime)) continue;
                    android.util.Range<Integer> r = ci.getCapabilitiesForType(t)
                            .getVideoCapabilities().getBitrateRange();
                    if (r != null && r.getUpper() != null && r.getUpper() > max) {
                        max = r.getUpper();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return max;
    }

    private static int align2(int v) {
        return (v & 1) == 0 ? v : v - 1;
    }

    private static boolean isHdrTransfer(int transfer) {
        return transfer == 7 || transfer == 6;   // HLG / PQ
    }

    /** 有没有能吃 HLG 的 HEVC 编码器（Main / Main10 都可以承载 HLG） */
    private static boolean hdrEncodeSupported() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (!"video/hevc".equalsIgnoreCase(t)) continue;
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(t);
                    StringBuilder sb = new StringBuilder();
                    for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                        sb.append(pl.profile).append('/').append(pl.level).append(' ');
                        if (pl.profile == 1 || pl.profile == 2) {   // Main / Main10
                            Log.i(TAG, "HDR 编码器：" + ci.getName() + " profiles=" + sb);
                            return true;
                        }
                    }
                    Log.i(TAG, "HEVC 编码器 " + ci.getName() + " 不支持 Main/Main10，profiles=" + sb);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "探测 HEVC 编码器失败：" + t);
        }
        return false;
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
