package dev.guxi.sonyhavoice;

import android.service.voice.VoiceInteractionService;

public final class AssistantService extends VoiceInteractionService {
    @Override public void onReady() {
        super.onReady();
        if (new ConfigStore(this).autostart()) ConnectionService.start(this);
    }
}
