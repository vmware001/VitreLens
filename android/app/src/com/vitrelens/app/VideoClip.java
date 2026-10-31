package com.vitrelens.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 普通视频的只读信息、缓存与精确取帧。所有方法都在调用方工作线程执行。 */
class VideoClip {
    final File file;
    final int width;
    final int height;
    final int rotation;
    final int frameCount;
    final long durationUs;

    private VideoClip(File file, int width, int height, int rotation,
                      int frameCount, long durationUs) {
        this.file = file;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.frameCount = Math.max(1, frameCount);
        this.durationUs = Math.max(1L, durationUs);
    }

    static VideoClip open(Context ctx, Uri uri, File cache) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new RuntimeException("无法打开视频");
        try {
            FileOutputStream out = new FileOutputStream(cache);
            try {
                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }

        MediaMetadataRetriever mm = new MediaMetadataRetriever();
        int w, h, rotation, count;
        long duration;
        try {
            mm.setDataSource(cache.getAbsolutePath());
            w = intMeta(mm, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 0);
            h = intMeta(mm, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 0);
            rotation = intMeta(mm, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
            count = intMeta(mm, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, 0);
            duration = intMeta(mm, MediaMetadataRetriever.METADATA_KEY_DURATION, 0) * 1000L;
            if (rotation == 90 || rotation == 270) {
                int t = w;
                w = h;
                h = t;
            }
        } finally {
            mm.release();
        }
        if (w <= 0 || h <= 0) throw new RuntimeException("读不到视频尺寸");

        // 帧数不从时长猜：直接读样本表。只遍历索引、不解码，长视频也比逐帧解码快得多。
        MediaExtractor ex = new MediaExtractor();
        int actualCount = 0;
        long lastPts = 0;
        try {
            ex.setDataSource(cache.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) throw new RuntimeException("视频里没有画面轨道");
            ex.selectTrack(track);
            while (true) {
                long pts = ex.getSampleTime();
                if (pts < 0) break;
                lastPts = pts;
                actualCount++;
                if (!ex.advance()) break;
            }
        } finally {
            ex.release();
        }
        if (actualCount > 0) count = actualCount;
        if (duration <= 0) duration = lastPts + 1;
        return new VideoClip(cache, w, h, rotation, count, duration);
    }

    private static int intMeta(MediaMetadataRetriever mm, int key, int fallback) {
        try {
            String s = mm.extractMetadata(key);
            return s == null ? fallback : Integer.parseInt(s);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    long timeUsForFrame(int index) {
        int i = Math.max(0, Math.min(frameCount - 1, index));
        if (frameCount <= 1) return 0;
        long t = durationUs * i / (frameCount - 1);
        return Math.max(0, Math.min(durationUs - 1, t));
    }

    /** 直接走 MediaCodec，兼容 MediaMetadataRetriever 无法解码的 HLG / 特殊封装。 */
    Bitmap frameAt(long timeUs, int maxDimension) throws Exception {
        return VideoFrameDecoder.decode(file, timeUs, maxDimension);
    }
}
