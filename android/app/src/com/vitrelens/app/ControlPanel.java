package com.vitrelens.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 程序化生成全部可调项（对齐网页版 index.html 的四个分区）。
 * 所有控件直接读写同一个 WatermarkPainter.Style，改完回调 onChange。
 */
class ControlPanel {


    private final Activity act;
    private final WatermarkPainter.Style st;
    private final Runnable onChange;
    private final List<Runnable> refreshers = new ArrayList<>();
    /** 右侧的四个选项页，同一时间只显示一个 */
    private final List<LinearLayout> pages = new ArrayList<>();
    private final List<TextView> railButtons = new ArrayList<>();
    private LinearLayout rail;
    private LinearLayout railPill;
    private ScrollView scroll;
    private int pageIndex = 0;

    ControlPanel(Activity act, WatermarkPainter.Style st, Runnable onChange) {
        this.act = act;
        this.st = st;
        this.onChange = onChange;
    }

    List<Runnable> build(LinearLayout rail, LinearLayout content, ScrollView scroll) {
        this.rail = rail;
        this.scroll = scroll;
        // 玻璃面板挂在滚动区上（不是内容上），这样滚动时面板固定、只有选项在动
        scroll.setBackground(new GlassCardDrawable(dp(22), 0x2BFFFFFF));
        scroll.setClipToOutline(true);
        scroll.setPadding(dp(14), dp(12), dp(14), dp(16));
        rail.removeAllViews();
        content.removeAllViews();
        pages.clear();
        railButtons.clear();
        refreshers.clear();

        // ── 01 水印内容
        LinearLayout c1 = page(content);
        textRow(c1, "相机名称", () -> st.name, s -> st.name = s, "OPPO Find X9 Ultra");
        textRow(c1, "副标题", () -> st.subtitle, s -> st.subtitle = s, "选填，留空则不显示");
        textRow(c1, "拍摄参数", () -> st.params, s -> st.params = s, "23mm ƒ/1.8 1/287s ISO50");
        textRow(c1, "时间 / 地点", () -> st.meta, s -> st.meta = s, "2026.10.26 12:06:26");

        label(c1, "相机品牌 Logo");
        segmentedWrap(c1, new String[]{"无", "徕卡", "哈苏", "哈苏H", "蔡司", "理光", "苹果", "自定义"},
                v -> {
                    st.brand = v;
                    st.showBrand = v > 0;
                    st.logoSizePct = 100f;   // 各品牌合适的大小不同，换品牌就回默认
                    refreshAll();
                },
                () -> st.brand);
        button(c1, "选择自定义图标…", () -> {
            if (act instanceof MainActivity) ((MainActivity) act).pickCustomLogo();
        });
        hint(c1, "建议用透明背景的 PNG，或纯色底的字标（会自动抠掉底色）");

        label(c1, "显示");
        chips(c1, new String[]{"副标题", "Logo", "参数", "时间"},
                () -> new boolean[]{st.showSubtitle, st.showBrand, st.showParams, st.showMeta},
                (i, on) -> {
                    if (i == 0) st.showSubtitle = on;
                    else if (i == 1) st.showBrand = on;
                    else if (i == 2) st.showParams = on;
                    else st.showMeta = on;
                });

        button(c1, "从 EXIF 重新读取", () -> {
            if (act instanceof MainActivity) ((MainActivity) act).refillFromExif();
        });

        // ── 02 版式
        LinearLayout c2 = page(content);
        label(c2, "水印位置");
        segmented(c2, new String[]{"左下", "居中", "右下"},
                () -> alignIndex(st.align),
                v -> {
                    st.align = v == 0 ? "left" : (v == 2 ? "right" : "center");
                    st.posXPct = v == 0 ? 0f : (v == 2 ? 100f : 50f);
                },
                () -> alignIndex(st.align));

        slider(c2, "水印宽度", 50, 100, 1, () -> st.widthPct, v -> st.widthPct = v, p -> (int) p + "%");
        slider(c2, "玻璃高度", 100, 300, 1, () -> st.glassHeightPct, v -> st.glassHeightPct = v,
                p -> Math.round(p) + "%");
        slider(c2, "距底高度", 0, 50, 0.1f, () -> st.gapPct, v -> st.gapPct = v,
                p -> String.format(Locale.US, "%.1f%%", p));
        slider(c2, "机型字号", 14, 48, 0.5f, () -> st.nameSize, v -> st.nameSize = v,
                p -> String.format(Locale.US, "%.0f", p));
        slider(c2, "参数字号", 6, 20, 0.5f, () -> st.metaSize, v -> st.metaSize = v,
                p -> String.format(Locale.US, "%.1f", p));

        slider(c2, "Logo 位置", 0, 100, 1, () -> st.logoPosPct, v -> st.logoPosPct = v,
                p -> Math.round(p) == 0 ? "靠名称"
                        : (Math.round(p) == 100 ? (st.logoRight ? "靠右" : "贴参数")
                        : Math.round(p) + "%"));
        label(c2, "Logo 相对参数");
        segmented(c2, new String[]{"在左侧", "在右侧"},
                () -> st.logoRight ? 1 : 0,
                v -> st.logoRight = v == 1,
                () -> st.logoRight ? 1 : 0);
        slider(c2, "Logo 大小", 50, 200, 1, () -> st.logoSizePct, v -> st.logoSizePct = v,
                p -> Math.round(p) + "%");

        label(c2, "字体");
        segmented(c2, new String[]{"无衬线", "窄体", "衬线", "等宽"},
                () -> fontIndex(st.font),
                v -> st.font = new String[]{"sans", "condensed", "serif", "mono"}[v],
                () -> fontIndex(st.font));

        label(c2, "文字颜色");
        hueRow(c2);

        label(c2, "文字材质");
        segmented(c2, new String[]{"液态玻璃", "磨砂玻璃", "不透明"},
                () -> matIndex(st.nameMaterial),
                v -> st.nameMaterial = new String[]{"liquid", "frost", "solid"}[v],
                () -> matIndex(st.nameMaterial));

        chips(c2, new String[]{"斜体字形"},
                () -> new boolean[]{st.italic},
                (i, on) -> st.italic = on);

        // ── 03 液态玻璃
        LinearLayout c3 = page(content);
        label(c3, "预设");
        segmented(c3, new String[]{"常规", "清透", "磨砂", "厚玻璃"},
                () -> activeBuiltin, v -> applyPreset(v), () -> activeBuiltin);
        hint(c3, "预设会覆盖下面所有玻璃参数，可再单独微调");

        label(c3, "我的预设");
        presetRow(c3);

        // 首屏只放最常动的四项 + 色调：模糊负责材质、厚度负责折射、高光/投影负责存在感。
        // 其余光学参数收进「高级光学」，免得一屏全是滑块、默认效果反而没人看得懂。
        slider(c3, "模糊度", 0, 100, 1, () -> st.roughPct, v -> st.roughPct = v, p -> Math.round(p) + "%");
        slider(c3, "厚度", 0, 100, 1, () -> st.thicknessPct, v -> st.thicknessPct = v, p -> Math.round(p) + "%");
        slider(c3, "高光", 0, 150, 1, () -> st.glossPct, v -> st.glossPct = v, p -> Math.round(p) + "%");
        slider(c3, "投影", 0, 100, 1, () -> st.shadowPct, v -> st.shadowPct = v, p -> Math.round(p) + "%");
        chips(c3, new String[]{"色调"},
                () -> new boolean[]{st.tintOn},
                (i, on) -> st.tintOn = on);
        hint(c3, "想更像苹果：模糊度 20~30、厚度 30~40、反射率 30 上下，色散与扭曲保持接近 0；"
                + "边缘折射是那圈「背景被吸进去」的来源，别关掉。");

        LinearLayout adv = fold(c3, "高级光学", false);
        slider(adv, "玻璃折射率", 1.00f, 2.00f, 0.01f, () -> st.ior, v -> st.ior = v,
                p -> String.format(Locale.US, "%.2f", p));
        slider(adv, "圆角 R", 0, 100, 1, () -> st.radiusPct, v -> st.radiusPct = v, p -> Math.round(p) + "%");
        slider(adv, "深度", 0, 100, 1, () -> st.depthPct, v -> st.depthPct = v, p -> Math.round(p) + "%");
        // 边缘折射：100% = 位移为斜面宽度的 2 倍。超过 100% 外沿会出现镜像，
        // 这就是苹果玻璃边缘那圈「背景被吸进去」的来源
        slider(adv, "边缘折射", 0, 200, 1, () -> st.edgePct, v -> st.edgePct = v,
                p -> Math.round(p) + "%");
        // 色散走平方曲线：滑块 0~100 → 实际 0~200，滑到一半约 25%（50）
        // 对数曲线半程就到 126，前段压不下来，微调轻微彩边时手感不对
        slider(adv, "色散", 0, 100, 0.5f,
                () -> dispSlider(st.dispPct),
                v -> st.dispPct = dispValue(v),
                p -> Math.round(dispValue(p)) + "%");
        slider(adv, "反射率", 0, 100, 1, () -> st.reflPct, v -> st.reflPct = v, p -> Math.round(p) + "%");
        slider(adv, "透射率", 0, 100, 1, () -> st.transPct, v -> st.transPct = v, p -> Math.round(p) + "%");
        // 名字改成「艺术扭曲」：它跟像不像苹果是反的，越接近系统材质越应该保持 0
        slider(adv, "艺术扭曲", 0, 200, 1, () -> st.warpPct, v -> st.warpPct = v, p -> Math.round(p) + "%");
        // 加白走对数曲线：滑块位置 0~100 → 实际加白量由 whiteValue 换算
        // 步长 0.5：对数曲线低段很陡，1% 一步会有 1~2% 的显示偏差
        // 不透明度：把结果混向「实色」（浅色背景近白、深色背景近黑），
        // 对应 macOS 液态玻璃的「色调」档；0 就是系统默认的透明档
        slider(adv, "不透明度", 0, 100, 0.5f,
                () -> whiteSlider(st.whitePct),
                v -> st.whitePct = whiteValue(v),
                p -> Math.round(whiteValue(p)) + "%");
        label(adv, "磨砂色调");
        segmented(adv, new String[]{"自动", "深色", "浅色"},
                () -> Math.max(0, Math.min(2, st.frostTone)),
                v -> st.frostTone = v,
                () -> Math.max(0, Math.min(2, st.frostTone)));
        hint(adv, "「磨砂」预设的实色：自动跟着背景明暗走（对应系统深/浅色外观），"
                + "也可以强制成深色玻璃或浅色玻璃。");

        slider(adv, "细分", 8, 128, 4, () -> st.segments, v -> st.segments = Math.round(v),
                p -> String.valueOf(Math.round(p)));
        hint(adv, "折射率固定 1.45~1.50 就够；透射率是「混入环境色的比例」，调低会变成一块发白的色板；"
                + "不透明度是 macOS 那档「色调」玻璃，实色会跟着背景明暗在近白/近黑之间切换；"
                + "艺术扭曲会主动把背景扭成玻璃砖，越接近系统材质越应该保持 0。");

        // ── 04 导出
        LinearLayout c4 = page(content);
        chips(c4, new String[]{"实况照片水印"},
                () -> new boolean[]{videoOn},
                (i, on) -> videoOn = on);
        hint(c4, "勾上后，实况照片的视频段也会重新编码并打上同样的水印。");
        chips(c4, new String[]{"始终保留原始分辨率"},
                () -> new boolean[]{keepOriginalVideo},
                (i, on) -> keepOriginalVideo = on);
        hint(c4, "开启后只尝试硬件与 CPU 软件编解码，绝不降低分辨率；"
                + "关闭时，两种方式都失败后才会降到 1280 / 864。CPU 编码可能很慢。");
        slider(c4, "视频水印高度", -4, 14, 0.2f,
                () -> st.videoGapShift, v -> st.videoGapShift = v,
                p -> String.format(Locale.US, "%+.1f%%", p));
        slider(c4, "视频水印宽度", 85, 130, 0.5f,
                () -> st.videoWidthScale, v -> st.videoWidthScale = v,
                p -> Math.round(p) + "%");
        hint(c4, "实测 OPPO 实况照片的视频取景约为照片的 94%（贴着底边裁），"
                + "所以视频里的水印默认上移 2%、宽度收到 95%。不满意可自己微调。");

        // 左侧竖排按钮：整列做成一颗玻璃胶囊，里面的段被选中时高亮
        railPill = new LinearLayout(act);
        railPill.setOrientation(LinearLayout.VERTICAL);
        railPill.setPadding(dp(4), dp(4), dp(4), dp(4));
        railPill.setBackground(new GlassCardDrawable(dp(22), 0x3DFFFFFF));
        rail.addView(railPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] names = {"内容", "版式", "玻璃", "导出"};
        for (int i = 0; i < names.length; i++) railButton(names[i], i);
        showPage(0);

        return refreshers;
    }

