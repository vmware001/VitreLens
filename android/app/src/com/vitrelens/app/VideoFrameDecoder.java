package com.vitrelens.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;

/** 单帧精确解码：用于普通视频的帧滑块预览，不依赖 MediaMetadataRetriever。 */
class VideoFrameDecoder {

    static Bitmap decode(File file, long targetUs, int maxDimension) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        Bitmap raw = null, best = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) throw new RuntimeException("视频里没有画面轨道");
            MediaFormat fmt = ex.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int w = getInt(fmt, MediaFormat.KEY_WIDTH, 0);
            int h = getInt(fmt, MediaFormat.KEY_HEIGHT, 0);
            int rotation = getInt(fmt, "rotation-degrees", 0);
            if (w <= 0 || h <= 0) throw new RuntimeException("读不到视频尺寸");

            YuvConverter yuv = new YuvConverter(
                    getInt(fmt, MediaFormat.KEY_COLOR_STANDARD, 0),
                    getInt(fmt, MediaFormat.KEY_COLOR_TRANSFER, 0),
                    getInt(fmt, MediaFormat.KEY_COLOR_RANGE, 0));
            ex.selectTrack(track);
            ex.seekTo(Math.max(0, targetUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            dec = VideoWatermark.openDecoder(mime, fmt);
            dec.start();

            raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] scratch = new int[w * h];
            long bestPts = Long.MIN_VALUE;
            boolean inputDone = false, outputDone = false;
            int guard = 0;

            while (!outputDone) {
                if (++guard > 10000) throw new RuntimeException("单帧解码超时");
                if (!inputDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        if (ib == null) throw new RuntimeException("解码器输入缓冲不可用");
                        ib.clear();
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(),
                                    ex.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC
                                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            ex.advance();
                        }
                    }
                }

                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                if (oi < 0) continue;
                long pts = bi.presentationTimeUs;
                boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                Image img = null;
                try {
                    img = dec.getOutputImage(oi);
                    if (img != null && yuv.toBitmap(img, raw, scratch, w, h)) {
                        if (pts <= targetUs) {
                            Bitmap next = raw.copy(Bitmap.Config.ARGB_8888, false);
                            if (best != null) best.recycle();
                            best = next;
                            bestPts = pts;
                        } else {
                            // 已有前一帧时按时间距离取更近的一帧；否则只能取目标后的第一帧。
                            if (best == null || pts - targetUs < targetUs - bestPts) {
                                Bitmap next = raw.copy(Bitmap.Config.ARGB_8888, false);
                                if (best != null) best.recycle();
                                best = next;
                            }
                            outputDone = true;
                        }
                    }
                } finally {
                    if (img != null) img.close();
                    dec.releaseOutputBuffer(oi, false);
                }
                if (eos) outputDone = true;
            }
            if (best == null) throw new RuntimeException("这一帧解码失败");

            Bitmap result = best;
            best = null;
            if (rotation != 0) {
                boolean swapped = rotation == 90 || rotation == 270;
                Bitmap rotated = Bitmap.createBitmap(swapped ? h : w, swapped ? w : h,
                        Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(rotated);
                c.translate(rotated.getWidth() / 2f, rotated.getHeight() / 2f);
                c.rotate(rotation);
                c.translate(-w / 2f, -h / 2f);
                c.drawBitmap(result, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
                result.recycle();
                result = rotated;
            }
            int max = Math.max(result.getWidth(), result.getHeight());
            if (maxDimension > 0 && max > maxDimension) {
                float k = maxDimension / (float) max;
                Bitmap scaled = Bitmap.createScaledBitmap(result,
                        Math.max(1, Math.round(result.getWidth() * k)),
                        Math.max(1, Math.round(result.getHeight() * k)), true);
                if (scaled != result) result.recycle();
                result = scaled;
            }
            if (!result.isMutable()) {
                Bitmap copy = result.copy(Bitmap.Config.ARGB_8888, true);
                if (copy != null) {
                    result.recycle();
                    result = copy;
                }
            }
            return result;
        } finally {
            if (best != null) best.recycle();
            if (raw != null) raw.recycle();
            if (dec != null) {
                try { dec.stop(); } catch (Throwable ignored) { }
                try { dec.release(); } catch (Throwable ignored) { }
            }
            ex.release();
        }
    }

    private static int getInt(MediaFormat f, String key, int fallback) {
        try { return f.containsKey(key) ? f.getInteger(key) : fallback; }
        catch (Throwable ignored) { return fallback; }
    }
}
