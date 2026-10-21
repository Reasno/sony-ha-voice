package dev.guxi.sonyhavoice;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.util.Log;

public final class AssistantSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            @Override public void onShow(Bundle args, int flags) {
                super.onShow(args, flags);
                Log.i("SonyHAVoice", "assistant_session_shown");
                setUiEnabled(false);
                try {
                    startAssistantActivity(new Intent(AssistantSessionService.this, VoiceActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception error) {
                    Log.e("SonyHAVoice", "assistant_activity_start_failed");
                }
                finish();
            }
        };
    }
}