    /* ── 外部状态 ── */

    boolean videoOn = true;
    /** 视频导出宁可失败也不降分辨率；CPU 软件编解码作为硬件失败后的后备 */
    boolean keepOriginalVideo = false;

    void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    void applyPreset(int idx) {
        // 预设必须覆盖**全部**玻璃参数，否则选了预设某些滑块还停在旧值
        // {ior, radius, depth, thickness, rough, disp, refl, trans, gloss, shadow, white, warp, segments, edge}
        float[][] p = {
                // 常规：系统默认的「透明」档 —— 模糊负责材质、厚度克制、反射与色散都收着
                {1.46f, 100, 24, 36, 20, 6, 28, 98, 28, 18, 0, 0, 128, 140},
                // 清透：基本清晰，全靠边缘折射认出来
                {1.46f, 100, 28, 44, 4, 8, 32, 100, 32, 14, 0, 0, 128, 160},
                // 磨砂：对应 macOS 26 的「色调」档 —— 不透明度提高，实色随浅色/深色背景
                // 在近白与近黑之间切换（trans 86 / white 30），再加上模糊
                {1.40f, 100, 30, 44, 76, 6, 30, 86, 24, 20, 30, 0, 128, 110},
                // 厚玻璃：保留原来的强风格，作为个性化预设（depth 已经很大，边缘折射收着）
                {1.72f, 100, 72, 88, 8, 46, 100, 100, 82, 58, 0, 130, 128, 60}
        };
        float[] v = p[Math.max(0, Math.min(p.length - 1, idx))];
        st.ior = v[0];
        st.radiusPct = v[1];
        st.depthPct = v[2];
        st.thicknessPct = v[3];
        st.roughPct = v[4];
        st.dispPct = v[5];
        st.reflPct = v[6];
        st.transPct = v[7];
        st.glossPct = v[8];
        st.shadowPct = v[9];
        st.whitePct = v[10];
        st.warpPct = v[11];
        st.segments = Math.round(v[12]);
        st.edgePct = v[13];
        st.tintOn = true;
        activeBuiltin = idx;
        activePreset = -1;   // 切到内置预设，之前选中的自定义预设不再生效
        refreshAll();
        onChange.run();
    }

