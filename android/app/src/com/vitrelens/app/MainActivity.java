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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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

public class MainActivity extends Activity implements SeekBar.OnSeekBarChangeListener {

    private static final int REQ_PICK = 1001;
    private static final int REQ_LOGO = 1002;
    private static final int REQ_VIDEO = 1003;
    // 预览从 1400 提高到 2000：全图仍按 View 缩放，特写与高分辨率 Logo 不再明显发虚
    private static final int PREVIEW_MAX = 2000;

    private ImageView preview, backdrop;
    /** 选中的照片列表（支持多选）；只有一个时缩略图条隐藏 */
    private final java.util.List<Uri> photoUris = new java.util.ArrayList<>();
    private int photoIndex = 0;
    private HorizontalScrollView filmRow;
    private LinearLayout filmstrip;
    /** 批量导出用：这一张处理完后要做什么 */
    /** 批量导出：这一张加载完之后要做的事 */
    private Runnable afterLoad;
    /**
     * 已 centerCrop 到整屏、并统一做过一次高斯的背景。
     * 背景 ImageView 和两块液态玻璃面板共用这一张，保证模糊程度完全一致。
     */
    private Bitmap backdropBmp;
    private TextView hint, status, sizeInfo, btnPick, btnExport, btnDetail;
    private boolean detailMode;
    private float closeUpPan = 0.5f;
    private String versionName = "1.0";
    private boolean checkingUpdate;
    // 拖动液态玻璃的形变（-1~1），由阻尼弹簧弹回 0；导出永远用 0
    private float jellyX, jellyY, jellyVX, jellyVY, jellyTX, jellyTY;
    /** 长按程度 0~1：手指压下去，玻璃整块鼓起 */
    private float jellyP, jellyPV, jellyPT;
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

