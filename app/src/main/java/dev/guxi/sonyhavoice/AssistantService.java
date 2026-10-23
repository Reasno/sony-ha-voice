package dev.guxi.sonyhavoice;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

public final class AssistantService extends VoiceInteractionService {
    private boolean available;
    @Override public void onReady() {
        super.onReady();
        available = true;
        if (new ConfigStore(this).autostart()) ConnectionService.start(this);
    }
    @Override public void onShutdown() { available = false; super.onShutdown(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ScreenshotCapture.ACTION.equals(intent.getAction())) {
            int id = intent.getIntExtra("capture_id", -1);
            try {
                if (!available) throw new IllegalStateException("系统助手尚未就绪，请重试");
                Bundle args = new Bundle(); args.putInt("capture_id", id);
                showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST
                    | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
            } catch (Exception error) { ScreenshotCapture.failed(id, error.getMessage()); }
        }
        return START_NOT_STICKY;
    }
}