    /* ── 我的预设（最多 3 个，存 SharedPreferences） ── */

    private static final String[] P_KEYS = {"p1", "p2", "p3"};
    private int activePreset = -1;
    /** 当前选中的内置预设，-1 = 未选 */
    private int activeBuiltin = -1;

    private void presetRow(LinearLayout parent) {
        final android.content.SharedPreferences sp =
                act.getSharedPreferences("vl_presets", android.content.Context.MODE_PRIVATE);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(2));
        final TextView[] chips = new TextView[3];

        final Runnable sync = () -> {
            for (int i = 0; i < 3; i++) {
                boolean has = sp.contains(P_KEYS[i]);
                chips[i].setText(has ? "预设 " + (i + 1) : "空 " + (i + 1));
                // 只有当前生效的那一个高亮，避免"有内容"和"正在用"混为一谈
                chips[i].setSelected(has && i == activePreset);
            }
        };

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = chip("");
            c.setOnClickListener(v -> {
                String saved = sp.getString(P_KEYS[idx], null);
                if (saved == null) {
                    sp.edit().putString(P_KEYS[idx], serialize()).apply();
                    activePreset = idx;
                    toastShort("已保存为预设 " + (idx + 1));
                } else {
                    deserialize(saved);
                    activePreset = idx;
                    refreshAll();
                    onChange.run();
                }
                sync.run();
            });
            c.setOnLongClickListener(v -> {
                if (!sp.contains(P_KEYS[idx])) {
                    sp.edit().putString(P_KEYS[idx], serialize()).apply();
                    activePreset = idx;
                    toastShort("已保存为预设 " + (idx + 1));
                    sync.run();
                    return true;
                }
                new android.app.AlertDialog.Builder(act)
                        .setTitle("预设 " + (idx + 1))
                        .setItems(new String[]{"覆盖为当前设置", "删除这个预设"}, (d, which) -> {
                            if (which == 0) {
                                sp.edit().putString(P_KEYS[idx], serialize()).apply();
                                activePreset = idx;
                                toastShort("已覆盖预设 " + (idx + 1));
                            } else {
                                sp.edit().remove(P_KEYS[idx]).apply();
                                if (activePreset == idx) activePreset = -1;
                                toastShort("已删除预设 " + (idx + 1));
                            }
                            sync.run();
                        })
                        .show();
                return true;
            });
            chips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(c, lp);
        }
        parent.addView(row, matchWrap());
        hint(parent, "点击套用；空槽点击保存；长按可覆盖或删除");
        sync.run();
        refreshers.add(sync);
    }

    private void toastShort(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 只序列化玻璃参数，这才是"液态玻璃预设"该记的东西 */
    private String serialize() {
        return st.ior + "|" + st.radiusPct + "|" + st.depthPct + "|" + st.thicknessPct + "|"
                + st.roughPct + "|" + st.dispPct + "|" + st.reflPct + "|" + st.transPct + "|"
                + st.glossPct + "|" + st.shadowPct + "|" + st.whitePct + "|"
                + (st.tintOn ? 1 : 0) + "|" + st.segments + "|" + st.warpPct;
    }

    private void deserialize(String v) {
        try {
            String[] a = v.split("\\|");
            st.ior = Float.parseFloat(a[0]);
            st.radiusPct = Float.parseFloat(a[1]);
            st.depthPct = Float.parseFloat(a[2]);
            st.thicknessPct = Float.parseFloat(a[3]);
            st.roughPct = Float.parseFloat(a[4]);
            st.dispPct = Float.parseFloat(a[5]);
            st.reflPct = Float.parseFloat(a[6]);
            st.transPct = Float.parseFloat(a[7]);
            st.glossPct = Float.parseFloat(a[8]);
            st.shadowPct = Float.parseFloat(a[9]);
            st.whitePct = Float.parseFloat(a[10]);
            st.tintOn = "1".equals(a[11]);
            st.segments = Integer.parseInt(a[12]);
            if (a.length > 13) st.warpPct = Float.parseFloat(a[13]);
        } catch (Throwable ignored) {
        }
    }

    /* ── 白值曲线 ── */

    /**
     * 加白用对数曲线：value = 100·ln(1+2t)/ln3，t 是滑块位置 0~1。
     * 低段响应快（滑块 10% 已有 17%），高段放缓，符合亮度的感知特性。
     * 之前是 2.6 次幂，前 30% 几乎拖不动，所以感觉"没反应"。
     */
    private static final double WHITE_K = 2.0;

    static float whiteValue(float slider) {
        double t = Math.min(1.0, Math.max(0.0, slider / 100.0));
        return (float) (100 * Math.log(1 + WHITE_K * t) / Math.log(1 + WHITE_K));
    }

    /** whiteValue 的反函数，用于把存储值换算回滑块位置 */
    static float whiteSlider(float value) {
        double v = Math.min(1.0, Math.max(0.0, value / 100.0));
        return (float) (100 * (Math.pow(1 + WHITE_K, v) - 1) / WHITE_K);
    }

    private static final double DISP_MAX = 200.0;

    /**
     * 滑块位置 0~100 → 实际色散 0~200，走平方曲线。
     * 要求「滑到一半约 25%」：t=0.5 → 200×0.25 = 50，正好是满量程的 25%。
     * （对数曲线 t=0.5 会到 126，太陡，前段根本压不下来。）
     */
    static float dispValue(float slider) {
        double t = Math.min(1.0, Math.max(0.0, slider / 100.0));
        return (float) (DISP_MAX * t * t);
    }

    /** dispValue 的反函数：把存储的色散值换算回滑块位置 */
    static float dispSlider(float value) {
        double v = Math.min(1.0, Math.max(0.0, value / DISP_MAX));
        return (float) (100 * Math.sqrt(v));
    }

    static int logoPosIndex(String p) {
        if ("left".equals(p)) return 0;
        if ("center".equals(p)) return 1;
        return 2;
    }

    static int alignIndex(String a) {
        return "left".equals(a) ? 0 : ("right".equals(a) ? 2 : 1);
    }

    static int fontIndex(String f) {
        if ("sans".equals(f)) return 0;
        if ("serif".equals(f)) return 2;
        if ("mono".equals(f)) return 3;
        return 1;
    }

    static int matIndex(String m) {
        if ("liquid".equals(m)) return 0;
        if ("frost".equals(m)) return 1;
        return 2;
    }

    /* ── 构件 ── */

    /**
     * 一页选项 = 一张玻璃卡片。分区名字交给左边的按钮，卡片里不再重复标题。
     * 默认隐藏，由 showPage() 控制显示哪一个。
     */
    private LinearLayout page(LinearLayout content) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setVisibility(View.GONE);
        content.addView(c, matchWrap());
        pages.add(c);
        return c;
    }

    /** 左栏胶囊里的一段 */
    private void railButton(String label, final int idx) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0x8AFFFFFF);
        t.setClickable(true);
        t.setOnClickListener(v -> showPage(idx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        if (idx > 0) lp.topMargin = dp(4);
        railPill.addView(t, lp);
        railButtons.add(t);
    }

    /** 选中段的底：上亮下暗的白，模拟玻璃被按下去那一块 */
    private Drawable segBg() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99FFFFFF, 0x59FFFFFF});
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(18));
        g.setStroke(Math.max(1, Math.round(dp(0.8f))), 0xB3FFFFFF);
        return g;
    }

    /** 切换右侧显示的选项组 */
    void showPage(int idx) {
        if (pages.isEmpty()) return;
        int i = Math.max(0, Math.min(pages.size() - 1, idx));
        final int dir = i >= pageIndex ? 1 : -1;   // 往后翻就从右边进来
        final boolean animate = i != pageIndex || pages.get(i).getVisibility() != View.VISIBLE;
        pageIndex = i;
        for (int k = 0; k < pages.size(); k++) {
            LinearLayout pg = pages.get(k);
            if (k == i) {
                pg.setVisibility(View.VISIBLE);
                if (animate) {
                    // 新的一页从侧面滑进来并淡入，给切换一点方向感
                    pg.animate().cancel();
                    pg.setAlpha(0f);
                    pg.setTranslationX(dir * dp(22));
                    pg.animate().alpha(1f).translationX(0f)
                            .setDuration(210)
                            .setInterpolator(new DecelerateInterpolator(1.6f))
                            .start();
                } else {
                    pg.setAlpha(1f);
                    pg.setTranslationX(0f);
                }
            } else {
                pg.animate().cancel();
                pg.setAlpha(1f);
                pg.setTranslationX(0f);
                pg.setVisibility(View.GONE);
            }
        }
        for (int k = 0; k < railButtons.size(); k++) {
            boolean on = k == i;
            TextView t = railButtons.get(k);
            t.setBackground(on ? segBg() : null);
            t.setTextColor(on ? 0xFF1A1A1A : 0x8AFFFFFF);
            t.setTypeface(Typeface.create("sans-serif", on ? Typeface.BOLD : Typeface.NORMAL));
        }
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    /**
     * 可折叠分组：返回内容容器，点标题行展开/收起。
     * 首屏只放常用项，其余收进来 —— 一屏十几个滑块反而没人知道该动哪个。
     */
    private LinearLayout fold(LinearLayout parent, String title, boolean expanded) {
        final TextView head = new TextView(act);
        head.setText((expanded ? "\u25be  " : "\u25b8  ") + title);
        head.setTextSize(11.5f);
        head.setTextColor(0xCCFFFFFF);
        head.setPadding(0, dp(14), 0, dp(6));
        parent.addView(head, matchWrap());

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(expanded ? View.VISIBLE : View.GONE);
        parent.addView(box, matchWrap());

        head.setOnClickListener(v -> {
            boolean show = box.getVisibility() != View.VISIBLE;
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            head.setText((show ? "\u25be  " : "\u25b8  ") + title);
        });
        return box;
    }

    /** 文字颜色：一行色块 + 说明，点开是 HSB 轮盘弹窗 */
    private void hueRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(4));

        final View swatch = new View(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.rightMargin = dp(14);
        row.addView(swatch, slp);

        final TextView info = new TextView(act);
        info.setTextSize(11.5f);
        info.setTextColor(0xB3FFFFFF);
        row.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Runnable sync = () -> {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(st.textHue < 0 ? 0xFFFFFFFF
                    : android.graphics.Color.HSVToColor(new float[]{
                    ((st.textHue % 360f) + 360f) % 360f,
                    Math.max(0f, Math.min(1f, st.textSat)),
                    Math.max(0f, Math.min(1f, st.textVal))}));
            g.setStroke(dp(1.5f), 0x59FFFFFF);
            swatch.setBackground(g);
            info.setText(st.textHue < 0
                    ? "自动（白字 / 深色字随背景）\n点这里打开 HSB 轮盘"
                    : String.format(Locale.US, "色相 %d°  饱和 %d%%  明度 %d%%\n点这里继续调整",
                    Math.round(st.textHue), Math.round(st.textSat * 100),
                    Math.round(st.textVal * 100)));
        };
        sync.run();

        row.setOnClickListener(v -> {
            final HsbWheelView wheel = new HsbWheelView(act);
            wheel.setColor(st.textHue < 0 ? 0f : st.textHue, st.textSat, st.textVal);
            wheel.setOnChange((h, sa, va) -> {
                st.textHue = h;
                st.textSat = sa;
                st.textVal = va;
                sync.run();
                onChange.run();
            });
            int size = dp(250);
            LinearLayout box = new LinearLayout(act);
            box.setPadding(dp(16), dp(12), dp(16), dp(4));
            box.addView(wheel, new LinearLayout.LayoutParams(size, size));

            new android.app.AlertDialog.Builder(act)
                    .setTitle("文字颜色")
                    .setView(box)
                    .setPositiveButton("完成", null)
                    .setNeutralButton("恢复自动", (d, w) -> {
                        st.textHue = -1f;
                        sync.run();
                        onChange.run();
                    })
                    .show();
        });

        parent.addView(row, matchWrap());
    }

    private void label(LinearLayout parent, String text) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextSize(11.5f);
        v.setTextColor(0xB3FFFFFF);
        v.setPadding(0, dp(12), 0, dp(4));
        parent.addView(v, matchWrap());
    }

    private void hint(LinearLayout parent, String text) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextSize(10.5f);
        v.setTextColor(0x70FFFFFF);
        v.setPadding(0, dp(6), 0, dp(2));
        parent.addView(v, matchWrap());
    }

    private void textRow(LinearLayout parent, String labelText, TextGetter getter,
                         TextSetter setter, String hintText) {
        label(parent, labelText);
        EditText e = new EditText(act);
        e.setText(getter.get() == null ? "" : getter.get());
        e.setHint(hintText);
        e.setTextSize(13);
        e.setTextColor(0xF2FFFFFF);
        e.setHintTextColor(0x50FFFFFF);
        e.setBackground(act.getDrawable(R.drawable.bg_glass_field));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(42);
        e.addTextChangedListener(new Watcher(setter, onChange));
        parent.addView(e, lp);
        refreshers.add(() -> {
            String v = getter.get();
            if (v == null) v = "";
            if (!e.getText().toString().equals(v)) e.setText(v);
        });
    }

    /** 0..max 的滑块，progress 为整数，getter/setter 用真实值 */
    private void slider(LinearLayout parent, String labelText, float min, float max, float step,
                        Getter get, FloatSetter set, Fmt fmt) {
        final int span = Math.round((max - min) / step);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(0, dp(12), 0, 0);
        TextView l = new TextView(act);
        l.setText(labelText);
        l.setTextSize(11.5f);
        l.setTextColor(0xB3FFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        head.addView(l, llp);
        TextView val = new TextView(act);
        val.setTextSize(11.5f);
        val.setTextColor(0xF0FFFFFF);
        val.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        head.addView(val);
        parent.addView(head, matchWrap());

        SeekBar bar = new SeekBar(act);
        bar.setMax(span);
        bar.setProgressDrawable(act.getDrawable(R.drawable.seek_track));
        bar.setThumb(act.getDrawable(R.drawable.seek_thumb));
        bar.setSplitTrack(false);
        bar.setPadding(dp(13), 0, dp(13), 0);
        LinearLayout.LayoutParams blp = matchWrap();
        blp.height = dp(36);
        parent.addView(bar, blp);

        final Runnable sync = () -> {
            float v = get.get();
            int p = Math.round((v - min) / step);
            if (p < 0) p = 0;
            if (p > span) p = span;
            if (bar.getProgress() != p) bar.setProgress(p);
            val.setText(fmt.fmt(min + p * step));
        };
        bar.setOnSeekBarChangeListener(new BarListener(p -> {
            float v = min + p * step;
            set.set(v);
            val.setText(fmt.fmt(v));
            bar.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            onChange.run();
        }));
        sync.run();
        refreshers.add(sync);
    }

    /** 单选的胶囊组（自动换行，每行最多 4 个） */
    private void segmentedWrap(LinearLayout parent, String[] options, IntSetter set, IntGetter current) {
        final int perRow = 4;
        TextView[] chips = new TextView[options.length];
        final Runnable sync = () -> {
            int cur = current.get();
            for (int i = 0; i < chips.length; i++) chips[i].setSelected(i == cur);
        };
        for (int start = 0; start < options.length; start += perRow) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, start == 0 ? dp(4) : dp(6), 0, dp(2));
            int count = Math.min(perRow, options.length - start);
            for (int k = 0; k < count; k++) {
                final int idx = start + k;
                TextView c = chip(options[idx]);
                c.setOnClickListener(v -> { set.set(idx); sync.run(); onChange.run(); });
                chips[idx] = c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
                if (k > 0) lp.leftMargin = dp(6);
                row.addView(c, lp);
            }
            // 最后一行不足 4 个时补空位，保持列宽一致
            for (int k = count; k < perRow; k++) {
                View spacer = new View(act);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
                if (k > 0) lp.leftMargin = dp(6);
                row.addView(spacer, lp);
            }
            parent.addView(row, matchWrap());
        }
        sync.run();
        refreshers.add(sync);
    }

    /** 单选的胶囊组 */
    private void segmented(LinearLayout parent, String[] options, IntGetter get,
                           IntSetter set, IntGetter current) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(2));
        TextView[] chips = new TextView[options.length];

        Runnable sync = () -> {
            int cur = current.get();
            for (int i = 0; i < chips.length; i++) chips[i].setSelected(i == cur);
        };

        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView c = chip(options[i]);
            c.setOnClickListener(v -> {
                set.set(idx);
                sync.run();
                onChange.run();
            });
            chips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(c, lp);
        }
        parent.addView(row, matchWrap());
        sync.run();
        refreshers.add(sync);
    }

    /** 多选开关片 */
    private void chips(LinearLayout parent, String[] options, BoolArrGetter get, BoolArrSetter set) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(2));
        TextView[] chips = new TextView[options.length];

        Runnable sync = () -> {
            boolean[] cur = get.get();
            for (int i = 0; i < chips.length; i++) chips[i].setSelected(i < cur.length && cur[i]);
        };

        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView c = chip(options[i]);
            c.setOnClickListener(v -> {
                boolean on = !v.isSelected();
                v.setSelected(on);
                set.set(idx, on);
                onChange.run();
            });
            chips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(c, lp);
        }
        parent.addView(row, matchWrap());
        sync.run();
        refreshers.add(sync);
    }

    private void button(LinearLayout parent, String text, Runnable onClick) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setTextColor(0xF0FFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackground(act.getDrawable(R.drawable.bg_btn));
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.height = dp(40);
        lp.topMargin = dp(10);
        parent.addView(b, lp);
    }

    private TextView chip(String text) {
        TextView c = new TextView(act);
        c.setText(text);
        c.setTextSize(12f);
        c.setGravity(Gravity.CENTER);
        c.setTextColor(0xE6FFFFFF);
        c.setBackground(act.getDrawable(R.drawable.bg_chip));
        c.setClickable(true);
        return c;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    /* ── 回调接口（避免匿名内部类：d8 对匿名类会崩） ── */

    interface Getter { float get(); }
    interface FloatSetter { void set(float v); }
    interface TextSetter { void set(String s); }
    interface TextGetter { String get(); }
    interface Fmt { String fmt(float progress); }
    interface IntGetter { int get(); }
    interface IntSetter { void set(int v); }
    interface BoolArrGetter { boolean[] get(); }
    interface BoolArrSetter { void set(int index, boolean on); }
    interface FloatCb { void on(float progress); }

    private static class BarListener implements SeekBar.OnSeekBarChangeListener {
        private final FloatCb cb;

        BarListener(FloatCb cb) {
            this.cb = cb;
        }

        @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            if (fromUser) cb.on(progress);
        }

        @Override public void onStartTrackingTouch(SeekBar sb) { }

        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    private static class Watcher implements TextWatcher {
        private final TextSetter setter;
        private final Runnable onChange;

        Watcher(TextSetter setter, Runnable onChange) {
            this.setter = setter;
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

        @Override public void afterTextChanged(Editable s) {
            setter.set(s.toString());
            onChange.run();
        }
    }

    static int colorAlpha(int base, int alpha) {
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base));
    }
}
