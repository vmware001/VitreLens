package com.vitrelens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/**
 * HSB 轮盘：外圈选色相，中间方块横向调饱和度、纵向调明度。
 *
 * <p>色环用 SweepGradient（起点 3 点钟、顺时针），方块是「纯色相 → 白」再叠「透明 → 黑」。
 * 手指落在环上改色相，落在方块里改 S/V。
 */
class HsbWheelView extends View {

    interface OnChange {
        void onChange(float hue, float sat, float val);
    }

    private float hue = 0f, sat = 0.6f, val = 1f;
    private OnChange listener;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sq = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sqRect = new RectF();

    private static final int[] HUES = {
            0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6,
            0xFF007AFF, 0xFFAF52DE, 0xFFFF3B30};

    HsbWheelView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        marker.setStyle(Paint.Style.STROKE);
        marker.setColor(0xFFFFFFFF);
    }

    void setColor(float h, float s, float v) {
        hue = h < 0 ? 0f : h;
        sat = Math.max(0f, Math.min(1f, s));
        val = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    void setOnChange(OnChange l) {
        listener = l;
    }

    private float strokeW() {
        return Math.max(14f, Math.min(getWidth(), getHeight()) * 0.13f);
    }

    private float outerR() {
        return Math.min(getWidth(), getHeight()) / 2f - strokeW() / 2f;
    }

    private float innerR() {
        return outerR() - strokeW();
    }

    private void square() {
        float side = innerR() * 1.30f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        sqRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;

        // 色相环
        ring.setStrokeWidth(strokeW());
        ring.setShader(new SweepGradient(cx, cy, HUES, null));
        c.drawCircle(cx, cy, outerR(), ring);
        ring.setShader(null);

        // 饱和度 / 明度方块
        square();
        sq.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
        c.drawRect(sqRect, sq);

        grad.setShader(new LinearGradient(sqRect.left, 0, sqRect.right, 0,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(sqRect, grad);

        grad.setShader(new LinearGradient(0, sqRect.top, 0, sqRect.bottom,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(sqRect, grad);
        grad.setShader(null);

        // 方块描边
        sq.setStyle(Paint.Style.STROKE);
        sq.setStrokeWidth(Math.max(1f, strokeW() * 0.10f));
        sq.setColor(0x40FFFFFF);
        c.drawRect(sqRect, sq);
        sq.setStyle(Paint.Style.FILL);

        // 方块里的标记
        float mx = sqRect.left + sqRect.width() * sat;
        float my = sqRect.top + sqRect.height() * (1f - val);
        marker.setStrokeWidth(Math.max(2f, strokeW() * 0.22f));
        c.drawCircle(mx, my, strokeW() * 0.38f, marker);

        // 环上的标记
        double a = Math.toRadians(hue);
        float hx = (float) (cx + Math.cos(a) * outerR());
        float hy = (float) (cy + Math.sin(a) * outerR());
        marker.setStrokeWidth(Math.max(2f, strokeW() * 0.30f));
        c.drawCircle(hx, hy, strokeW() * 0.42f, marker);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_MOVE) return true;

        square();
        float x = e.getX(), y = e.getY();
        boolean changed = false;

        if (sqRect.contains(x, y)) {
            sat = Math.max(0f, Math.min(1f, (x - sqRect.left) / sqRect.width()));
            val = Math.max(0f, Math.min(1f, 1f - (y - sqRect.top) / sqRect.height()));
            changed = true;
        } else {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float dist = (float) Math.hypot(x - cx, y - cy);
            if (dist > innerR() * 0.92f) {
                float deg = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                if (deg < 0) deg += 360f;
                hue = deg;
                changed = true;
            }
        }
        if (changed) {
            if (listener != null) listener.onChange(hue, sat, val);
            invalidate();
        }
        return true;
    }
}
