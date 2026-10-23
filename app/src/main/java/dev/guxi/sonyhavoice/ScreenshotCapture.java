package dev.guxi.sonyhavoice;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;

/** One user-requested screenshot supplied by the Android assistant session. */
final class ScreenshotCapture {
    interface Callback { void complete(Bitmap preview, byte[] jpeg, String error); }
    static final String ACTION = "dev.guxi.sonyhavoice.CAPTURE_SCREEN";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Callback pending;
    private static int sequence;
    private static Runnable timeout;

    static int request(Context context, Callback callback) {
        cancel();
        int id = ++sequence;
        pending = callback;
        android.util.Log.i("SonyHAVoice", "capture_requested id=" + id);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = context.getSystemService(RoleManager.class);
                if (roles == null || !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                    throw new IllegalStateException("请先将 Sony HA Voice 设为系统助手");
            }
            if (Settings.Secure.getInt(context.getContentResolver(), "assist_screenshot_enabled", 1) == 0) {
                if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED)
                    throw new IllegalStateException("请在系统助手设置中允许使用屏幕截图");
                if (!Settings.Secure.putInt(context.getContentResolver(), "assist_screenshot_enabled", 1))
                    throw new IllegalStateException("无法开启系统助手截图权限");
            }
            timeout = () -> deliver(id, null, null, "系统未提供截图，请检查助手截图权限");
            main.postDelayed(timeout, 8000);
            context.startService(new Intent(context, AssistantService.class).setAction(ACTION)
                .putExtra("capture_id", id));
        } catch (Exception error) {
            main.post(() -> deliver(id, null, null, error.getMessage()));
        }
        return id;
    }

    static boolean waiting(int id) { return pending != null && id == sequence; }

    static void receive(int id, Bitmap bitmap) {
        android.util.Log.i("SonyHAVoice", "capture_received id=" + id
            + " waiting=" + waiting(id) + " available=" + (bitmap != null));
        if (!waiting(id)) { if (bitmap != null) bitmap.recycle(); return; }
        if (bitmap == null) {
            deliver(id, null, null, "当前画面无法截取，受保护的视频内容可能不允许截图");
            return;
        }
        // Avoid retaining a full-resolution TV framebuffer or compressing it on the UI thread.
        new Thread(() -> {
            Bitmap preview = null;
            byte[] jpeg = null;
            String error = null;
            try {
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                float scale = Math.min(1f, 1280f / Math.max(width, height));
                preview = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), true);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                preview.compress(Bitmap.CompressFormat.JPEG, 85, output);
                jpeg = output.toByteArray();
                if (jpeg.length == 0 || jpeg.length > 2 * 1024 * 1024)
                    throw new IllegalStateException("截图大小超出限制");
            } catch (Exception failure) { error = "截图处理失败"; }
            if (preview != bitmap) bitmap.recycle();
            final Bitmap result = preview;
            final byte[] data = jpeg;
            final String message = error;
            main.post(() -> deliver(id, result, data, message));
        }, "assistant-screenshot").start();
    }

    static void failed(int id, String error) { deliver(id, null, null, error); }

    private static void deliver(int id, Bitmap preview, byte[] jpeg, String error) {
        if (!waiting(id)) { if (preview != null) preview.recycle(); return; }
        Callback callback = pending;
        pending = null;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        android.util.Log.i("SonyHAVoice", "capture_complete success=" + (error == null));
        callback.complete(preview, jpeg, error);
    }

    static void cancel() {
        sequence++;
        pending = null;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }
}
