package dev.guxi.sonyhavoice;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** A small animated voice indicator. Microphone levels come from the active recorder. */
final class VoiceOrb extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader glow, sphere;
    private float radius, level, target;
    private boolean active;
    VoiceOrb(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    void active(boolean value) { active = value; if (!value) target = 0; invalidate(); }
    void level(double rms) { target = (float)Math.min(1, rms / 2400.0); invalidate(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        radius = Math.min(w, h) * .36f;
        glow = new RadialGradient(w / 2f, h / 2f, radius * 1.4f,
            new int[]{0x704C7CFF, 0x354C9BFF, 0x004C9BFF}, null, Shader.TileMode.CLAMP);
        sphere = new LinearGradient(0, 0, w, h,
            new int[]{0xFF7CF2DE, 0xFF598AFF, 0xFFAE6FFF, 0xFFF2B5D9}, null, Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        level += (target - level) * .2f;
        float breathe = active ? (float)Math.sin(SystemClock.uptimeMillis() / 450.0) * .035f : 0;
        float r = radius * (1 + breathe + .1f * level);
        paint.setShader(glow); canvas.drawCircle(cx, cy, radius * 1.4f, paint);
        paint.setShader(sphere); canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null); paint.setColor(0xEDFFFFFF);
        for (int i = -2; i <= 2; i++) {
            float motion = active ? (float)Math.abs(Math.sin(SystemClock.uptimeMillis() / 210.0 + i)) : .2f;
            float bar = r * (.19f + (2 - Math.abs(i)) * .12f + level * .4f * motion);
            float x = cx + i * r * .29f;
            canvas.drawRoundRect(x - r * .055f, cy - bar, x + r * .055f, cy + bar,
                r * .055f, r * .055f, paint);
        }
        if (active && isShown() && hasWindowFocus()) postInvalidateDelayed(32);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) invalidate();
    }
}
