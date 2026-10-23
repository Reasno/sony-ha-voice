package dev.guxi.sonyhavoice;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.util.Log;

public final class AssistantSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            private int captureId = args == null ? -1 : args.getInt("capture_id", -1);
            private final Handler main = new Handler(Looper.getMainLooper());
            private final Runnable captureTimeout = () -> {
                ScreenshotCapture.failed(captureId, "系统截图超时，请重试");
                finish();
            };
            @Override public void onPrepareShow(Bundle args, int flags) {
                main.removeCallbacks(captureTimeout);
                setUiEnabled(false);
                super.onPrepareShow(args, flags);
            }
            @Override public void onShow(Bundle args, int flags) {
                super.onShow(args, flags);
                captureId = args == null ? -1 : args.getInt("capture_id", -1);
                Log.i("SonyHAVoice", "assistant_session capture=" + (captureId >= 0));
                if (captureId >= 0) {
                    main.postDelayed(captureTimeout, 8500);
                    return;
                }
                Log.i("SonyHAVoice", "assistant_session_shown");
                try {
                    startAssistantActivity(new Intent(AssistantSessionService.this, VoiceActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception error) {
                    Log.e("SonyHAVoice", "assistant_activity_start_failed");
                }
                finish();
            }
            @Override public void onHandleScreenshot(Bitmap screenshot) {
                if (captureId >= 0) {
                    main.removeCallbacks(captureTimeout);
                    ScreenshotCapture.receive(captureId, screenshot);
                    finish();
                } else if (screenshot != null) screenshot.recycle();
            }
            @Override public void onDestroy() {
                main.removeCallbacks(captureTimeout);
                super.onDestroy();
            }
        };
    }
}