    /* 普通视频模式：帧滑块只解码当前帧，导出时才处理整段视频 */
    private Uri videoUri;
    private VideoClip videoClip;
    private Bitmap videoRawFrame;
    private int videoRawIndex = -1;
    private int videoFrameIndex;
    private int videoPreviewSeq;
    private final Runnable videoFrameTask = () -> renderVideoPreview(true);
    private final Runnable videoStyleTask = () -> renderVideoPreview(false);
    private LinearLayout videoFrameBar;
    private SeekBar videoFrameSeek;
    private TextView videoFrameLabel;

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
        // 上次运行留下的高质量预览直接删掉，避免占缓存
        clearHqPreview();
        filmRow = findViewById(R.id.filmRow);
        filmstrip = findViewById(R.id.filmstrip);
        videoFrameBar = findViewById(R.id.videoFrameBar);
        videoFrameSeek = findViewById(R.id.videoFrameSeek);
        videoFrameLabel = findViewById(R.id.videoFrameLabel);
        videoFrameSeek.setOnSeekBarChangeListener(this);
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
                    // 按住不动 380ms 视为长按：玻璃被「按」得鼓起
                    ui.removeCallbacks(jellyLongPress);
                    ui.postDelayed(jellyLongPress, 380);
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
                    // 手指一旦移动就不再算长按
                    if (Math.hypot(dx, dy) > 10f / Math.max(scale, 1e-4)) {
                        ui.removeCallbacks(jellyLongPress);
                        if (jellyPT > 0f) {
                            jellyPT = 0f;
                        }
                    }
                    jellyTX = Math.max(-1f, Math.min(1f, dx / denom));
                    jellyTY = Math.max(-1f, Math.min(1f, dy / denom));
                    startJelly();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!jellyActive) return false;
                    jellyActive = false;
                    ui.removeCallbacks(jellyLongPress);
                    jellyTX = 0f;
                    jellyTY = 0f;
                    jellyPT = 0f;
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

        btnPick.setOnClickListener(v -> pickSource());
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
                : "支持 JPG / PNG / HEIC / MP4；照片保留元数据，视频可逐帧预览并导出");

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
        recycleQuietly(videoRawFrame);
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

    /** 高质量预览：缓存文件 + 生成时的样式签名 */
    private File hqFile;
    private String hqSig;

    /** 样式签名：任何一个会影响水印外观的参数变了，缓存就作废 */
    private String styleSig() {
        WatermarkPainter.Style s = style;
        return s.name + "|" + s.subtitle + "|" + s.params + "|" + s.meta + "|" + s.brand
                + "|" + s.showSubtitle + s.showBrand + s.showParams + s.showMeta
                + "|" + s.widthPct + "|" + s.gapPct + "|" + s.glassHeightPct
                + "|" + s.nameSize + "|" + s.metaSize + "|" + s.font + "|" + s.nameMaterial
                + "|" + s.italic + "|" + s.align + "|" + s.posXPct
                + "|" + s.logoPosPct + "|" + s.logoSizePct + "|" + s.logoRight
                + "|" + s.videoGapShift + "|" + s.videoWidthScale
                + "|" + s.roughPct + "|" + s.thicknessPct + "|" + s.depthPct + "|" + s.ior
                + "|" + s.reflPct + "|" + s.transPct + "|" + s.whitePct + "|" + s.dispPct
                + "|" + s.edgePct + "|" + s.segments + "|" + s.textHue + "|" + s.textSat
                + "|" + s.textVal + "|" + s.frostTone + "|" + s.tintOn;
    }

    /** 换图 / 退出应用时把缓存删掉 */
    private void clearHqPreview() {
        hqSig = null;
        try {
            File f = new File(getCacheDir(), "vl_hq.mp4");
            if (f.exists() && !f.delete()) {
                Log.w("VitreLens", "高质量预览缓存没删掉：" + f);
            }
        } catch (Throwable ignored) {
        }
    }

    private void toggleVideo() {
        if (videoPlaying) {
            stopVideo();
            return;
        }
        if (meta == null || meta.trailerVideoLength <= 0 || srcUri == null) {
            toast("这张照片没有动态部分");
            return;
        }

        // 高质量预览：走和导出完全相同的 GL 合成（真的玻璃折射），生成一次后缓存，
        // 样式没变就直接播缓存。换图 / 退出应用会删掉。
        final String sig = styleSig();
        hqFile = new File(getCacheDir(), "vl_hq.mp4");
        if (hqFile.exists() && sig.equals(hqSig)) {
            startPlayer(hqFile, true);
            return;
        }
        buildHighQualityPreview(sig);
    }

    /** 用导出那条路生成一份带真玻璃水印的短视频，作为高质量预览 */
    private void buildHighQualityPreview(final String sig) {
        final Uri uri = srcUri;
        final long skip = meta.eoiEnd + meta.trailerLeadLength;
        final long len = meta.trailerVideoLength;
        final ProgressDialog pd = ProgressDialog.show(this, "", "正在生成高质量预览…", true, false);
        // 必须和正式导出共用同一条 GL 队列。普通线程里 eglMakeCurrent 会失败，
        // 之前失败后静默回退 Canvas，导致所谓「高质量预览」其实还是近似水印。
        glQueue.execute(() -> {
            boolean ok = false;
            try {
                File src = new File(getCacheDir(), "vl_preview.mp4");
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new RuntimeException("无法打开源文件");
                try {
                    VideoWatermark.extractRange(in, skip, len, src);
                } finally {
                    in.close();
                }
                VideoWatermark.transcode(this, src, hqFile, composer, style.copy(),
                        (frame, total, stage) -> { });
                ok = hqFile.exists() && hqFile.length() > 1024;
            } catch (Throwable t) {
                Log.w("VitreLens", "高质量预览生成失败，回退普通预览", t);
            }
            final boolean good = ok;
            runOnUiThread(() -> {
                if (pd != null && pd.isShowing()) pd.dismiss();
                if (good) {
                    hqSig = sig;
                    startPlayer(hqFile, true);
                } else {
                    startPlainPreview(uri, skip, len);
                }
            });
        });
    }

    /** 回退：直接播原视频，水印用 Canvas 近似 */
    private void startPlainPreview(final Uri uri, final long skip, final long len) {
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
                runOnUiThread(() -> startPlayer(tmp, false));
            } catch (Throwable t) {
                runOnUiThread(() -> toast("读取动态部分失败：" + t.getMessage()));
            }
        }).start();
    }

    private void startPlayer(File f, boolean baked) {
        try {
            stopVideo();
            videoView.setVisibility(View.VISIBLE);
            // 高质量预览里水印已经烘焙进画面了，不要再叠 Canvas 那层
            videoMark.setVisibility(baked ? View.GONE : View.VISIBLE);
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
                // getVideoWidth() 返回的是编码尺寸，竖拍视频常带 90° 旋转标记，
                // 直接用会把画面当成横的 → 拉伸成 1:1。这里按旋转后的显示尺寸来。
                int[] disp = videoDisplaySize(f);
                videoMark.setVideoSize(disp != null ? disp[0] : mp.getVideoWidth(),
                        disp != null ? disp[1] : mp.getVideoHeight());
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

    /** 视频旋转之后的显示尺寸（竖拍视频的编码尺寸是横的） */
    private int[] videoDisplaySize(java.io.File f) {
        android.media.MediaMetadataRetriever mm = new android.media.MediaMetadataRetriever();
        try {
            mm.setDataSource(f.getAbsolutePath());
            String ws = mm.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String hs = mm.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rs = mm.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (ws == null || hs == null) return null;
            int w = Integer.parseInt(ws), h = Integer.parseInt(hs);
            int rot = rs == null ? 0 : Integer.parseInt(rs);
            if (rot == 90 || rot == 270) {
                int t = w;
                w = h;
                h = t;
            }
            return new int[]{w, h};
        } catch (Throwable t) {
            return null;
        } finally {
            try { mm.release(); } catch (Throwable ignored) { }
        }
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
        // 已经加载过照片就不能再把「选择一张照片开始」放出来
        hint.setVisibility(previewBase == null ? View.VISIBLE : View.GONE);
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
            // srcPath 支持用 | 分隔多张（外部一次传一批）
            String path = it.getStringExtra("srcPath");
            if (path != null && !path.isEmpty()) {
                String[] parts = path.split("\\|");
                if (parts.length > 1) {
                    photoUris.clear();
                    for (String one : parts) {
                        if (!one.trim().isEmpty()) {
                            photoUris.add(Uri.fromFile(new java.io.File(one.trim())));
                        }
                    }
                    if (!photoUris.isEmpty()) {
                        photoIndex = 0;
                        loadImage(photoUris.get(0), false);
                        return;
                    }
                }
                uri = Uri.fromFile(new java.io.File(path));
            }
        }
        if (uri == null) return;
        final Uri u = uri;
        String type = it.getType();
        if (type == null) {
            try { type = getContentResolver().getType(u); } catch (Throwable ignored) { }
        }
        String path = u.getPath();
        if ((type != null && type.startsWith("video/"))
                || (path != null && path.toLowerCase(Locale.US).matches(".*\\.(mp4|mov|mkv|webm|m4v)$"))) {
            loadVideo(u);
            return;
        }
        photoUris.clear();
        photoUris.add(u);
        photoIndex = 0;
        int flags = it.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (flags != 0) {
            try { getContentResolver().takePersistableUriPermission(u, flags); } catch (Exception ignored) { }
        }
        loadImage(u);
    }

    private void pickSource() {
        new AlertDialog.Builder(this)
                .setTitle("选择素材")
                .setItems(new String[]{"照片（可多选）", "普通视频"}, (d, which) -> {
                    if (which == 0) pickImage();
                    else pickVideo();
                })
                .show();
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (i.resolveActivity(getPackageManager()) == null) {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivityForResult(i, REQ_VIDEO);
        } catch (Throwable t) {
            toast("没有找到可用的视频选择器");
        }
    }

    private void pickImage() {
        // ACTION_OPEN_DOCUMENT 打开的是系统的「文件」界面（按目录浏览），
        // 挑照片要多点好几层。改用相册的 ACTION_PICK：直接是缩略图网格。
        // 没有相册应用时再退回 ACTION_GET_CONTENT（仍然只列图片）。
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (i.resolveActivity(getPackageManager()) == null) {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 相册不认多选时退回系统选择器，那边支持多选
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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
            java.util.ArrayList<Uri> picked = new java.util.ArrayList<>();
            android.content.ClipData cd = data.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                for (int k = 0; k < cd.getItemCount(); k++) {
                    Uri u = cd.getItemAt(k).getUri();
                    if (u != null) picked.add(u);
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (!picked.isEmpty()) {
                photoUris.clear();
                photoUris.addAll(picked);
                photoIndex = 0;
                loadImage(photoUris.get(0), false);
            }
        } else if (req == REQ_VIDEO && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadVideo(uri);
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

    /** 多选时在预览下方显示缩略图条，点一张就切过去 */
    private void buildFilmstrip() {
        if (filmstrip == null || filmRow == null) return;
        filmstrip.removeAllViews();
        if (photoUris.size() <= 1) {
            filmRow.setVisibility(View.GONE);
            return;
        }
        filmRow.setVisibility(View.VISIBLE);
        for (int i = 0; i < photoUris.size(); i++) {
            final int idx = i;
            // 边框画在外层容器上：直接画在 ImageView 上会被 clipToOutline 裁掉一半
            FrameLayout box = new FrameLayout(this);
            box.setBackground(thumbBg(i == photoIndex));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(6);
            box.setLayoutParams(lp);

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            ip.setMargins(dp(2), dp(2), dp(2), dp(2));
            iv.setLayoutParams(ip);
            iv.setBackground(thumbBg(false));
            iv.setClipToOutline(true);
            box.addView(iv);
            box.setOnClickListener(v -> switchTo(idx));
            filmstrip.addView(box);
            loadThumb(iv, photoUris.get(i));
        }
        // 让当前选中那张可见
        final int cur = photoIndex;
        filmRow.post(() -> {
            if (cur < filmstrip.getChildCount()) {
                filmRow.smoothScrollTo(filmstrip.getChildAt(cur).getLeft() - dp(24), 0);
            }
        });
    }

    private android.graphics.drawable.Drawable thumbBg(boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(10));
        g.setColor(0x33FFFFFF);
        g.setStroke(on ? dp(2) : 1, on ? 0xFFFFFFFF : 0x33FFFFFF);
        return g;
    }

    private void loadThumb(ImageView iv, Uri uri) {
        new Thread(() -> {
            Bitmap b = null;
            try {
                b = getContentResolver().loadThumbnail(uri, new android.util.Size(132, 132), null);
            } catch (Throwable ignored) {
                // file:// 之类的 URI 没有缩略图接口，自己解一张小图
            }
            if (b == null) {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 8;
                    InputStream in = getContentResolver().openInputStream(uri);
                    b = BitmapFactory.decodeStream(in, null, o);
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
            }
            final Bitmap bb = b;
            if (bb != null) ui.post(() -> iv.setImageBitmap(bb));
        }).start();
    }

    private void switchTo(int idx) {
        if (idx < 0 || idx >= photoUris.size() || idx == photoIndex) return;
        photoIndex = idx;
        loadImage(photoUris.get(idx), true);
        buildFilmstrip();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ── 普通视频：选择精确帧预览，整段导出 ── */

    private void clearVideoMode() {
        videoPreviewSeq++;
        videoUri = null;
        videoClip = null;
        videoRawIndex = -1;
        videoFrameIndex = 0;
        final Bitmap oldFrame = videoRawFrame;
        videoRawFrame = null;
        // 旧帧可能正被 GL 队列合成，回收也排到同一条队列，避免跨线程 use-after-recycle
        if (oldFrame != null) glQueue.execute(() -> recycleQuietly(oldFrame));
        if (videoFrameBar != null) videoFrameBar.setVisibility(View.GONE);
        ui.removeCallbacks(videoFrameTask);
        ui.removeCallbacks(videoStyleTask);
    }

    private void loadVideo(Uri uri) {
        stopVideo();
        clearVideoMode();
        photoUris.clear();
        if (filmRow != null) filmRow.setVisibility(View.GONE);
        recycleQuietly(photo);
        photo = null;
        meta = null;
        videoUri = uri;
        btnPlay.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        btnExport.setEnabled(false);
        sizeInfo.setText("读取视频…");
        ProgressDialog pd = ProgressDialog.show(this, "", "读取视频…", true, false);
        new Thread(() -> {
            try {
                final VideoClip clip = VideoClip.open(this, uri,
                        new File(getCacheDir(), "vl_input_video.mp4"));
                runOnUiThread(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    videoClip = clip;
                    videoFrameIndex = 0;
                    videoFrameSeek.setMax(clip.frameCount - 1);
                    videoFrameSeek.setProgress(0);
                    videoFrameBar.setVisibility(View.VISIBLE);
                    sizeInfo.setText(clip.width + " × " + clip.height + " · "
                            + clip.frameCount + " 帧 · " + formatVideoTime(clip.durationUs));
                    status.setText("拖动帧滑块精确预览；导出会对整段视频逐帧添加同款液态玻璃水印");
                    btnExport.setEnabled(true);
                    updateVideoFrameLabel();
                    renderVideoPreview(true);
                });
            } catch (Throwable t) {
                final String msg = String.valueOf(t.getMessage());
                runOnUiThread(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    sizeInfo.setText("视频读取失败");
                    toast("读取视频失败：" + msg);
                });
            }
        }).start();
    }

    private void scheduleVideoFrame() {
        ui.removeCallbacks(videoFrameTask);
        // 拖动过程中只做短防抖；松手会立即执行。每次只解码一帧，不启动整段转码。
        ui.postDelayed(videoFrameTask, 160);
    }

    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (!fromUser || videoClip == null) return;
        videoFrameIndex = progress;
        updateVideoFrameLabel();
        scheduleVideoFrame();
    }

    @Override public void onStartTrackingTouch(SeekBar bar) {
        ui.removeCallbacks(videoFrameTask);
    }

    @Override public void onStopTrackingTouch(SeekBar bar) {
        ui.removeCallbacks(videoFrameTask);
        ui.post(videoFrameTask);
    }

    private void updateVideoFrameLabel() {
        if (videoClip == null || videoFrameLabel == null) return;
        videoFrameLabel.setText(String.format(Locale.US, "第 %d / %d 帧 · %s",
                videoFrameIndex + 1, videoClip.frameCount,
                formatVideoTime(videoClip.timeUsForFrame(videoFrameIndex))));
    }

    private static String formatVideoTime(long us) {
        long ms = Math.max(0, us / 1000);
        return String.format(Locale.US, "%02d:%02d.%03d",
                ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    private void renderVideoPreview(final boolean decodeFrame) {
        final VideoClip clip = videoClip;
        if (clip == null) return;
        final int index = videoFrameIndex;
        final int seq = ++videoPreviewSeq;
        final WatermarkPainter.Style st = style.copy();
        glQueue.execute(() -> {
            Bitmap out = null, bg = null;
            try {
                if (decodeFrame || videoRawFrame == null || videoRawIndex != index) {
                    Bitmap fresh = clip.frameAt(clip.timeUsForFrame(index), PREVIEW_MAX);
                    Bitmap old = videoRawFrame;
                    videoRawFrame = fresh;
                    videoRawIndex = index;
                    if (old != null && old != fresh) old.recycle();
                }
                Bitmap work = videoRawFrame.copy(Bitmap.Config.ARGB_8888, true);
                if (work == null) throw new RuntimeException("无法创建预览帧");
                out = composer.composeOn(work, work.getWidth(), work.getHeight(), st);
                bg = makeBackdrop(out);
            } catch (Throwable t) {
                Log.w("VitreLens", "视频帧预览失败", t);
            }
            final Bitmap result = out, background = bg;
            ui.post(() -> {
                if (result == null) {
                    toast("这一帧预览失败");
                    return;
                }
                if (seq != videoPreviewSeq || videoClip != clip) {
                    recycleQuietly(result);
                    recycleQuietly(background);
                    return;
                }
                Bitmap previous = lastComposed;
                previewBase = result;
                lastComposed = result;
                if (detailMode) updateCloseUp();
                else preview.setImageBitmap(result);
                if (previous != null && previous != result) recycleQuietly(previous);
                backdropBmp = background;
                backdrop.setImageBitmap(background);
                applyUiGlass();
            });
        });
    }

    private void loadImage(Uri uri) {
        loadImage(uri, false);
    }

    private void loadImage(Uri uri, boolean quiet) {
        clearVideoMode();
        ProgressDialog pd = quiet ? null : ProgressDialog.show(this, "", "读取照片…", true, false);
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
                    backdrop.setImageBitmap(bg);
                    applyUiGlass();
                    fillFromMeta();
                    if (pd != null) pd.dismiss();
                    // 换了照片，上一张的高质量预览就没用了
                    clearHqPreview();
                    buildFilmstrip();
                    schedulePreview();
                    if (afterLoad != null) {
                        Runnable r = afterLoad;
                        afterLoad = null;
                        ui.postDelayed(r, 150);
                    }
                });
            } catch (Throwable e) {
                final String msg = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    if (pd != null) pd.dismiss();
                    toast("读取失败：" + msg);
                });
            }
        }).start();
    }

    /**
     * 先把照片 centerCrop 成屏幕尺寸，再对**整屏**统一做一次轻高斯。
     *
     * <p>这张位图同时用于背景显示和两块玻璃面板的取样。这样面板里一定也有高斯，
     * 且左栏与右面板共享同一个模糊半径；面板自身只负责折射、反射和边缘，不再二次模糊。
     */
    private Bitmap makeBackdrop(Bitmap src) {
        int vw = Math.max(1, backdrop.getWidth());
        int vh = Math.max(1, backdrop.getHeight());
        float scale = Math.max(vw / (float) src.getWidth(), vh / (float) src.getHeight());
        int sw = Math.max(vw, Math.round(src.getWidth() * scale));
        int sh = Math.max(vh, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);

        Bitmap out = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(scaled, (vw - sw) / 2f, (vh - sh) / 2f, null);
        if (scaled != out) scaled.recycle();

        int[] px = new int[vw * vh];
        out.getPixels(px, 0, vw, 0, 0, vw, vh);
        Blur.boxBlur(px, vw, vh, Math.max(2, Math.round(vw / 260f)));
        out.setPixels(px, 0, vw, 0, 0, vw, vh);
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
        // 不要用 hasVideo 覆盖用户的开关：普通照片没有视频段，覆盖之后
        // 再打开实况照片时开关还停在「关」，看起来就像默认关闭了。

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
    /**
     * 把位图四个角按圆角裁掉（alpha 清零，边缘 1px 做抗锯齿）。
     * 只处理四个 radius×radius 的方块，代价可以忽略。
     */
    private Bitmap roundCorners(Bitmap src, int w, int h, float radius) {
        if (src == null) return null;
        Bitmap out = src;
        try {
            if (!src.isMutable()) {
                out = src.copy(Bitmap.Config.ARGB_8888, true);
            }
            if (out == null) return src;
            float r = Math.max(0f, Math.min(radius, Math.min(w, h) / 2f));
            int ri = (int) Math.ceil(r);
            int[] row = new int[Math.max(1, ri)];
            for (int y = 0; y < ri; y++) {
                for (int x = 0; x < ri; x++) {
                    // 以左上角圆心 (r, r) 为基准算覆盖率
                    float dx = Math.max(0f, r - x - 0.5f);
                    float dy = Math.max(0f, r - y - 0.5f);
                    float d = (float) Math.hypot(dx, dy);
                    float cover = Math.max(0f, Math.min(1f, r - d + 0.5f));
                    if (cover >= 1f) continue;
                    setCornerAlpha(out, w, h, x, y, cover);
                    setCornerAlpha(out, w, h, w - 1 - x, y, cover);
                    setCornerAlpha(out, w, h, x, h - 1 - y, cover);
                    setCornerAlpha(out, w, h, w - 1 - x, h - 1 - y, cover);
                }
            }
        } catch (Throwable t) {
            return src;
        }
        return out;
    }

    private void setCornerAlpha(Bitmap b, int w, int h, int x, int y, float cover) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int c = b.getPixel(x, y);
        int a = Math.round(((c >>> 24) & 0xFF) * cover);
        b.setPixel(x, y, (a << 24) | (c & 0x00FFFFFF));
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

        // 已模糊的整屏背景与视图坐标 1:1 对齐；两块面板取样的就是同一张高斯结果
        Bitmap srcBmp = backdropBmp;
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
                // 着色器在圆角之外原样输出背景像素（mask=0），贴到方形 View 上
                // 四个角就会露出「没处理过的原始背景」—— 看起来就是四个硬棱角。
                // 这里按圆角把四角抠成透明，露出下面的整屏背景。
                // 直接按圆角把四角的 alpha 清零。之前用 PorterDuff.DST_IN 画一遍圆角，
                // 实测在模拟器上没生效 —— 四个角仍然是实心的，于是面板边界看起来是个直角矩形。
                Bitmap masked = roundCorners(res, vw, vh, radiusPx);
                v.setBackground(new android.graphics.drawable.BitmapDrawable(
                        getResources(), masked));
            });
        });
    }

    /** 界面玻璃用的参数：磨砂、低反射、折射克制 —— 和导出用的预设是同一套字段 */
    private WatermarkPainter.Style uiGlassStyle() {
        WatermarkPainter.Style s = new WatermarkPainter.Style();
        s.ior = 1.46f;
        s.radiusPct = 100f;
        // 界面面板尺寸差很大，用像素而不是百分比，两块才像同一个材质。
        // 斜面必须明显小于圆角（22dp≈44px），否则圆角处会被折出棱角。
        s.pixelUnits = true;
        s.depthPct = 14f;
        s.thicknessPct = 10f;
        // 不再二次模糊：背景本身已经是模糊过的，面板再糊一遍会比周围更糊
        // （而且模糊半径是按渲染块宽度算的，左栏和右面板本来就会差出好几倍）。
        s.roughPct = 0f;
        s.dispPct = 5f;
        s.reflPct = 22f;
        s.transPct = 97f;
        s.glossPct = 24f;
        s.shadowPct = 0f;
        s.whitePct = 0f;
        s.warpPct = 0f;
        s.edgePct = 30f;
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

    private final Runnable jellyLongPress = this::onJellyLongPress;

    private void onJellyLongPress() {
        if (!jellyActive) return;
        jellyPT = 1f;
        startJelly();
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
        // 按压回弹慢一点，像真的按下去再弹起来
        jellyPV += ((jellyPT - jellyP) * 420f - jellyPV * 30f) * dt;
        jellyP += jellyPV * dt;

        boolean settled = !jellyActive
                && Math.abs(jellyTX - jellyX) < 0.004f && Math.abs(jellyVX) < 0.02f
                && Math.abs(jellyTY - jellyY) < 0.004f && Math.abs(jellyVY) < 0.02f
                && Math.abs(jellyPT - jellyP) < 0.004f && Math.abs(jellyPV) < 0.02f;
        if (settled) {
            jellyX = jellyTX;
            jellyY = jellyTY;
            jellyP = jellyPT;
            jellyVX = jellyVY = jellyPV = 0f;
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
        if (videoClip != null) {
            // 样式变化只重跑水印合成，复用已解码的原始帧，不重复访问视频解码器
            ui.removeCallbacks(videoStyleTask);
            ui.postDelayed(videoStyleTask, 70);
            return;
        }
        if (previewBase == null) return;
        ui.removeCallbacks(previewTask);
        ui.postDelayed(previewTask, 70);
    }

    private final Runnable previewTask = this::runPreview;

    private void runPreview() {
        if (videoClip != null) {
            renderVideoPreview(false);
            return;
        }
        if (previewBase == null) return;
        final WatermarkPainter.Style st = style.copy();
        final int seq = ++previewSeq;
        final float jx = jellyX, jy = jellyY, jp = jellyP;
        previewBusy = true;
        glQueue.execute(() -> {
            Bitmap out = null;
            try {
                Bitmap base = previewBase;
                if (base != null) {
                    Bitmap work = base.copy(Bitmap.Config.ARGB_8888, true);
                    if (work != null) {
                        out = composer.composeOn(work, work.getWidth(), work.getHeight(), st, jx, jy, jp);
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
        if (videoClip != null) {
            exportVideo();
            return;
        }
        if (photoUris.size() > 1) {
            startBatch();
            return;
        }
        if (photo == null) return;
        exportOne(null);
    }

    /** 普通视频导出：复用实况照片的逐帧 GL 合成与编码器，音轨原样保留。 */
    private void exportVideo() {
        final VideoClip clip = videoClip;
        if (clip == null) return;
        final WatermarkPainter.Style st = style.copy();
        final ProgressDialog pd = ProgressDialog.show(this, "", "正在给视频加水印…", true, false);
        glQueue.execute(() -> {
            Uri outUri = null;
            File tmp = new File(getCacheDir(), "vl_video_export.mp4");
            try {
                VideoWatermark.transcode(this, clip.file, tmp, composer, st,
                        (frame, total, stage) -> {
                            if (frame % 3 == 0 || frame >= total) {
                                setPdMessage(pd, stage + " " + frame + "/" + total);
                            }
                        });

                String name = "VL_VIDEO_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new Date()) + ".mp4";
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VitreLens");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (outUri == null) throw new RuntimeException("无法创建视频文件");

                InputStream in = new java.io.FileInputStream(tmp);
                OutputStream out = getContentResolver().openOutputStream(outUri);
                if (out == null) {
                    in.close();
                    throw new RuntimeException("无法写入视频");
                }
                try {
                    byte[] buf = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n > 0) out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    in.close();
                    out.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outUri, done, null, null);
                setPdMessage(pd, "导出完成");
                ui.post(() -> {
                    pd.dismiss();
                    toast("已导出视频 → Movies/VitreLens"
                            + (VideoWatermark.lastColorNote == null ? "" : "\n" + VideoWatermark.lastColorNote));
                });
            } catch (Throwable t) {
                if (outUri != null) {
                    try { getContentResolver().delete(outUri, null, null); } catch (Throwable ignored) { }
                }
                Log.e("VitreLens", "普通视频导出失败", t);
                final String msg = String.valueOf(t.getMessage());
                ui.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("视频导出失败")
                            .setMessage(msg)
                            .setPositiveButton("好", null)
                            .show();
                });
            }
        });
    }

    /* ── 批量导出：一张接一张，前一张写完再切下一张 ── */

    private int batchOk;

    private void startBatch() {
        batchOk = 0;
        photoIndex = -1;
        nextBatch(0);
    }

    private void nextBatch(final int i) {
        if (i >= photoUris.size()) {
            toast("已导出 " + batchOk + " / " + photoUris.size() + " 张到 Pictures/VitreLens");
            photoIndex = Math.max(0, Math.min(photoIndex, photoUris.size() - 1));
            buildFilmstrip();
            return;
        }
        photoIndex = i;
        buildFilmstrip();
        // 加载完这一张再导出，导出完再进下一张
        afterLoad = () -> exportOne(() -> nextBatch(i + 1));
        loadImage(photoUris.get(i), true);
    }

    private void exportOne(final Runnable onDone) {
        if (photo == null) {
            if (onDone != null) onDone.run();
            return;
        }
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

                // 带毫秒：批量导出时相邻两张经常落在同一秒，会生成 "xxx (1).jpg" 这种重名
                String name = "VL_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new Date()) + ".jpg";
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
                batchOk++;
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
                    if (onDone != null) onDone.run();
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
                    if (onDone != null) {
                        // 批量时弹框会挡住后面每一张，只记日志继续
                        Log.w("VitreLens", "第 " + (photoIndex + 1) + " 张导出失败：" + msg);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("导出失败")
                                .setMessage(msg)
                                .setPositiveButton("好", null)
                                .show();
                    }
                    if (onDone != null) onDone.run();
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
