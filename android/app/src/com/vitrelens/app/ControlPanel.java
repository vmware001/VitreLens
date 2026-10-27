package com.vitrelens.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private static final double WHITE_GAMMA = 2.6;

    private final Activity act;
    private final WatermarkPainter.Style st;
    private final Runnable onChange;
    private final List<Runnable> refreshers = new ArrayList<>();

    ControlPanel(Activity act, WatermarkPainter.Style st, Runnable onChange) {
        this.act = act;
        this.st = st;
        this.onChange = onChange;
    }

    List<Runnable> build(LinearLayout root) {
        root.removeAllViews();

        // ── 01 水印内容
        LinearLayout c1 = card(root, "01", "水印内容");
        textRow(c1, "相机名称", () -> st.name, s -> st.name = s, "OPPO Find X9 Ultra");
        textRow(c1, "副标题", () -> st.subtitle, s -> st.subtitle = s, "哈苏影像 · 光影追寻");
        textRow(c1, "拍摄参数", () -> st.params, s -> st.params = s, "23mm ƒ/1.8 1/287s ISO50");
        textRow(c1, "时间 / 地点", () -> st.meta, s -> st.meta = s, "2026.10.26 12:06:26");

        label(c1, "相机品牌 Logo");
        segmented(c1, new String[]{"无", "徕卡", "哈苏", "蔡司", "理光"},
                () -> st.brand, v -> { st.brand = v; st.showBrand = v > 0; },
                () -> st.brand);

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
        LinearLayout c2 = card(root, "02", "版式");
        label(c2, "水印位置");
        segmented(c2, new String[]{"左下", "居中", "右下"},
                () -> alignIndex(st.align),
                v -> st.align = v == 0 ? "left" : (v == 2 ? "right" : "center"),
                () -> alignIndex(st.align));

        slider(c2, "水印宽度", 50, 100, 1, () -> st.widthPct, v -> st.widthPct = v, p -> (int) p + "%");
        slider(c2, "玻璃高度", 100, 300, 1, () -> st.glassHeightPct, v -> st.glassHeightPct = v,
                p -> Math.round(p) + "%");
        slider(c2, "距底高度", 0, 12, 0.1f, () -> st.gapPct, v -> st.gapPct = v,
                p -> String.format(Locale.US, "%.1f%%", p));
        slider(c2, "机型字号", 14, 48, 0.5f, () -> st.nameSize, v -> st.nameSize = v,
                p -> String.format(Locale.US, "%.0f", p));
        slider(c2, "参数字号", 6, 20, 0.5f, () -> st.metaSize, v -> st.metaSize = v,
                p -> String.format(Locale.US, "%.1f", p));

        label(c2, "字体");
        segmented(c2, new String[]{"无衬线", "窄体", "衬线", "等宽"},
                () -> fontIndex(st.font),
                v -> st.font = new String[]{"sans", "condensed", "serif", "mono"}[v],
                () -> fontIndex(st.font));

        label(c2, "文字材质");
        segmented(c2, new String[]{"液态玻璃", "磨砂玻璃", "不透明"},
                () -> matIndex(st.nameMaterial),
                v -> st.nameMaterial = new String[]{"liquid", "frost", "solid"}[v],
                () -> matIndex(st.nameMaterial));

        chips(c2, new String[]{"斜体字形"},
                () -> new boolean[]{st.italic},
                (i, on) -> st.italic = on);

        // ── 03 液态玻璃
        LinearLayout c3 = card(root, "03", "液态玻璃");
        label(c3, "预设");
        segmented(c3, new String[]{"清透", "磨砂", "厚玻璃"},
                () -> -1, v -> applyPreset(v), () -> -1);
        hint(c3, "预设会覆盖下面所有玻璃参数，可再单独微调");

        label(c3, "我的预设");
        presetRow(c3);

        slider(c3, "玻璃折射率", 1.00f, 2.00f, 0.01f, () -> st.ior, v -> st.ior = v,
                p -> String.format(Locale.US, "%.2f", p));
        slider(c3, "圆角 R", 0, 100, 1, () -> st.radiusPct, v -> st.radiusPct = v, p -> Math.round(p) + "%");
        slider(c3, "深度", 0, 100, 1, () -> st.depthPct, v -> st.depthPct = v, p -> Math.round(p) + "%");
        slider(c3, "厚度", 0, 100, 1, () -> st.thicknessPct, v -> st.thicknessPct = v, p -> Math.round(p) + "%");
        slider(c3, "粗糙度", 0, 100, 1, () -> st.roughPct, v -> st.roughPct = v, p -> Math.round(p) + "%");
        slider(c3, "色散", 0, 200, 1, () -> st.dispPct, v -> st.dispPct = v, p -> Math.round(p) + "%");
        slider(c3, "横向扭曲", 0, 200, 1, () -> st.warpPct, v -> st.warpPct = v, p -> Math.round(p) + "%");
        slider(c3, "反射率", 0, 100, 1, () -> st.reflPct, v -> st.reflPct = v, p -> Math.round(p) + "%");
        slider(c3, "透射率", 0, 100, 1, () -> st.transPct, v -> st.transPct = v, p -> Math.round(p) + "%");
        slider(c3, "高光", 0, 150, 1, () -> st.glossPct, v -> st.glossPct = v, p -> Math.round(p) + "%");
        slider(c3, "投影", 0, 100, 1, () -> st.shadowPct, v -> st.shadowPct = v, p -> Math.round(p) + "%");
        slider(c3, "加白", 0, 200, 1, () -> st.whitePct, v -> st.whitePct = v, p -> Math.round(p) + "%");
        slider(c3, "细分", 8, 128, 4, () -> st.segments, v -> st.segments = Math.round(v),
                p -> String.valueOf(Math.round(p)));
        chips(c3, new String[]{"色调"},
                () -> new boolean[]{st.tintOn},
                (i, on) -> st.tintOn = on);
        hint(c3, "参数与 @specy/liquid-glass 一致：真实光线折射 + 厚度 + 粗糙度 + 菲涅尔反射。");

        // ── 04 导出
        LinearLayout c4 = card(root, "04", "导出");
        chips(c4, new String[]{"实况照片水印"},
                () -> new boolean[]{videoOn},
                (i, on) -> videoOn = on);
        hint(c4, "勾上后，实况照片的视频段也会重新编码并打上同样的水印。");
        slider(c4, "视频水印高度", -4, 14, 0.2f,
                () -> st.videoGapShift, v -> st.videoGapShift = v,
                p -> String.format(Locale.US, "%+.1f%%", p));
        slider(c4, "视频水印宽度", 85, 130, 0.5f,
                () -> st.videoWidthScale, v -> st.videoWidthScale = v,
                p -> Math.round(p) + "%");
        hint(c4, "实测 OPPO 实况照片的视频取景约为照片的 94%（贴着底边裁），"
                + "所以视频里的水印默认上移 5%、宽度放大 6%。不满意可自己微调。");

        return refreshers;
    }

    /* ── 外部状态 ── */

    boolean videoOn = true;

    void refreshAll() {
        for (Runnable r : refreshers) r.run();
    }

    void applyPreset(int idx) {
        // {ior, depth, thickness, rough, disp, refl, trans, gloss, shadow}
        float[][] p = {
                {1.46f, 34, 42, 12, 18, 90, 100, 45, 40},
                {1.30f, 46, 56, 72, 10, 68, 96, 26, 34},
                {1.72f, 72, 88, 8, 46, 100, 100, 82, 58}
        };
        float[] v = p[Math.max(0, Math.min(2, idx))];
        st.ior = v[0];
        st.depthPct = v[1];
        st.thicknessPct = v[2];
        st.roughPct = v[3];
        st.dispPct = v[4];
        st.reflPct = v[5];
        st.transPct = v[6];
        st.glossPct = v[7];
        st.shadowPct = v[8];
        refreshAll();
        onChange.run();
    }

    /* ── 我的预设（最多 3 个，存 SharedPreferences） ── */

    private static final String[] P_KEYS = {"p1", "p2", "p3"};
    private int activePreset = -1;

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

    static float whiteValue(float slider) {
        return (float) (100 * Math.pow(Math.min(1.0, slider / 100.0), WHITE_GAMMA));
    }

    static float whiteSlider(float value) {
        return (float) (100 * Math.pow(Math.min(1.0, value / 100.0), 1 / WHITE_GAMMA));
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

    private LinearLayout card(LinearLayout root, String num, String title) {
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(16), dp(6), dp(6));

        TextView n = new TextView(act);
        n.setText(num);
        n.setTextSize(11);
        n.setTextColor(0x88FFFFFF);
        n.setLetterSpacing(0.12f);
        n.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        head.addView(n);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(0xF2FFFFFF);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setPadding(dp(10), 0, 0, 0);
        head.addView(t);
        root.addView(head, matchWrap());

        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(act.getDrawable(R.drawable.bg_glass_card));
        c.setPadding(dp(14), dp(6), dp(14), dp(10));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(14);
        root.addView(c, lp);
        return c;
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
            onChange.run();
        }));
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
