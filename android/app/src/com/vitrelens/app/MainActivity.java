package com.vitrelens.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
    private static final int REQ_LOGO = 1002;
    private static final int PREVIEW_MAX = 1400;

    private ImageView preview, backdrop;
    /** 整屏背景位图，界面上的液态玻璃面板要拿它取样 */
    private Bitmap backdropBmp;
    /** 屏幕尺寸的清晰背景（centerCrop 过），界面玻璃拿它取样：
     *  背景本身已经模糊过，玻璃再糊一遍就没有细节可折射了，等于白做。 */
    private Bitmap backdropSharp;
    private TextView hint, status, sizeInfo, btnPick, btnExport, btnDetail;
    private boolean detailMode;
    private float closeUpPan = 0.5f;
    private String versionName = "1.0";
    private boolean checkingUpdate;
    // 拖动液态玻璃的形变（-1~1），由阻尼弹簧弹回 0；导出永远用 0
    private float jellyX, jellyY, jellyVX, jellyVY, jellyTX, jellyTY;
    private float jellyDownX, jellyDownY;
    private boolean jellyActive, jellyRunning;
    private long jellyLast;
    private volatile boolean previewBusy;
    private Bitmap lastComposed;
    private TextureView videoView;
    private VideoMarkView videoMark;
    private MediaPlayer player;
    private boolean videoPlaying;
    private TextView btnPlay;
    private Bitmap cropBuf;          // 特写裁剪的复用缓冲，拖动时不重新分配
    private Canvas cropCanvas;
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
        videoView = findViewById(R.id.videoView);
        videoMark = findViewById(R.id.videoMark);
        btnPlay = findViewById(R.id.btnPlay);
        btnPlay.setOnClickListener(v -> toggleVideo());

        btnDetail = findViewById(R.id.btnDetail);
        btnDetail.setOnClickListener(v -> {
            detailMode = !detailMode;
            btnDetail.setSelected(detailMode);
            btnDetail.setText(detailMode ? "全图" : "特写");
            if (videoPlaying) {
                videoMark.setCloseUp(detailMode, closeUpPan);
                syncVideoTransform();
            }
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
                    if (videoPlaying) {
                        videoMark.setCloseUp(detailMode, closeUpPan);
                        syncVideoTransform();
                    }
                    updateCloseUp();
                    return true;
                }
                default:
                    return true;
            }
        });
        controls = findViewById(R.id.controls);

        // 预览卡片也用同一套液态玻璃背景
        findViewById(R.id.stage).setBackground(
                new GlassCardDrawable(getResources().getDisplayMetrics().density * 22f, 0x2BFFFFFF));

        // 在预览上拖动：玻璃被「拉」出形变，松手弹回 —— 水印本身不会真的移动
        findViewById(R.id.stage).setOnTouchListener((v, e) -> {
            if (previewBase == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    jellyDownX = e.getX();
                    jellyDownY = e.getY();
                    jellyActive = true;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!jellyActive) return false;
                    float scale = viewToImageScale();
                    if (scale <= 0f) return false;
                    float dx = (e.getX() - jellyDownX) / scale;
                    float dy = (e.getY() - jellyDownY) / scale;
                    // 归一化到「0.6 个水印宽度」：拖到这个距离就是满形变
                    float denom = Math.max(1f,
                            previewBase.getWidth() * (style.widthPct / 100f) * 0.6f);
                    jellyTX = Math.max(-1f, Math.min(1f, dx / denom));
                    jellyTY = Math.max(-1f, Math.min(1f, dy / denom));
                    startJelly();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!jellyActive) return false;
                    jellyActive = false;
                    jellyTX = 0f;
                    jellyTY = 0f;
                    startJelly();
                    return true;
                default:
                    return false;
            }
        });
        btnExport.setEnabled(false);

        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            versionName = "1.0";
        }
        TextView ver = findViewById(R.id.tvVer);
        ver.setText("v" + versionName);
        // 点版本号手动检查更新（不额外占界面）
        ver.setOnClickListener(v -> checkUpdate(false));

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
                    R.drawable.logo_hasselblad_h, R.drawable.logo_zeiss,
                    R.drawable.logo_ricoh, R.drawable.logo_apple};
            Bitmap[] bmps = new Bitmap[ids.length];
            for (int i = 0; i < ids.length; i++) {
                bmps[i] = android.graphics.BitmapFactory.decodeResource(getResources(), ids[i]);
            }
            WatermarkPainter.initLogos(bmps);
        } catch (Throwable ignored) {
        }

        loadCustomLogo();
        panel = new ControlPanel(this, style, this::schedulePreview);
        refreshers = panel.build(findViewById(R.id.rail), controls, findViewById(R.id.scroll));

        status.setText(glass == null
                ? "液态玻璃初始化失败（" + glError + "），导出将只含水印文字"
                : "支持 JPG / PNG / HEIC；导出保留 EXIF、动态照片与 HDR 增益图");

        handleIncoming(getIntent());

        // 启动 3 秒后静默查一次；拿不到网络就直接放弃，不弹任何东西
        if (Updater.shouldAutoCheck(this)) {
            ui.postDelayed(() -> checkUpdate(true), 3000);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 先让渲染线程把 EGL 与 GL 资源还回去，再关队列。
        // 之前直接 shutdownNow()，GlassRenderer 没有 release()，每重建一次 Activity
        // 就漏一整套 EGL 上下文（切换深色模式、旋转都会触发重建）。
        if (glass != null) {
            try {
                glQueue.execute(glass::release);
            } catch (Throwable ignored) {
            }
        }
        glQueue.shutdown();
        try {
            glQueue.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        recycleQuietly(lastComposed);
        recycleQuietly(cropBuf);
        recycleQuietly(previewBase);
        recycleQuietly(photo);
        lastComposed = null;
        cropBuf = null;
        previewBase = null;
        photo = null;
    }

    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try {
                b.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ── 实况照片的动态预览 ── */

    private void toggleVideo() {
        if (videoPlaying) {
            stopVideo();
            return;
        }
        if (meta == null || meta.trailerVideoLength <= 0 || srcUri == null) {
            toast("这张照片没有动态部分");
            return;
        }
        final Uri uri = srcUri;
        final long skip = meta.eoiEnd + meta.trailerLeadLength;
        final long len = meta.trailerVideoLength;
        toast("正在准备动态部分…");
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "vl_preview.mp4");
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new RuntimeException("无法打开源文件");
                try {
                    VideoWatermark.extractRange(in, skip, len, tmp);
                } finally {
                    in.close();
                }
                runOnUiThread(() -> startPlayer(tmp));
            } catch (Throwable t) {
                runOnUiThread(() -> toast("读取动态部分失败：" + t.getMessage()));
            }
        }).start();
    }

    private void startPlayer(File f) {
        try {
            stopVideo();
            videoView.setVisibility(View.VISIBLE);
            videoMark.setVisibility(View.VISIBLE);
            preview.setVisibility(View.GONE);
            hint.setVisibility(View.GONE);
            videoMark.setStyle(style);
            videoMark.setCloseUp(detailMode, closeUpPan);

            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setLooping(true);
            player.setOnPreparedListener(mp -> {
                // SurfaceTexture 要等 View 真正挂上才有，这里再取
                android.graphics.SurfaceTexture st = videoView.getSurfaceTexture();
                if (st == null) {
                    toast("视频预览初始化失败");
                    stopVideo();
                    return;
                }
                mp.setSurface(new Surface(st));
                videoMark.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                videoPlaying = true;
                btnPlay.setText("停止");
                btnPlay.setSelected(true);
                syncVideoTransform();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                toast("播放失败（" + what + "/" + extra + "）");
                stopVideo();
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            toast("播放失败：" + t.getMessage());
            stopVideo();
        }
    }

    /** 让 TextureView 的画面跟水印层用同一套缩放/平移 */
    private void syncVideoTransform() {
        if (videoView == null || videoMark == null) return;
        android.graphics.Matrix m = new android.graphics.Matrix();
        videoMark.applyTo(m, videoView.getWidth(), videoView.getHeight());
        videoView.setTransform(m);
    }

    private void stopVideo() {
        videoPlaying = false;
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        videoView.setVisibility(View.GONE);
        videoMark.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        btnPlay.setText("播放");
        btnPlay.setSelected(false);
    }

    /* ── 自动更新 ── */

    private void checkUpdate(boolean silent) {
        if (checkingUpdate) return;
        checkingUpdate = true;
        if (!silent) toast("正在检查更新…");
        new Thread(() -> {
            try {
                Updater.Info info = Updater.fetchLatest();
                Updater.markChecked(this);
                final boolean newer = Updater.isNewer(info.version, versionName);
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    if (newer) showUpdateDialog(info);
                    else if (!silent) toast("已是最新版本 v" + versionName);
                });
            } catch (Throwable t) {
                Updater.log("检查更新失败：" + t);
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    if (!silent) toast("检查更新失败：" + t.getMessage());
                });
            }
        }).start();
    }

    private void showUpdateDialog(Updater.Info info) {
        StringBuilder m = new StringBuilder();
        m.append("当前 v").append(versionName).append("  →  新版本 v").append(info.version);
        if (Updater.hasBundledLogos(this)) {
            m.append("\n\n注意：GitHub 上的安装包不含品牌官方图标，");
            m.append("更新后水印里的品牌标识会变回内置矢量图形。");
        }
        String notes = plainNotes(info.notes);
        // 正文第一行通常就是 "VitreLens 1.39"，标题里已经有了，去掉
        if (notes.startsWith("VitreLens")) {
            int nl = notes.indexOf('\n');
            if (nl > 0) notes = notes.substring(nl + 1).trim();
        }
        if (notes.length() > 400) notes = notes.substring(0, 400) + "…";
        if (!notes.isEmpty()) m.append("\n\n").append(notes);

        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(m.toString())
                .setPositiveButton("下载并安装", (d, w) -> startUpdate(info))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void startUpdate(Updater.Info info) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("下载 v" + info.version);
        pd.setMessage("正在连接…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        // GitHub 的直链经常不给 Content-Length，先转圈，拿到长度再切成进度条
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            try {
                final java.io.File apk = Updater.download(this, info, (pct, got, total) ->
                        runOnUiThread(() -> {
                            if (total > 0 && pct >= 0) {
                                if (pd.isIndeterminate()) pd.setIndeterminate(false);
                                pd.setProgress(pct);
                                pd.setMessage(fmtSize(got) + " / " + fmtSize(total));
                            } else {
                                pd.setMessage("已下载 " + fmtSize(got));
                            }
                        }));
                runOnUiThread(() -> {
                    pd.dismiss();
                    try {
                        if (!Updater.install(this, apk)) {
                            new AlertDialog.Builder(this)
                                    .setTitle("还差一步")
                                    .setMessage("系统已经打开「安装未知应用」的设置页。\n"
                                            + "给 VitreLens 打开权限后，回来再点一次版本号即可。")
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    } catch (Throwable t) {
                        showUpdateError("安装失败", t);
                    }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    showUpdateError("下载失败", t);
                });
            }
        }).start();
    }

    private void showUpdateError(String title, Throwable t) {
        String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg + "\n\n如果一直失败（国内访问 GitHub 的下载服务器常常不通），"
                        + "可以到 Releases 页面用浏览器下载。")
                .setPositiveButton("用浏览器打开", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/vmware001/VitreLens/releases/latest")));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    /** Release 正文是 Markdown，直接塞进对话框会满屏 ## 和 **，这里去掉最基本的标记 */
    private static String plainNotes(String md) {
        if (md == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String raw : md.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            l = l.replaceAll("^#{1,6}\\s*", "");
            l = l.replaceAll("^[-*+]\\s+", "· ");
            l = l.replaceAll("^\\d+\\.\\s+", "");
            l = l.replaceAll("^>\\s*", "");
            l = l.replace("**", "").replace("__", "").replace("`", "");
            sb.append(l).append('\n');
        }
        return sb.toString().trim();
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
        // ACTION_OPEN_DOCUMENT 打开的是系统的「文件」界面（按目录浏览），
        // 挑照片要多点好几层。改用相册的 ACTION_PICK：直接是缩略图网格。
        // 没有相册应用时再退回 ACTION_GET_CONTENT（仍然只列图片）。
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (i.resolveActivity(getPackageManager()) == null) {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            toast("没有找到可用的图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadImage(uri);
        } else if (req == REQ_LOGO && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) importCustomLogo(uri);
        }
    }

    /* ── 自定义图标 ── */

    void pickCustomLogo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_LOGO);
    }

    private void loadCustomLogo() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "custom_logo.png");
            if (f.isFile()) {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) WatermarkPainter.setCustomLogo(b);
            }
        } catch (Throwable ignored) {
        }
    }

    private void importCustomLogo(Uri uri) {
        ProgressDialog pd = ProgressDialog.show(this, "", "读取图标…", true, false);
        new Thread(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                InputStream in0 = getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(in0, null, bounds);
                if (in0 != null) in0.close();

                int max = 720;
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= max || bounds.outHeight / (sample * 2) >= max) sample *= 2;

                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                InputStream in1 = getContentResolver().openInputStream(uri);
                Bitmap b = BitmapFactory.decodeStream(in1, null, o);
                if (in1 != null) in1.close();
                if (b == null) throw new RuntimeException("无法解码这张图片");

                // 存一份到私有目录，下次启动还能用
                java.io.File f = new java.io.File(getFilesDir(), "custom_logo.png");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();

                final Bitmap src = b;
                runOnUiThread(() -> {
                    pd.dismiss();
                    WatermarkPainter.setCustomLogo(src);
                    style.brand = 7;
                    style.showBrand = true;
                    if (refreshers != null) for (Runnable r : refreshers) r.run();
                    schedulePreview();
                    toast("已设为自定义图标");
                });
            } catch (Throwable e) {
                final String msg = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("导入失败：" + msg);
                });
            }
        }).start();
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
                    backdropBmp = bg;
                    backdropSharp = makeSharpBackdrop(bmp);
                    backdrop.setImageBitmap(bg);
                    applyUiGlass();
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
    /**
     * 整屏背景：按屏幕宽度出一张真图，再打一层**轻微**高斯。
     *
     * <p>原来是缩到 140px 宽再放大 3 倍 —— 那根本不是模糊，是把图压成马赛克，
     * 放到 720 宽屏幕上每个像素铺 5 个点，所以糊成一片。
     * 现在保留分辨率（最多 900 宽），只叠 sigma≈4 的轻模糊，背景还能看出是什么。
     */
    private Bitmap makeBackdrop(Bitmap src) {
        int w = Math.min(900, Math.max(360, getResources().getDisplayMetrics().widthPixels));
        int h = Math.max(1, Math.round(w * (float) src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);

        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        // 3 次盒式宽度 2r+1 时 sigma ≈ r，这里取 4 左右
        Blur.boxBlur(px, w, h, Math.max(3, Math.round(w / 120f)));

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
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

        // 有视频段才显示「播放」，否则点了也没意义
        if (btnPlay != null) btnPlay.setVisibility(hasVideo ? View.VISIBLE : View.GONE);

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
                st.append(" · 照片 ProXDR ✓");
                if (srcW > 0 && (srcW != photoW || srcH != photoH)) {
                    st.append("（已缩放，增益图可能对不上）");
                }
            } else if (meta.motion) {
                st.append(" · 照片无增益图");
            } else {
                st.append(" · 无增益图（HDR 无法保留）");
                // HEIC 没有增益图时，OPPO 相册的「转换为兼容格式」会重新生成一份，
                // 先用它转成 JPEG 再导入本 App，HDR 就能保留下来。
                st.append("\n提示：先在相册「转换为兼容格式」转成 JPEG 再导入，ProXDR 才能保住");
            }
            if (meta.motion) {
                // 视频轨的 HDR 是另一回事：转码时统一色调映射成 SDR，避免偏色发灰
                st.append(" · 视频轨转 SDR");
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
        if (s.contains("zeiss") || s.contains("vivo") || s.contains("蔡司")) return 4;
        if (s.contains("ricoh") || s.contains("理光")) return 5;
        if (s.contains("apple") || s.contains("iphone")) return 6;
        return 0;
    }

    /* ── 预览 ── */

    /**
     * 把界面上的玻璃面板换成「真」液态玻璃：取背景图对应区域的像素，
     * 走和导出完全相同的那套着色器渲染，再作为背景贴回去。
     *
     * <p>背景是静态位图，所以每个面板只需要算一次（换照片时重算）。
     * 界面上的文字/控件画在这层之上，所以看到的是「背景被玻璃折射」的效果。
     */
    /** 屏幕尺寸的清晰背景：centerCrop 到整屏，给界面玻璃取样 */
    private Bitmap makeSharpBackdrop(Bitmap src) {
        int vw = Math.max(1, backdrop.getWidth());
        int vh = Math.max(1, backdrop.getHeight());
        float s = Math.max(vw / (float) src.getWidth(), vh / (float) src.getHeight());
        int w = Math.max(vw, Math.round(src.getWidth() * s));
        int h = Math.max(vh, Math.round(src.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap out = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(scaled, (vw - w) / 2f, (vh - h) / 2f, null);
        if (scaled != out) scaled.recycle();
        return out;
    }

    private void applyUiGlass() {
        if (glass == null || backdropBmp == null) return;
        // 左栏真正显示的是 rail 里的那颗胶囊，玻璃要贴在它上面
        View railBox = findViewById(R.id.rail);
        View pill = (railBox instanceof ViewGroup && ((ViewGroup) railBox).getChildCount() > 0)
                ? ((ViewGroup) railBox).getChildAt(0) : railBox;
        renderGlassBg(pill, 22f);
        renderGlassBg(findViewById(R.id.scroll), 22f);
    }

    private void renderGlassBg(final View v, final float radiusDp) {
        if (glass == null || backdropBmp == null || backdropBmp.isRecycled()) return;
        if (v == null || v.getWidth() < 8 || v.getHeight() < 8) {
            Log.w("VitreLens", "UI 玻璃跳过：视图尺寸为 0");
            return;
        }

        final int vw = v.getWidth(), vh = v.getHeight();
        int[] bLoc = new int[2], vLoc = new int[2];
        backdrop.getLocationInWindow(bLoc);
        v.getLocationInWindow(vLoc);
        float vx = vLoc[0] - bLoc[0];
        float vy = vLoc[1] - bLoc[1];

        // 清晰背景已经是屏幕尺寸，视图坐标就是位图坐标
        Bitmap srcBmp = (backdropSharp != null && !backdropSharp.isRecycled())
                ? backdropSharp : backdropBmp;
        int bx = Math.max(0, Math.round(vx));
        int by = Math.max(0, Math.round(vy));
        int bw = Math.min(vw, srcBmp.getWidth() - bx);
        int bh = Math.min(vh, srcBmp.getHeight() - by);
        if (bw < 8 || bh < 8) return;

        Bitmap base;
        try {
            Bitmap crop = Bitmap.createBitmap(srcBmp, bx, by, bw, bh);
            base = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(base);
            bc.drawBitmap(crop, null, new Rect(0, 0, vw, vh), new Paint(Paint.FILTER_BITMAP_FLAG));
            crop.recycle();
        } catch (Throwable t) {
            return;
        }
        // 和背景那层压暗对齐（#B307080B 是全黑度，玻璃自己要更亮一点才像浮在上面）
        new Canvas(base).drawColor(0xA607080B);

        final WatermarkPainter.Style ust = uiGlassStyle();
        final float radiusPx = radiusDp * getResources().getDisplayMetrics().density;

        glQueue.execute(() -> {
            Bitmap out = null;
            try {
                WatermarkPainter.Panel p = new WatermarkPainter.Panel();
                p.x = 0;
                p.y = 0;
                p.w = vw;
                p.h = vh;
                p.r = radiusPx;
                p.bevel = vh * 0.5f * (ust.depthPct / 100f);
                p.edgeW = p.bevel;
                out = glass.render(base, 0, 0, vw, vh, p, ust, true);
            } catch (Throwable t) {
                Log.w("VitreLens", "UI 玻璃渲染失败", t);
            }
            final Bitmap res = out;
            ui.post(() -> {
                if (res == null) return;
                android.graphics.drawable.BitmapDrawable d =
                        new android.graphics.drawable.BitmapDrawable(getResources(), res);
                v.setBackground(d);
            });
        });
    }

    /** 界面玻璃用的参数：磨砂、低反射、折射克制 —— 和导出用的预设是同一套字段 */
    private WatermarkPainter.Style uiGlassStyle() {
        WatermarkPainter.Style s = new WatermarkPainter.Style();
        s.ior = 1.46f;
        s.radiusPct = 100f;
        s.depthPct = 18f;
        s.thicknessPct = 14f;
        s.roughPct = 52f;
        s.dispPct = 5f;
        s.reflPct = 22f;
        s.transPct = 97f;
        s.glossPct = 24f;
        s.shadowPct = 0f;
        s.whitePct = 0f;
        s.warpPct = 0f;
        s.edgePct = 120f;
        s.segments = 64;
        s.tintOn = true;
        return s;
    }

    /** 预览上显示的位图相对视图的缩放比（fitCenter），用来把手指位移换算成图像像素 */
    private float viewToImageScale() {
        Bitmap b = detailMode ? cropBuf : lastComposed;
        if (b == null || b.isRecycled()) return 0f;
        int vw = preview.getWidth(), vh = preview.getHeight();
        if (vw == 0 || vh == 0) return 0f;
        return Math.min(vw / (float) b.getWidth(), vh / (float) b.getHeight());
    }

    private void startJelly() {
        if (jellyRunning) return;
        jellyRunning = true;
        jellyLast = 0;
        ui.post(jellyTick);
    }

    /** 阻尼弹簧：k 决定回弹快慢，c 决定过冲多少（偏小一点才有「果冻」的余韵） */
    private final Runnable jellyTick = this::jellyFrame;

    private void jellyFrame() {
        long now = System.nanoTime();
        float dt = jellyLast == 0 ? 0.016f : Math.min(0.032f, (now - jellyLast) / 1e9f);
        jellyLast = now;
        final float k = 620f, c = 24f;
        jellyVX += ((jellyTX - jellyX) * k - jellyVX * c) * dt;
        jellyVY += ((jellyTY - jellyY) * k - jellyVY * c) * dt;
        jellyX += jellyVX * dt;
        jellyY += jellyVY * dt;

        boolean settled = !jellyActive
                && Math.abs(jellyTX - jellyX) < 0.004f && Math.abs(jellyVX) < 0.02f
                && Math.abs(jellyTY - jellyY) < 0.004f && Math.abs(jellyVY) < 0.02f;
        if (settled) {
            jellyX = jellyTX;
            jellyY = jellyTY;
            jellyVX = jellyVY = 0f;
            jellyRunning = false;
            jellyLast = 0;
            runPreviewNow();
            return;
        }
        runPreviewNow();
        ui.postDelayed(jellyTick, 16);
    }

    /** 拖动时每帧都要重画，不能等 schedulePreview 的 70ms 防抖 */
    private void runPreviewNow() {
        if (previewBusy) return;
        ui.removeCallbacks(previewTask);
        ui.post(previewTask);
    }

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
        final float jx = jellyX, jy = jellyY;
        previewBusy = true;
        glQueue.execute(() -> {
            Bitmap out = null;
            try {
                Bitmap base = previewBase;
                if (base != null) {
                    Bitmap work = base.copy(Bitmap.Config.ARGB_8888, true);
                    if (work != null) {
                        out = composer.composeOn(work, work.getWidth(), work.getHeight(), st, jx, jy);
                    }
                }
            } catch (Throwable ignored) {
            }
            final Bitmap result = out;
            ui.post(() -> {
                previewBusy = false;
                if (result == null) return;
                // 过期的帧没人会显示，必须回收，否则每次拖动滑块都漏一张整图
                if (seq != previewSeq) {
                    recycleQuietly(result);
                    return;
                }
                Bitmap prev = lastComposed;
                lastComposed = result;
                if (detailMode) {
                    updateCloseUp();
                } else {
                    preview.setImageBitmap(result);
                }
                // setImageBitmap 之后 ImageView 已经不再引用上一张，可以安全回收
                if (prev != null && prev != result) recycleQuietly(prev);
            });
        });
    }

    /**
     * 特写：把水印那一块裁出来，可用 closeUpPan 左右平移。
     *
     * 裁剪结果画进一块复用缓冲里。原来每次 ACTION_MOVE 都 Bitmap.createBitmap
     * 一张新图（1400×300 约 1.7MB），拖几十秒就是上千张垃圾。
     */
    private void updateCloseUp() {
        if (!detailMode || lastComposed == null) return;
        int[] box = closeUpBox(lastComposed, style);
        if (box[2] < 8 || box[3] < 8) {
            preview.setImageBitmap(lastComposed);
            return;
        }
        if (cropBuf == null || cropBuf.isRecycled()
                || cropBuf.getWidth() != box[2] || cropBuf.getHeight() != box[3]) {
            recycleQuietly(cropBuf);
            cropBuf = null;
            cropCanvas = null;
            try {
                cropBuf = Bitmap.createBitmap(box[2], box[3], Bitmap.Config.ARGB_8888);
                cropCanvas = new Canvas(cropBuf);
            } catch (Throwable t) {
                return;
            }
        }
        if (cropCanvas == null) return;
        cropCanvas.drawBitmap(lastComposed, -box[0], -box[1], null);
        preview.setImageBitmap(cropBuf);
    }

    /** @return {x, y, w, h}：特写要裁的区域，已夹在图内 */
    private int[] closeUpBox(Bitmap full, WatermarkPainter.Style st) {
        int W = full.getWidth(), H = full.getHeight();
        WatermarkPainter.Layout L = WatermarkPainter.layout(W, H, st);
        float u = W / 1000f;
        int pad = Math.round(L.panel.h * 1.1f) + Math.round(30 * u);
        int top = Math.max(0, Math.round(L.panel.y) - pad);
        int bottom = Math.min(H, Math.round(L.panel.y + L.panel.h) + pad);
        int cw = Math.min(W, Math.round(L.panel.w * 0.62f));
        int ch = bottom - top;
        if (cw < 8 || ch < 8) return new int[]{0, 0, 0, 0};
        int minX = Math.max(0, Math.round(L.panel.x - 20 * u));
        int maxX = Math.max(minX, Math.min(W - cw, Math.round(L.panel.x + L.panel.w - cw + 20 * u)));
        int cx = Math.round(minX + (maxX - minX) * closeUpPan);
        cx = Math.max(0, Math.min(W - cw, cx));
        return new int[]{cx, Math.max(0, Math.min(H - ch, top)), cw, ch};
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
                if (hadVideo && VideoWatermark.lastColorNote != null) {
                    msg = msg + "\n" + VideoWatermark.lastColorNote;
                }
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
