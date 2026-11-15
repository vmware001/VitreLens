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

/**
 * 单帧精确解码：用于普通视频的帧滑块预览，不依赖 MediaMetadataRetriever。
 *
 * <p>帧滑块每拖一格都会调用一次，原来的实现有三处很贵：
 * <ol>
 *   <li>每次都新建 MediaExtractor + MediaCodec（configure/start 在 4K 上要 50~150ms）；</li>
 *   <li>先按源尺寸做完整 YUV→ARGB（4K 每帧 8.3MP），再缩小到预览尺寸；</li>
 *   <li>GOP 里每个候选帧都 {@code raw.copy()} 一张全尺寸位图（4K 每张 33MB）。</li>
 * </ol>
 * 现在 extractor/decoder 按文件缓存复用，拖动时只 seek + flush；YUV 直接用
 * {@link YuvConverter#toBitmapScaled} 解到预览尺寸；候选帧用两张复用位图交替承接，
 * 不再拷贝。4K 下每格从 200~600ms 降到几十毫秒。
 *
 * <p>返回的位图是解码器内部复用的缓冲：调用方可以一直持有到下一次解码，
 * 但**不要 recycle**（下一次解码会覆盖它）。
 */
class VideoFrameDecoder {

    /** 按文件复用；同一时间只有一个视频在预览，所以静态单例足够 */
    private static Session session;

    static synchronized Bitmap decode(File file, long targetUs, int maxDimension) throws Exception {
        String key = file.getAbsolutePath() + '|' + file.length() + '|' + file.lastModified();
        Session s = session;
        if (s == null || !s.key.equals(key)) {
            if (s != null) s.close();
            s = new Session(file, key);
            session = s;
        }
        try {
            return s.decodeFrame(targetUs, maxDimension);
        } catch (Throwable t) {
            // flush/seek 在个别解码器上会把复用状态弄坏；丢掉重建一次再试，失败才真的报错。
            try { s.close(); } catch (Throwable ignored) { }
            session = null;
            Session fresh = new Session(file, key);
            session = fresh;
            try {
                return fresh.decodeFrame(targetUs, maxDimension);
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                throw t2;
            }
        }
    }

