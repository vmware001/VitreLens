package com.vitrelens.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/**
 * 色相轮盘：外圈拖一圈选色相，中间那小块点一下回到「自动」（白/黑随背景）。
 *
 * <p>用 SweepGradient 画色环（起点 3 点钟、顺时针），指针位置就是角度，
 * 所以取色只要算 atan2。
 */
class HueWheelView extends View {

    interface OnHue {
        /** @param hue 0~360；-1 表示关闭（自动） */
        void onHue(float hue);
    }

    private float hue = -1f;
    private OnHue listener;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);

    HueWheelView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        thumb.setStyle(Paint.Style.STROKE);
        thumb.setColor(0xFFFFFFFF);
    }

    void setHue(float h) {
        hue = h;
        invalidate();
    }

    void setOnHue(OnHue l) {
        listener = l;
    }

    private static final int[] COLORS = {
            0xFFFF3B30, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6,
            0xFF007AFF, 0xFFAF52DE, 0xFFFF3B30};

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float stroke = Math.max(6f, Math.min(w, h) * 0.15f);
        float r = Math.min(w, h) / 2f - stroke / 2f;

        ring.setStrokeWidth(stroke);
        ring.setShader(new SweepGradient(cx, cy, COLORS, null));
        c.drawCircle(cx, cy, r, ring);
        ring.setShader(null);

        // 中心：点一下回到自动
        mid.setStyle(Paint.Style.FILL);
        mid.setColor(hue < 0 ? 0xFFFFFFFF : Color.HSVToColor(new float[]{hue, 0.62f, 1f}));
        c.drawCircle(cx, cy, r * 0.44f, mid);
        mid.setStyle(Paint.Style.STROKE);
        mid.setStrokeWidth(Math.max(1f, stroke * 0.14f));
        mid.setColor(0x66FFFFFF);
        c.drawCircle(cx, cy, r * 0.44f, mid);

        if (hue >= 0) {
            double a = Math.toRadians(hue);
            float px = (float) (cx + Math.cos(a) * r);
            float py = (float) (cy + Math.sin(a) * r);
            thumb.setStrokeWidth(Math.max(2f, stroke * 0.30f));
            c.drawCircle(px, py, stroke * 0.42f, thumb);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN
                && e.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return true;
        }
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float dx = e.getX() - cx, dy = e.getY() - cy;
        float dist = (float) Math.hypot(dx, dy);
        float r = Math.min(getWidth(), getHeight()) / 2f;

        if (dist < r * 0.42f) {
            // 中间：关闭色相，回到白/黑自适应
            if (hue >= 0f) {
                hue = -1f;
                if (listener != null) listener.onHue(-1f);
                invalidate();
            }
            return true;
        }
        float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (deg < 0) deg += 360f;
        hue = deg;
        if (listener != null) listener.onHue(deg);
        invalidate();
        return true;
    }
}
