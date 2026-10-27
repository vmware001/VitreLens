package com.vitrelens.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int PREVIEW_MAX = 1400;

    private ImageView preview, backdrop;
    private TextView hint, status, sizeInfo, btnPick, btnExport, btnDetail;
    private boolean detailMode;
    private float closeUpPan = 0.5f;
    private Bitmap lastComposed;
    private float lastTouchX;
    private LinearLayout controls;

    private Uri srcUri;
    private Meta meta;
    private Bitmap photo;        // 全尺寸可变工作位图
    private int photoW, photoH;
    private int srcW, srcH;
    private Bitmap previewBase;

    private GlassRenderer glass;
    private Composer composer;
    private String glError;

    private final WatermarkPainter.Style style = new WatermarkPainter.Style();
    private ControlPanel panel;
    private List<Runnable> refreshers;

    private final ExecutorService glQueue = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile int previewSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        backdrop = findViewById(R.id.backdrop);
        hint = findViewById(R.id.hint);
        status = findViewById(R.id.status);
        sizeInfo = findViewById(R.id.sizeInfo);
        btnPick = findViewById(R.id.btnPick);
        btnExport = findViewById(R.id.btnExport);
        btnDetail = findViewById(R.id.btnDetail);
        btnDetail.setOnClickListener(v -> {
            detailMode = !detailMode;
            btnDetail.setSelected(detailMode);
            btnDetail.setText(detailMode ? "全图" : "特写");
            if (detailMode && lastComposed != null) {
                updateCloseUp();
            } else {
                schedulePreview();
            }
        });

        // 特写模式下左右拖动可以平移到水印的任意位置
        preview.setOnTouchListener((v, e) -> {
            if (!detailMode) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastTouchX = e.getX();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lastTouchX;
                    lastTouchX = e.getX();
                    float span = Math.max(1f, v.getWidth() * 0.7f);
                    closeUpPan = Math.max(0f, Math.min(1f, closeUpPan - dx / span));
                    updateCloseUp();
                    return true;
                }
                default:
                    return true;
            }
        });
        controls = findViewById(R.id.controls);
        btnExport.setEnabled(false);

        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tvVer)).setText("v" + vn);
        } catch (Throwable ignored) {
        }

        btnPick.setOnClickListener(v -> pickImage());
        btnExport.setOnClickListener(v -> export());

        try {
            glass = new GlassRenderer(this);
        } catch (Throwable t) {
            glError = String.valueOf(t.getMessage());
            glass = null;
        }
        composer = new Composer(glass);

        try {
            WatermarkPainter.initFonts(
                    getResources().getFont(R.font.inter),
                    getResources().getFont(R.font.oswald),
                    getResources().getFont(R.font.playfair),
                    getResources().getFont(R.font.jetbrainsmono));
        } catch (Throwable ignored) {
        }

        try {
            int[] ids = {R.drawable.logo_leica, R.drawable.logo_hasselblad,
                    R.drawable.logo_zeiss, R.drawable.logo_ricoh};
            Bitmap[] bmps = new Bitmap[4];
            for (int i = 0; i < 4; i++) {
                bmps[i] = android.graphics.BitmapFactory.decodeResource(getResources(), ids[i]);
            }
            WatermarkPainter.initLogos(bmps);
        } catch (Throwable ignored) {
        }

        try {
            int[] ids = {R.drawable.logo_leica, R.drawable.logo_hasselblad,
                    R.drawable.logo_zeiss, R.drawable.logo_ricoh};
            Bitmap[] bmps = new Bitmap[4];
            for (int i = 0; i < 4; i++) {
                bmps[i] = android.graphics.BitmapFactory.decodeResource(getResources(), ids[i]);
            }
            WatermarkPainter.initLogos(bmps);
        } catch (Throwable ignored) {
        }

        panel = new ControlPanel(this, style, this::schedulePreview);
        refreshers = panel.build(controls);

        status.setText(glass == null
                ? "液态玻璃初始化失败（" + glError + "），导出将只含水印文字"
                : "支持 JPG / PNG / HEIC；导出保留 EXIF、动态照片与 HDR 增益图");

        handleIncoming(getIntent());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        glQueue.shutdownNow();
    }

    /* ── 外部入口 ── */

    private void handleIncoming(Intent it) {
        if (it == null) return;
        Uri uri = null;
        String action = it.getAction();
        if (Intent.ACTION_VIEW.equals(action)) uri = it.getData();
        else if (Intent.ACTION_SEND.equals(action)) uri = it.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            String path = it.getStringExtra("srcPath");
            if (path != null && !path.isEmpty()) uri = Uri.fromFile(new java.io.File(path));
        }
        if (uri == null) return;
        final Uri u = uri;
        int flags = it.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (flags != 0) {
            try { getContentResolver().takePersistableUriPermission(u, flags); } catch (Exception ignored) { }
        }
        loadImage(u);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadImage(uri);
        }
    }

    /* ── 载入 ── */

    private long maxPixels() {
        long heap = Runtime.getRuntime().maxMemory();
        long pixels = (long) (heap * 0.45 / 4);
        return Math.max(12L * 1000 * 1000, Math.min(pixels, 56L * 1000 * 1000));
    }

    private boolean dirty;
    private boolean decodedUpright = true;

    private void loadImage(Uri uri) {
        ProgressDialog pd = ProgressDialog.show(this, "", "读取照片…", true, false);
        new Thread(() -> {
            try {
                srcUri = uri;
                InputStream mi = getContentResolver().openInputStream(uri);
                meta = Meta.parse(mi, fileSize(uri));
                if (mi != null) mi.close();

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                InputStream in0 = getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(in0, null, bounds);
                if (in0 != null) in0.close();
                srcW = bounds.outWidth;
                srcH = bounds.outHeight;

                Bitmap bmp = decodeFull(bounds.outWidth, bounds.outHeight);
                if (photo != null && photo != bmp) photo.recycle();
                photo = bmp;
                photoW = bmp.getWidth();
                photoH = bmp.getHeight();
                dirty = false;

                if (previewBase != null) previewBase.recycle();
                double pk = Math.min(1.0, (double) PREVIEW_MAX / Math.max(photoW, photoH));
                previewBase = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, (int) (photoW * pk)),
                        Math.max(1, (int) (photoH * pk)), true);

                Bitmap bg = makeBackdrop(bmp);
                runOnUiThread(() -> {
                    hint.setVisibility(View.GONE);
                    btnExport.setEnabled(true);
                    backdrop.setImageBitmap(bg);
                    fillFromMeta();
                    pd.dismiss();
                    schedulePreview();
                });
            } catch (Throwable e) {
                final String msg = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("读取失败：" + msg);
                });
            }
        }).start();
    }

    /** 缩到极小再放大，得到一个廉价但好看的模糊背景 */
    private Bitmap makeBackdrop(Bitmap src) {
        int w = 36;
        int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap out = Bitmap.createScaledBitmap(small, w * 3, h * 3, true);
        if (small != out) small.recycle();
        return out;
    }

    private Bitmap decodeFull(int rawW, int rawH) throws Exception {
        Uri uri = srcUri;
        int orient = meta == null ? 1 : meta.orientation;
        Bitmap bmp = null;
        boolean rotatedByDecoder = true;

        try {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(true);
            });
            if (orient >= 5 && orient <= 8 && rawW > 0 && bmp.getWidth() == rawW && bmp.getHeight() == rawH) {
                rotatedByDecoder = false;
            }
        } catch (Throwable t) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in1 = getContentResolver().openInputStream(uri);
            bmp = BitmapFactory.decodeStream(in1, null, o);
            if (in1 != null) in1.close();
            if (bmp != null && orient != 1) {
                Matrix mx = new Matrix();
                switch (orient) {
                    case 2: mx.setScale(-1, 1); break;
                    case 3: mx.setRotate(180); break;
                    case 4: mx.setScale(1, -1); break;
                    case 5: mx.setRotate(90); mx.postScale(-1, 1); break;
                    case 6: mx.setRotate(90); break;
                    case 7: mx.setRotate(-90); mx.postScale(-1, 1); break;
                    case 8: mx.setRotate(-90); break;
                    default: break;
                }
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            rotatedByDecoder = true;
        }
        if (bmp == null) throw new RuntimeException("无法解码这张图片");
        decodedUpright = rotatedByDecoder;

        int w = bmp.getWidth(), h = bmp.getHeight();
        double px = (double) w * h;
        long cap = maxPixels();
        if (px > cap) {
            double k = Math.sqrt(cap / px);
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        if (!bmp.isMutable()) {
            Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, true);
            if (c != null) { bmp.recycle(); bmp = c; }
        }
        return bmp;
    }

    private long fileSize(Uri uri) {
        long len = -1;
        if ("file".equals(uri.getScheme())) {
            try { return new java.io.File(uri.getPath()).length(); } catch (Exception ignored) { }
        }
        try {
            android.database.Cursor cur = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
            if (cur != null) {
                if (cur.moveToFirst() && !cur.isNull(0)) len = cur.getLong(0);
                cur.close();
            }
        } catch (Exception ignored) { }
        if (len <= 0) {
            try {
                android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(uri, "r");
                if (fd != null) { len = fd.getLength(); fd.close(); }
            } catch (Exception ignored) { }
        }
        return len;
    }

    /* ── EXIF 回填 ── */

    private void fillFromMeta() {
        if (meta != null) {
            String name = meta.model.isEmpty() ? meta.make : meta.model;
            if (!name.isEmpty()) style.name = name;

            StringBuilder sb = new StringBuilder();
            double f = meta.focal35 > 0 && (meta.focal <= 0 || meta.focal35 / meta.focal >= 2.5)
                    ? meta.focal35 : meta.focal;
            if (f > 0) sb.append(Math.round(f)).append("mm ");
            if (meta.fNumber > 0) sb.append("ƒ/").append(trim(Math.round(meta.fNumber * 10) / 10.0)).append(" ");
            if (meta.exposure > 0) {
                if (meta.exposure >= 1) sb.append(trim(Math.round(meta.exposure * 10) / 10.0)).append("s ");
                else sb.append("1/").append(Math.round(1 / meta.exposure)).append("s ");
            }
            if (meta.iso > 0) sb.append("ISO").append(meta.iso);
            if (sb.length() > 0) style.params = sb.toString().trim();

            if (!meta.date.isEmpty()) {
                String d = meta.date;
                if (d.length() >= 19) {
                    d = d.substring(0, 10).replace(':', '.') + " " + d.substring(11, 19);
                } else {
                    d = d.replace(':', '.');
                }
                style.meta = d;
            }
            style.brand = brandFrom(name);
            style.showBrand = style.brand > 0;
        }

        boolean hasVideo = meta != null && meta.trailerVideoLength > 0;
        panel.videoOn = hasVideo;

        StringBuilder st = new StringBuilder();
        st.append(photoW).append(" × ").append(photoH);
        if (srcW > 0 && (srcW != photoW || srcH != photoH)) st.append(" · 已按内存上限缩放");
        if (meta != null && meta.motion) {
            st.append(" · 动态照片");
            if (hasVideo) {
                st.append("（视频 ")
                        .append(String.format(Locale.US, "%.1f", meta.trailerVideoLength / 1048576.0))
                        .append(" MB）");
            } else {
                st.append("（没找到视频段）");
            }
        }
        if (meta != null) {
            if (meta.hdr) {
                st.append(" · ProXDR ✓");
                if (srcW > 0 && (srcW != photoW || srcH != photoH)) {
                    st.append("（已缩放，增益图可能对不上）");
                }
            } else if (meta.motion) {
                st.append(" · 无增益图");
            } else {
                st.append(" · 无增益图（HDR 无法保留）");
                // HEIC 没有增益图时，OPPO 相册的「转换为兼容格式」会重新生成一份，
                // 先用它转成 JPEG 再导入本 App，HDR 就能保留下来。
                st.append("\n提示：先在相册「转换为兼容格式」转成 JPEG 再导入，ProXDR 才能保住");
            }
        }
        status.setText(st.toString());
        sizeInfo.setText(photoW + "×" + photoH);
        if (refreshers != null) for (Runnable r : refreshers) r.run();
    }

    void refillFromExif() {
        if (meta == null) return;
        fillFromMeta();
        schedulePreview();
    }

    private String trim(double v) {
        String s = String.valueOf(v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    private int brandFrom(String name) {
        String s = name.toLowerCase();
        if (s.contains("leica") || s.contains("xiaomi") || s.contains("徕卡")) return 1;
        if (s.contains("hasselblad") || s.contains("oppo") || s.contains("oneplus") || s.contains("哈苏")) return 2;
        if (s.contains("zeiss") || s.contains("vivo") || s.contains("蔡司")) return 3;
        if (s.contains("ricoh") || s.contains("理光")) return 4;
        return 0;
    }

    /* ── 预览 ── */

    private void schedulePreview() {
        if (previewBase == null) return;
        ui.removeCallbacks(previewTask);
        ui.postDelayed(previewTask, 70);
    }

    private final Runnable previewTask = this::runPreview;

    private void runPreview() {
        if (previewBase == null) return;
        final WatermarkPainter.Style st = style.copy();
        final int seq = ++previewSeq;
        glQueue.execute(() -> {
            try {
                Bitmap base = previewBase;
                if (base == null) return;
                Bitmap work = base.copy(Bitmap.Config.ARGB_8888, true);
                if (work == null) return;
                Bitmap out = composer.composeOn(work, work.getWidth(), work.getHeight(), st);
                lastComposed = out;
                final Bitmap show = detailMode ? closeUp(out, st) : out;
                ui.post(() -> {
                    if (seq != previewSeq) return;
                    preview.setImageBitmap(show);
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void updateCloseUp() {
        if (!detailMode || lastComposed == null) return;
        try {
            preview.setImageBitmap(closeUp(lastComposed, style));
        } catch (Throwable ignored) {
        }
    }

    /** 特写：把水印那一块裁出来放大，可用 closeUpPan 左右平移 */
    private Bitmap closeUp(Bitmap full, WatermarkPainter.Style st) {
        int W = full.getWidth(), H = full.getHeight();
        WatermarkPainter.Layout L = WatermarkPainter.layout(W, H, st);
        float u = W / 1000f;
        int pad = Math.round(L.panel.h * 1.1f) + Math.round(30 * u);
        int top = Math.max(0, Math.round(L.panel.y) - pad);
        int bottom = Math.min(H, Math.round(L.panel.y + L.panel.h) + pad);
        int cw = Math.round(L.panel.w * 0.62f);
        if (cw > W) cw = W;
        int minX = Math.max(0, Math.round(L.panel.x - 20 * u));
        int maxX = Math.max(minX, Math.min(W - cw, Math.round(L.panel.x + L.panel.w - cw + 20 * u)));
        int cx = Math.round(minX + (maxX - minX) * closeUpPan);
        if (cw < 8 || bottom - top < 8) return full;
        try {
            return Bitmap.createBitmap(full, Math.max(0, Math.min(W - cw, cx)), top, cw, bottom - top);
        } catch (Throwable t) {
            return full;
        }
    }

    /* ── 导出 ── */

    private void export() {
        if (photo == null) return;
        final WatermarkPainter.Style st = style.copy();
        final boolean wantVideo = panel.videoOn;
        ProgressDialog pd = ProgressDialog.show(this, "", "生成中…", true, false);

        glQueue.execute(() -> {
            Uri outUri = null;
            java.io.File newVideo = null;
            try {
                if (dirty || photo == null) {
                    if (photo != null) photo.recycle();
                    photo = decodeFull(srcW, srcH);
                    photoW = photo.getWidth();
                    photoH = photo.getHeight();
                    dirty = false;
                }

                long newVideoLen = 0;
                String videoWarn = null;
                if (wantVideo && meta != null && meta.trailerVideoLength > 0) {
                    try {
                        newVideo = buildWatermarkedVideo(st, pd);
                        newVideoLen = newVideo.length();
                        setPdMessage(pd, "视频好了，正在合成照片…");
                    } catch (Throwable t) {
                        Log.w("VitreLens", "视频加不上水印，改为原样保留", t);
                        newVideo = null;
                        videoWarn = videoFailReason(t);
                    }
                }

                Bitmap out = composer.composeOn(photo, photoW, photoH, st);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 22);
                if (!out.compress(Bitmap.CompressFormat.JPEG, 100, bos)) {
                    throw new RuntimeException("JPEG 编码失败");
                }
                byte[] jpeg = bos.toByteArray();
                bos = null;
                dirty = true;

                String name = "VL_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VitreLens");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                outUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (outUri == null) throw new RuntimeException("无法创建相册文件");

                OutputStream rawOs = getContentResolver().openOutputStream(outUri);
                if (rawOs == null) throw new RuntimeException("无法写入相册");
                final CountingStream os = new CountingStream(rawOs);

                if (meta != null && (meta.motion || !meta.segs.isEmpty())) {
                    InputStream trailer = getContentResolver().openInputStream(srcUri);
                    try {
                        meta.assemble(os, jpeg, trailer, decodedUpright, newVideo, newVideoLen);
                    } finally {
                        if (trailer != null) trailer.close();
                    }
                } else {
                    os.write(jpeg);
                }
                os.flush();
                os.close();
                // ── 导出后自检：把刚写出的文件重新解析一遍，确认增益图还在
                String hdrNote = null;
                try {
                    InputStream chk = getContentResolver().openInputStream(outUri);
                    if (chk != null) {
                        Meta om = Meta.parse(chk, os.count);
                        chk.close();
                        boolean srcHdr = meta != null && meta.hdr;
                        if (srcHdr) {
                            hdrNote = om.hdr ? "ProXDR ✓ 已保留" : "⚠ 增益图丢失";
                        } else {
                            hdrNote = "源图无增益图";
                        }
                    }
                } catch (Throwable t) {
                    Log.w("VitreLens", "导出自检失败", t);
                }

                final boolean hadVideo = newVideo != null;
                if (newVideo != null) { newVideo.delete(); newVideo = null; }

                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(outUri, done, null, null);

                String msg;
                if (videoWarn != null) msg = videoWarn;
                else if (hadVideo) msg = "已导出：照片 + 视频都带水印";
                else if (meta != null && meta.motion) msg = "已导出（视频未加水印）";
                else msg = "已导出 → 相册 Pictures/VitreLens";
                if (hdrNote != null) msg = msg + "\n" + hdrNote;
                Log.i("VitreLens", "导出自检: " + hdrNote + "（源图 hdr=" + (meta != null && meta.hdr) + "）");
                final String outMsg = msg;
                final boolean warn = videoWarn != null;
                ui.post(() -> {
                    pd.dismiss();
                    if (warn) {
                        new AlertDialog.Builder(this)
                                .setTitle("视频没加上水印")
                                .setMessage("照片已导出到相册 Pictures/VitreLens。\n\n原因：" + outMsg
                                        + "\n\n视频部分保持原样，没有改动。")
                                .setPositiveButton("好", null)
                                .show();
                    } else {
                        toast(outMsg);
                    }
                });
            } catch (Throwable e) {
                if (outUri != null) {
                    try { getContentResolver().delete(outUri, null, null); } catch (Exception ignored) { }
                }
                if (newVideo != null) newVideo.delete();
                Log.e("VitreLens", "导出失败", e);
                String m = e.getMessage();
                final String msg = (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
                ui.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("导出失败")
                            .setMessage(msg)
                            .setPositiveButton("好", null)
                            .show();
                });
            }
        });
    }

    /* ── 动态照片：视频打水印 ── */

    private java.io.File buildWatermarkedVideo(WatermarkPainter.Style st, ProgressDialog pd) throws Exception {
        java.io.File tmpIn = new java.io.File(getCacheDir(), "vl_src.mp4");
        java.io.File tmpOut = new java.io.File(getCacheDir(), "vl_out.mp4");
        if (tmpOut.exists() && !tmpOut.delete()) throw new RuntimeException("清不掉上次的临时文件");

        setPdMessage(pd, "正在取出视频段…");
        InputStream in = getContentResolver().openInputStream(srcUri);
        if (in == null) throw new RuntimeException("打不开原图");
        try {
            VideoWatermark.extractRange(in, meta.eoiEnd + meta.trailerLeadLength,
                    meta.trailerVideoLength, tmpIn);
        } finally {
            in.close();
        }

        try {
            VideoWatermark.transcode(this, tmpIn, tmpOut, composer, st,
                    (frame, total, stage) -> {
                        if (frame % 3 == 0 || frame >= total) {
                            setPdMessage(pd, stage + " " + frame + "/" + total);
                        }
                    });
        } catch (Throwable t) {
            Log.e("VitreLens", "视频转码失败: " + t, t);
            tmpOut.delete();
            String m = t.getMessage();
            throw new RuntimeException(m == null || m.trim().isEmpty()
                    ? t.getClass().getSimpleName() : m);
        } finally {
            tmpIn.delete();
        }
        return tmpOut;
    }

    private static String videoFailReason(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = "";
        m = m.trim();
        if (m.isEmpty() || m.equals("IllegalStateException") || m.equals("null")) {
            return "这台设备的解码器解不了这段视频（多半是 10bit / HDR 视频）";
        }
        if (m.contains("没有可用的视频解码器")) return "系统里没有能解 HEVC 的解码器";
        return m;
    }

    private void setPdMessage(ProgressDialog pd, String msg) {
        ui.post(() -> {
            try {
                pd.setMessage(msg);
            } catch (Exception ignored) { }
        });
    }

    /** 统计写入字节数，供导出后自检用 */
    private static class CountingStream extends java.io.FilterOutputStream {
        long count;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override public void write(int b) throws java.io.IOException {
            out.write(b);
            count++;
        }

        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
