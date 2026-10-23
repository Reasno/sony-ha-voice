package dev.guxi.sonyhavoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.URI;
import java.util.HashMap;
import org.json.JSONObject;

public final class VoiceActivity extends ConnectedActivity {
    private TextView phase, transcript, answer, meter;
    private EditText textInput;
    private Button action;
    private Switch screenshotToggle;
    private ImageView screenshotPreview;
    private Bitmap screenshotBitmap;
    private byte[] screenshotJpeg;
    private boolean capturing;
    private VoiceOrb orb;
    private ScrollView speechView;
    private PcmRecorder recorder;
    private MediaPlayer player;
    private AudioFocusRequest focus;
    private boolean autoStart = true, listening, inSession, visible, resumed, textMode, played;
    private volatile int sessionGeneration;
    private boolean processing;
    private boolean playbackStarted;
    private int focusChangeGeneration;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable waitTimeout = () -> {
        if (autoStart) { autoStart = false; phase.setText("HA 连接未就绪，请检查设置后重试"); }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        textMode = getIntent().getBooleanExtra("text_mode", false);
        createOverlay();
        if (textMode) {
            autoStart = false;
            textInput = new EditText(this); textInput.setSingleLine(true);
            textInput.setTextColor(Color.WHITE);
            String input = getIntent().getStringExtra("text");
            textInput.setText(input == null ? "现在几点了？" : input); layout.addView(textInput, 1);
        }
        action.setText(textMode ? "发送文字" : "开始语音");
        action.setOnClickListener(v -> {
            if (listening) stopRecording();
            else requestTurn();
        });
        screenshotToggle.setChecked(new ConfigStore(this).screenshotEnabled());
        screenshotToggle.setOnCheckedChangeListener((button, enabled) -> {
            new ConfigStore(this).screenshotEnabled(enabled);
            if (textMode) {
                cancelSession(); phase.setText(enabled ? "按发送开始附图对话" : "按发送开始对话");
            }
            else requestTurn();
        });
        action.requestFocus();
        main.postDelayed(waitTimeout, 15000);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable surface(int color, float corner) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(corner));
        return background;
    }
    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }
    private Button pill(String value) {
        Button button = new Button(this);
        button.setText(value); button.setTextSize(14); button.setAllCaps(false);
        button.setTextColor(Color.WHITE); button.setPadding(dp(18), 0, dp(18), 0);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, surface(0xFFE5EDFF, 22));
        bg.addState(new int[]{}, surface(0x28FFFFFF, 22));
        button.setBackground(bg);
        button.setOnFocusChangeListener((view, focused) -> {
            button.setTextColor(focused ? 0xFF141C32 : Color.WHITE);
            view.animate().scaleX(focused ? 1.04f : 1).scaleY(focused ? 1.04f : 1).setDuration(140).start();
        });
        return button;
    }
    private void createOverlay() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00060913, 0x10060913, 0xA0060913}));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL); panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(24), dp(18));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xF025304B, 0xF0121727, 0xF022203B});
        background.setCornerRadius(dp(28)); background.setStroke(dp(1), 0x3DCDDCFF);
        panel.setBackground(background); panel.setElevation(dp(16));
        FrameLayout.LayoutParams outer = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        outer.setMargins(dp(36), dp(24), dp(36), dp(28)); root.addView(panel, outer);
        orb = new VoiceOrb(this);
        LinearLayout.LayoutParams orbSize = new LinearLayout.LayoutParams(dp(100), dp(100));
        orbSize.rightMargin = dp(22); panel.addView(orb, orbSize);
        screenshotPreview = new ImageView(this);
        screenshotPreview.setContentDescription("本轮电视截图");
        screenshotPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screenshotPreview.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewSize = new LinearLayout.LayoutParams(dp(128), dp(80));
        previewSize.rightMargin = dp(18); panel.addView(screenshotPreview, previewSize);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        panel.addView(layout, new LinearLayout.LayoutParams(0, -2, 1));
        TextView brand = label("Home Assistant", 12, 0xFFA9BBD9);
        brand.setLetterSpacing(.1f); layout.addView(brand);
        phase = label("等待 HA 就绪", 24, Color.WHITE);
        phase.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        phase.setPadding(0, dp(6), 0, dp(6)); layout.addView(phase);
        ScrollView speech = new ScrollView(this);
        speechView = speech;
        speech.setVisibility(View.GONE);
        speech.setFillViewport(false); speech.setClipToPadding(false);
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        transcript = label("", 16, 0xFFB9C9E2);
        transcript.setPadding(0, 0, 0, dp(6));
        answer = label("", 21, 0xFFF3F6FF);
        answer.setLineSpacing(dp(3), 1);
        words.addView(transcript); words.addView(answer); speech.addView(words);
        layout.addView(speech, new LinearLayout.LayoutParams(-1, dp(80)));
        LinearLayout footer = new LinearLayout(this); footer.setGravity(Gravity.CENTER_VERTICAL);
        meter = label("按语音键开始 · 返回关闭", 12, 0xFF9FAFCB);
        footer.addView(meter, new LinearLayout.LayoutParams(0, -2, 1));
        screenshotToggle = new Switch(this);
        screenshotToggle.setText("附电视截图"); screenshotToggle.setTextColor(Color.WHITE);
        screenshotToggle.setTextSize(13); screenshotToggle.setShowText(false);
        screenshotToggle.setSwitchPadding(dp(8)); screenshotToggle.setPadding(dp(8), 0, dp(8), 0);
        screenshotToggle.setBackground(surface(0x18FFFFFF, 18));
        screenshotToggle.setOnFocusChangeListener((view, focused) ->
            view.setBackground(surface(focused ? 0x555E86C2 : 0x18FFFFFF, 18)));
        footer.addView(screenshotToggle, new LinearLayout.LayoutParams(-2, dp(40)));
        action = pill("开始语音");
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-2, dp(40));
        actionSize.leftMargin = dp(12); footer.addView(action, actionSize);
        Button close = pill("关闭"); close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(-2, dp(40));
        closeSize.leftMargin = dp(12); footer.addView(close, closeSize);
        layout.addView(footer);
        // ConnectedActivity uses this only for its settings screens.
        statusView = new TextView(this);
        setContentView(root);
        panel.setAlpha(0); panel.setTranslationY(dp(24));
        panel.animate().alpha(1).translationY(0).setDuration(240).start();
    }
    @Override protected void onStart() { visible = true; super.onStart(); }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!textMode && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3);
        main.post(this::startPendingTurn);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 3 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
            && service != null && service.client().ready() && autoStart) startPendingTurn();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (textMode != intent.getBooleanExtra("text_mode", false)) {
            cancelSession();
            setIntent(intent);
            recreate();
            return;
        }
        setIntent(intent);
        if (textMode && intent.hasExtra("text")) textInput.setText(intent.getStringExtra("text"));
        if (listening) stopRecording();
        else requestTurn();
    }
    @Override public void status(String text, boolean ready) {
        // A cancellation can queue "connecting" before a newer connection has already become ready.
        if (!ready && inSession && service != null && !service.client().ready()) {
            cancelSession(); phase.setText("连接已断开，请稍后重试");
        }
        startPendingTurn();
    }
    private void requestTurn() {
        android.util.Log.i("SonyHAVoice", "turn_requested interrupt=" + (inSession || player != null));
        cancelSession();
        autoStart = true;
        phase.setText("正在连接");
        main.removeCallbacks(waitTimeout);
        main.postDelayed(waitTimeout, 15000);
        // New assistant intents may arrive between onPause and onResume.
        main.post(this::startPendingTurn);
    }
    private void startPendingTurn() {
        if (!visible || !resumed || !autoStart || service == null || !service.client().ready()) return;
        if (capturing) return;
        if (screenshotToggle.isChecked() && screenshotJpeg == null) {
            captureScreenshot(); return;
        }
        if (textMode || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startSession();
    }
    private void captureScreenshot() {
        capturing = true;
        android.util.Log.i("SonyHAVoice", "overlay_hidden_for_capture");
        main.removeCallbacks(waitTimeout);
        phase.setText("正在截取电视画面");
        int current = sessionGeneration;
        View decor = getWindow().getDecorView();
        decor.setAlpha(0);
        // Allow the translucent window to render without the assistant panel before capture.
        main.postDelayed(() -> {
            if (!visible || !capturing || current != sessionGeneration) return;
            ScreenshotCapture.request(this, (preview, jpeg, error) -> {
                if (!visible || current != sessionGeneration) {
                    if (preview != null) preview.recycle();
                    return;
                }
                capturing = false; decor.setAlpha(1);
                android.util.Log.i("SonyHAVoice", "overlay_restored_after_capture success=" + (error == null));
                if (error != null) {
                    if (preview != null) preview.recycle();
                    autoStart = false; phase.setText(error); action.setText("重试");
                    return;
                }
                screenshotBitmap = preview; screenshotJpeg = jpeg;
                screenshotPreview.setImageBitmap(preview);
                screenshotPreview.setVisibility(View.VISIBLE);
                main.post(this::startPendingTurn);
            });
        }, 200);
    }
    private void startSession() {
        if (service == null || !service.client().ready()) { phase.setText("HA 尚未就绪"); return; }
        if (inSession) return;
        if (!textMode && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3); return;
        }
        autoStart = false; main.removeCallbacks(waitTimeout);
        releasePlayer();
        abandonFocus();
        transcript.setText(""); answer.setText("");
        speechView.setVisibility(View.GONE);
        meter.setText(textMode ? "返回关闭" : "停顿自动发送 · 按确定结束录音");
        orb.active(true);
        int current = ++sessionGeneration;
        try {
            requestFocus();
            service.client().start(textMode ? textInput.getText().toString() : null, screenshotJpeg);
            inSession = true; played = false; processing = false;
            action.setText(textMode ? "重新发送" : "结束录音");
            if (textMode) { phase.setText("正在处理"); return; }
            phase.setText("正在聆听 · 现在请讲话");
            recorder = new PcmRecorder();
            listening = true;
            recorder.start(this, service.client().config(), new PcmRecorder.Listener() {
                @Override public void audio(byte[] pcm) {
                    if (service != null && sessionGeneration == current) service.client().audio(pcm);
                }
                @Override public void level(double rms, String route, long samples) {
                    if (!visible || current != sessionGeneration) return;
                    orb.level(rms);
                    android.util.Log.i("SonyHAVoice", "audio samples=" + samples
                        + " rms=" + Math.round(rms) + " route=" + route);
                }
                @Override public void ended(String error) {
                    if (!visible || current != sessionGeneration || !inSession) return;
                    listening = false; recorder = null;
                    if (error != null) { cancelSession(); phase.setText(error); }
                    else if (!processing) {
                        if (service != null) service.client().finishAudio();
                        phase.setText("正在识别"); action.setText("重新说");
                    }
                }
            });
        } catch (Exception error) { cancelSession(); phase.setText(error.getMessage()); }
    }
    private void stopRecording() {
        if (recorder != null) recorder.stop();
        listening = false;
        if (service != null) service.client().finishAudio();
        phase.setText("正在识别"); action.setText("重新说");
    }
    @Override public void event(String type, JSONObject data) {
        if (!visible || !inSession) return;
        if ("stt-end".equals(type)) {
            processing = true;
            stopRecording();
            JSONObject output = data.optJSONObject("stt_output");
            transcript.setText(output == null ? "" : output.optString("text"));
            speechView.setVisibility(View.VISIBLE);
            phase.setText("正在思考");
        } else if ("intent-end".equals(type)) {
            JSONObject output = data.optJSONObject("intent_output");
            if (output != null) {
                JSONObject response = output.optJSONObject("response");
                JSONObject speech = response == null ? null : response.optJSONObject("speech");
                JSONObject plain = speech == null ? null : speech.optJSONObject("plain");
                if (plain != null) {
                    answer.setText(plain.optString("speech"));
                    speechView.setVisibility(View.VISIBLE);
                }
            }
            phase.setText("正在准备回答");
            action.setText(textMode ? "发送文字" : "重新说");
        } else if ("image-start".equals(type)) {
            phase.setText("正在结合画面思考");
        } else if ("tts-end".equals(type)) {
            JSONObject output = data.optJSONObject("tts_output");
            if (output != null && !output.optString("url").isEmpty()) play(output.optString("url"));
        } else if ("run-end".equals(type)) {
            if (recorder != null) recorder.stop();
            listening = false; inSession = false; action.setText(textMode ? "发送文字" : "再次语音");
            if (!played) { phase.setText("已完成"); orb.active(false); abandonFocus(); }
        } else if ("client-error".equals(type)) {
            cancelSession(); phase.setText(data.optString("message", "会话失败"));
        }
    }
    private void play(String path) {
        try {
            JSONObject config = service.client().config();
            URI origin = new URI(config.optString("url"));
            URI audio = origin.resolve(path);
            if (!sameOrigin(origin, audio)) throw new IllegalArgumentException("回答音频地址不属于配置的 HA");
            releasePlayer();
            player = new MediaPlayer();
            int turn = sessionGeneration;
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            HashMap<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + config.optString("token"));
            headers.put("android-allow-cross-domain-redirect", "0");
            player.setDataSource(this, Uri.parse(audio.toString()), headers);
            player.setOnPreparedListener(media -> {
                if (player != media || turn != sessionGeneration || !resumed) return;
                playbackStarted = true; played = true; phase.setText("正在回答"); media.start();
                android.util.Log.i("SonyHAVoice", "tts_playback_started");
            });
            player.setOnCompletionListener(media -> {
                if (player != media || turn != sessionGeneration) return;
                phase.setText("还需要什么帮助？"); orb.active(false);
                meter.setText("按语音键继续 · 返回关闭"); releasePlayer(); abandonFocus();
                android.util.Log.i("SonyHAVoice", "tts_playback_completed");
            });
            player.setOnErrorListener((media, what, extra) -> {
                if (player != media || turn != sessionGeneration) return true;
                phase.setText("回答音频播放失败"); orb.active(false);
                releasePlayer(); abandonFocus(); return true;
            });
            player.prepareAsync();
            played = true;
        } catch (Exception error) {
            phase.setText("回答音频播放失败：" + error.getMessage());
            orb.active(false);
            releasePlayer(); abandonFocus();
        }
    }
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equals(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
            && port(a) == port(b) && b.getUserInfo() == null;
    }
    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    }
    private void requestFocus() {
        AudioFocusRequest[] owner = new AudioFocusRequest[1];
        owner[0] = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (focus != owner[0]) return;
                android.util.Log.i("SonyHAVoice", "audio_focus_change=" + change);
                if (change == AudioManager.AUDIOFOCUS_LOSS
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    // A new Sony assistant invocation can temporarily take focus before delivering its intent.
                    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        int changeGeneration = ++focusChangeGeneration;
                        if (player != null && playbackStarted && player.isPlaying()) player.pause();
                        main.postDelayed(() -> {
                            if (focus == owner[0] && changeGeneration == focusChangeGeneration) {
                                cancelSession(); phase.setText("音频已暂停，按语音键继续");
                            }
                        }, 350);
                    } else {
                        cancelSession(); phase.setText("音频已暂停，按语音键继续");
                    }
                } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    focusChangeGeneration++;
                    if (player != null && playbackStarted && !player.isPlaying()) player.start();
                }
            }).build();
        focus = owner[0];
        if (getSystemService(AudioManager.class).requestAudioFocus(focus)
            != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("无法获得音频焦点");
    }
    private void abandonFocus() {
        focusChangeGeneration++;
        AudioFocusRequest previous = focus;
        focus = null;
        if (previous != null) getSystemService(AudioManager.class).abandonAudioFocusRequest(previous);
    }
    private void releasePlayer() {
        playbackStarted = false;
        MediaPlayer previous = player;
        player = null;
        if (previous != null) {
            previous.setOnPreparedListener(null);
            previous.setOnCompletionListener(null);
            previous.setOnErrorListener(null);
            previous.release();
        }
    }
    private void cancelSession() {
        sessionGeneration++; autoStart = false; inSession = false; listening = false;
        capturing = false; ScreenshotCapture.cancel();
        if (getWindow() != null) getWindow().getDecorView().setAlpha(1);
        if (screenshotPreview != null) {
            screenshotPreview.setImageDrawable(null); screenshotPreview.setVisibility(View.GONE);
        }
        if (screenshotBitmap != null) screenshotBitmap.recycle();
        screenshotBitmap = null; screenshotJpeg = null;
        if (recorder != null) { recorder.stop(); recorder = null; }
        if (service != null) service.client().cancel();
        releasePlayer(); abandonFocus();
        if (orb != null) orb.active(false);
        if (meter != null) meter.setText("按语音键继续 · 返回关闭");
        if (action != null) action.setText(textMode ? "发送文字" : "再次语音");
    }
    @Override protected void onStop() {
        visible = false; cancelSession(); main.removeCallbacks(waitTimeout);
        super.onStop();
    }
    @Override protected void onPause() {
        resumed = false;
        // Home, a new app, or another foreground interaction ends microphone capture immediately.
        if (inSession || player != null) { cancelSession(); phase.setText("已暂停"); }
        super.onPause();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_SEARCH || key == KeyEvent.KEYCODE_VOICE_ASSIST
                || key == KeyEvent.KEYCODE_ASSIST) {
            if (event.getRepeatCount() == 0) {
                if (textMode) {
                    startActivity(new Intent(this, VoiceActivity.class).setAction(Intent.ACTION_ASSIST));
                } else if (listening) stopRecording();
                else requestTurn();
            }
            return true;
        }
        return super.onKeyDown(key, event);
    }
}
