package dev.guxi.sonyhavoice;

import android.content.Intent;
import android.speech.SpeechRecognizer;

public final class RecognitionService extends android.speech.RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try { callback.error(SpeechRecognizer.ERROR_CLIENT); } catch (Exception ignored) {}
    }
    @Override protected void onCancel(Callback callback) {}
    @Override protected void onStopListening(Callback callback) {}
}