    /** 换视频/退出视频预览时释放缓存的解码器与缓冲 */
    static synchronized void close() {
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) { }
        }
        session = null;
    }

    private static class Session {
        final String key;
        final MediaExtractor ex = new MediaExtractor();
        final MediaCodec dec;
        final YuvConverter yuv;
        final int srcW, srcH, rotation;
        final boolean swapped;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        /** YUV 转换的 int[] 划转缓冲，按目标尺寸复用 */
        int[] scratch;
        /** 交替承接候选帧的复用位图：best 不在用的那只用来写下一次解码结果 */
        Bitmap bufA, bufB;
        /** 有旋转时的中间缓冲（源方向） */
        Bitmap convertBuf;
        Canvas convertCanvas;
        int slotConvW = -1, slotConvH = -1, slotOutW = -1, slotOutH = -1;

        Session(File file, String key) throws Exception {
            this.key = key;
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                ex.release();
                throw new RuntimeException("视频里没有画面轨道");
            }
            MediaFormat fmt = ex.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            srcW = getInt(fmt, MediaFormat.KEY_WIDTH, 0);
            srcH = getInt(fmt, MediaFormat.KEY_HEIGHT, 0);
            rotation = getInt(fmt, "rotation-degrees", 0);
            swapped = rotation == 90 || rotation == 270;
            if (srcW <= 0 || srcH <= 0) {
                ex.release();
                throw new RuntimeException("读不到视频尺寸");
            }
            yuv = new YuvConverter(
                    getInt(fmt, MediaFormat.KEY_COLOR_STANDARD, 0),
                    getInt(fmt, MediaFormat.KEY_COLOR_TRANSFER, 0),
                    getInt(fmt, MediaFormat.KEY_COLOR_RANGE, 0));
            ex.selectTrack(track);
            dec = VideoWatermark.openDecoder(mime, fmt);
            dec.start();
        }

        /** 上次返回帧的时间戳；只有目标在它后面时才可能顺序前进 */
        long lastReturnedPts = Long.MIN_VALUE;
        boolean positionValid;
        boolean atEos;
        /** 允许顺序前进的最大跨度：跨度太大时重新 seek 反而更省 */
        private static final long FORWARD_MAX_US = 4_000_000L;

        Bitmap decodeFrame(long targetUs, int maxDimension) throws Exception {
            // 帧滑块大多数时候是连续向前拖：这时不要 seek+flush 从关键帧重放，
            // 直接从当前解码位置继续解新增的几帧（313 帧长 GOP 视频上，单次跳帧从 2s 级降到几十 ms）。
            if (positionValid && !atEos && targetUs > lastReturnedPts
                    && targetUs - lastReturnedPts <= FORWARD_MAX_US) {
                Bitmap forward = decodeLoop(targetUs, maxDimension, false);
                if (forward != null) return forward;
            }
            return decodeLoop(targetUs, maxDimension, true);
        }

        /**
         * @param seek true = 先 seek 到目标前一个关键帧并 flush；false = 从当前解码位置继续往前解
         * @return 解码结果；顺序模式下若发现位置已越过目标（需要 seek）返回 null，由调用方重来
         */
        private Bitmap decodeLoop(long targetUs, int maxDimension, boolean seek) throws Exception {
            if (seek) {
                ex.seekTo(Math.max(0, targetUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                dec.flush();
                lastReturnedPts = Long.MIN_VALUE;
                atEos = false;
            }
            positionValid = true;

            Bitmap best = null;
            long bestPts = Long.MIN_VALUE;
            boolean inputDone = false, outputDone = false;
            int guard = 0;
            // 两种模式都只多喂一小段：喂太远会让 extractor 跑到目标前面，下一次小步前进只能重新 seek
            long maxFeedAheadUs = 400_000L;
            long feedAheadUs = seek ? 200_000L : 34_000L;

            while (!outputDone) {
                if (++guard > 8000) throw new RuntimeException("单帧解码超时");

                // 先把 decoder 里现成的输出取完，避免还有帧没出来时又喂进后面的样本
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                if (oi >= 0) {
                    long pts = bi.presentationTimeUs;
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    Image img = null;
                    try {
                        img = dec.getOutputImage(oi);
                        // 顺序模式下已经返回过的帧不再考虑，避免重复转换
                        if (img != null && pts > lastReturnedPts) {
                            boolean take;
                            if (pts <= targetUs) {
                                // 目标是「不大于 target 的最后一帧」，每出现一帧都更好
                                take = true;
                            } else {
                                take = best == null || (pts - targetUs) < (targetUs - bestPts);
                            }
                            if (take) {
                                Bitmap next = convertFrame(img, best, maxDimension);
                                if (next != null) {
                                    best = next;
                                    bestPts = pts;
                                }
                            }
                        }
                    } finally {
                        if (img != null) img.close();
                        dec.releaseOutputBuffer(oi, false);
                    }
                    if (pts > targetUs && best != null) outputDone = true;
                    if (eos) {
                        atEos = true;
                        outputDone = true;
                    }
                    continue;
                }

                // 没有输出：需要继续喂输入
                if (inputDone) {
                    if (best != null) {
                        outputDone = true;
                        continue;
                    }
                    if (!seek) return null;   // 顺序位置已越过目标，让调用方重新 seek
                    throw new RuntimeException("这一帧解码失败");
                }
                long nextPts = ex.getSampleTime();
                if (nextPts < 0) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else if (best != null) {
                        outputDone = true;
                    }
                    continue;
                }
                boolean canFeed = nextPts <= targetUs + feedAheadUs || feedAheadUs >= maxFeedAheadUs;
                if (canFeed) {
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
                    continue;
                }
                // 已经喂到上限：有候选就收工；还没有任何输出就先放宽上限（解码器需要管线填充）
                if (best != null) {
                    outputDone = true;
                } else {
                    feedAheadUs = Math.min(feedAheadUs * 2, maxFeedAheadUs);
                }
            }

            if (best == null) {
                if (!seek) return null;   // 顺序位置已越过目标，让调用方重新 seek
                throw new RuntimeException("这一帧解码失败");
            }
            lastReturnedPts = bestPts;
            return best;
        }

        /**
         * 把一帧 YUV 解到预览尺寸，返回写好的复用位图；失败返回 null。
         * best 是当前最优候选，不能覆盖它 —— 用另一只槽承接。
         */
        private Bitmap convertFrame(Image img, Bitmap best, int maxDimension) {
            int iw = img.getWidth(), ih = img.getHeight();
            if (iw <= 0 || ih <= 0) return null;

            int upW = swapped ? ih : iw;
            int upH = swapped ? iw : ih;
            float k = 1f;
            int maxSide = Math.max(upW, upH);
            if (maxDimension > 0 && maxSide > maxDimension) {
                k = maxDimension / (float) maxSide;
            }
            int outW = Math.max(1, Math.round(upW * k));
            int outH = Math.max(1, Math.round(upH * k));
            int convW = swapped ? outH : outW;   // 旋转前的目标尺寸
            int convH = swapped ? outW : outH;

            try {
                ensureSlots(convW, convH, outW, outH);
                Bitmap dst = (best == bufA) ? bufB : bufA;
                if (dst == null) return null;

                Bitmap target = (rotation == 0) ? dst : convertBuf;
                if (target == null || target.isRecycled()) return null;
                if (scratch == null || scratch.length < convW * convH) {
                    scratch = new int[convW * convH];
                }
                if (!yuv.toBitmapScaled(img, target, scratch, iw, ih, convW, convH)) return null;

                if (rotation != 0) {
                    // convertBuf 已经是「缩放后的源方向」，绕着画布中心旋转到 upright
                    if (convertCanvas == null) convertCanvas = new Canvas();
                    convertCanvas.setBitmap(dst);
                    convertCanvas.drawColor(0xFF000000);
                    convertCanvas.save();
                    convertCanvas.translate(outW / 2f, outH / 2f);
                    convertCanvas.rotate(rotation);
                    convertCanvas.translate(-convW / 2f, -convH / 2f);
                    convertCanvas.drawBitmap(convertBuf, 0, 0, paint);
                    convertCanvas.restore();
                }
                return dst;
            } catch (Throwable t) {
                return null;
            }
        }

        /** 保证两只候选槽 + 旋转中间缓冲的尺寸正确；视频尺寸不变时不会重复分配。 */
        private void ensureSlots(int convW, int convH, int outW, int outH) {
            boolean same = slotConvW == convW && slotConvH == convH
                    && slotOutW == outW && slotOutH == outH;
            if (same && bufA != null && !bufA.isRecycled()
                    && bufB != null && !bufB.isRecycled()
                    && (rotation == 0 || (convertBuf != null && !convertBuf.isRecycled()))) {
                return;
            }
            recycleQuietly(bufA);
            recycleQuietly(bufB);
            recycleQuietly(convertBuf);
            bufA = bufB = convertBuf = null;
            slotConvW = convW;
            slotConvH = convH;
            slotOutW = outW;
            slotOutH = outH;
            if (rotation == 0) {
                bufA = Bitmap.createBitmap(convW, convH, Bitmap.Config.ARGB_8888);
                bufB = Bitmap.createBitmap(convW, convH, Bitmap.Config.ARGB_8888);
            } else {
                bufA = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                bufB = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                convertBuf = Bitmap.createBitmap(convW, convH, Bitmap.Config.ARGB_8888);
            }
        }

        private static void recycleQuietly(Bitmap b) {
            if (b != null && !b.isRecycled()) {
                try { b.recycle(); } catch (Throwable ignored) { }
            }
        }

        void close() {
            try { dec.stop(); } catch (Throwable ignored) { }
            try { dec.release(); } catch (Throwable ignored) { }
            try { ex.release(); } catch (Throwable ignored) { }
            recycleQuietly(bufA);
            recycleQuietly(bufB);
            recycleQuietly(convertBuf);
            bufA = bufB = null;
            convertBuf = null;
            scratch = null;
        }
    }

    private static int getInt(MediaFormat f, String key, int fallback) {
        try { return f.containsKey(key) ? f.getInteger(key) : fallback; }
        catch (Throwable ignored) { return fallback; }
    }
}
