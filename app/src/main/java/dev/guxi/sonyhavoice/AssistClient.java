package dev.guxi.sonyhavoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

final class AssistClient {
    interface Listener {
        void status(String status, boolean ready);
        void event(String type, JSONObject data);
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConfigStore store;
    private final Listener listener;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).build();
    private WebSocket socket;
    private JSONObject config;
    private boolean closed, ready, finishing, sttReady, refreshing, audioStage;
    private int nextId = 1, runId, handlerId = -1, attempt, generation;
    private int turnGeneration;
    private String conversationId;
    private long conversationUpdatedAt;
    private static final long CONVERSATION_IDLE_MS = 10 * 60 * 1000L;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final Runnable deadline = () -> failRun("会话超时");
    private final Runnable retry = () -> connect();
    private final Runnable authDeadline = () -> reconnect("连接超时");

    AssistClient(Context context, Listener listener) {
        this.store = new ConfigStore(context);
        this.listener = listener;
    }
    synchronized boolean ready() { return ready; }
    synchronized boolean active() { return runId != 0; }
    synchronized JSONObject config() { return config == null ? store.load() : config; }
    synchronized void connect() {
        if (closed || socket != null || refreshing) return;
        config = store.load();
        if (config.optString("token").isEmpty() && config.optString("refresh_token").isEmpty()) {
            status("请先配置 HA 地址并授权");
            return;
        }
        if (!config.optString("refresh_token").isEmpty()
            && config.optLong("expires_at") < System.currentTimeMillis() + 60000) {
            refresh();
            return;
        }
        status("正在连接 HA");
        int gen = ++generation;
        String url = config.optString("url").replaceFirst("^http", "ws") + "/api/websocket";
        socket = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                synchronized (AssistClient.this) {
                    if (gen != generation || closed) return;
                    try { receive(new JSONObject(text)); }
                    catch (Exception error) { reconnect("HA 协议响应异常"); }
                }
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                synchronized (AssistClient.this) {
                    if (gen == generation && !closed) reconnect("连接断开，正在重连");
                }
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (AssistClient.this) {
                    if (gen == generation && !closed) reconnect("连接已关闭");
                }
            }
        });
        main.removeCallbacks(authDeadline);
        main.postDelayed(authDeadline, 15000);
    }
    private synchronized void refresh() {
        refreshing = true;
        status("正在更新授权");
        Request request = new Request.Builder().url(config.optString("url") + "/auth/token")
            .post(new FormBody.Builder().add("grant_type", "refresh_token")
                .add("refresh_token", config.optString("refresh_token"))
                .add("client_id", config.optString("client_id")).build()).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException error) {
                synchronized (AssistClient.this) {
                    refreshing = false;
                    if (!closed) scheduleRetry("授权更新失败，正在重试");
                }
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response result = response) {
                    synchronized (AssistClient.this) {
                        refreshing = false;
                        if (closed) return;
                        if (result.code() == 400 || result.code() == 401) {
                            status("授权已失效，请重新授权");
                            return;
                        }
                        if (!result.isSuccessful()) {
                            scheduleRetry("HA 授权服务暂不可用");
                            return;
                        }
                        JSONObject token = new JSONObject(result.body().string());
                        config.put("token", token.getString("access_token"));
                        config.put("expires_at", System.currentTimeMillis()
                            + token.getLong("expires_in") * 1000);
                        store.save(config);
                        connect();
                    }
                } catch (Exception error) {
                    synchronized (AssistClient.this) {
                        refreshing = false;
                        if (!closed) scheduleRetry("授权响应异常");
                    }
                }
            }
        });
    }
    private void receive(JSONObject message) throws Exception {
        String type = message.optString("type");
        if ("auth_required".equals(type)) {
            send(new JSONObject().put("type", "auth").put("access_token", config.optString("token")));
        } else if ("auth_ok".equals(type)) {
            main.removeCallbacks(authDeadline);
            ready = true;
            attempt = 0;
            status("就绪");
        } else if ("auth_invalid".equals(type)) {
            disconnect();
            if (!config.optString("refresh_token").isEmpty()
                && config.optLong("expires_at") > 0) {
                config.put("expires_at", 0);
                store.save(config);
                connect();
            } else status("授权失败，请重新配置");
        } else if ("result".equals(type) && message.optInt("id") == runId
            && !message.optBoolean("success")) {
            JSONObject error = message.optJSONObject("error");
            failRun("HA 拒绝会话：" + (error == null ? "unknown" : error.optString("code")));
        } else if ("event".equals(type) && runId != 0 && message.optInt("id") == runId) {
            JSONObject event = message.getJSONObject("event");
            String eventType = event.getString("type");
            JSONObject data = event.optJSONObject("data");
            if (data == null) data = new JSONObject();
            if ("run-start".equals(eventType)) {
                handlerId = data.getJSONObject("runner_data").optInt("stt_binary_handler_id", -1);
            } else if ("stt-start".equals(eventType)) {
                sttReady = true;
                flush();
            } else if ("stt-end".equals(eventType)) {
                audioStage = false;
                sttReady = false;
                pending.reset();
            } else if ("intent-end".equals(eventType)) {
                JSONObject output = data.optJSONObject("intent_output");
                String id = output == null ? "" : output.optString("conversation_id", "");
                if (!id.isEmpty() && !"null".equals(id)) {
                    conversationId = id;
                    conversationUpdatedAt = android.os.SystemClock.elapsedRealtime();
                }
            } else if ("error".equals(eventType)) {
                String code = data.optString("code", "unknown");
                android.util.Log.i("SonyHAVoice", "pipeline_error=" + code);
                failRun("stt-no-text-recognized".equals(code)
                    ? "未识别到语音，请在“正在聆听”后讲话并检查麦克风来源"
                    : "HA 会话失败：" + code);
                return;
            } else if ("run-end".equals(eventType)) {
                resetRun();
            }
            emit(eventType, data);
        }
    }
    synchronized void start(String text) throws Exception {
        if (!ready || runId != 0) throw new IllegalStateException("HA 尚未就绪或会话正在进行");
        turnGeneration++;
        runId = nextId++;
        handlerId = -1;
        finishing = false;
        sttReady = false;
        pending.reset();
        audioStage = text == null;
        JSONObject input = new JSONObject();
        input.put(text == null ? "sample_rate" : "text", text == null ? 16000 : text);
        if (text == null) {
            input.put("no_vad", true);
            input.put("volume_multiplier", 4);
        }
        JSONObject command = new JSONObject().put("id", runId).put("type", "assist_pipeline/run")
            .put("start_stage", text == null ? "stt" : "intent").put("end_stage", "tts")
            .put("input", input).put("timeout", 90);
        if (!config.optString("pipeline").isEmpty()) command.put("pipeline", config.optString("pipeline"));
        if (!config.optString("device_id").isEmpty()) command.put("device_id", config.optString("device_id"));
        if (conversationId != null
            && android.os.SystemClock.elapsedRealtime() - conversationUpdatedAt < CONVERSATION_IDLE_MS) {
            command.put("conversation_id", conversationId);
        } else conversationId = null;
        android.util.Log.i("SonyHAVoice", "turn_start id=" + runId
            + " context=" + (conversationId != null));
        send(command);
        main.postDelayed(deadline, 95000);
    }
    synchronized void audio(byte[] pcm) {
        if (runId == 0 || finishing || !audioStage) return;
        if (sttReady && handlerId >= 0) {
            byte[] frame = Pcm.frame(handlerId, pcm);
            if (socket == null || !socket.send(ByteString.of(frame))) reconnect("音频发送失败");
        } else if (pending.size() + pcm.length <= 16000 * 2 * 3) {
            pending.write(pcm, 0, pcm.length);
        } else failRun("HA 录音流程未及时就绪");
    }
    synchronized void finishAudio() {
        if (runId == 0 || finishing || !audioStage) return;
        finishing = true;
        flush();
    }
    private void flush() {
        if (!sttReady || handlerId < 0 || socket == null) return;
        if (pending.size() > 0) {
            if (!socket.send(ByteString.of(Pcm.frame(handlerId, pending.toByteArray())))) {
                reconnect("音频发送失败"); return;
            }
            pending.reset();
        }
        if (finishing && !socket.send(ByteString.of(new byte[]{(byte)handlerId})))
            reconnect("音频结束帧发送失败");
    }
    synchronized void cancel() {
        turnGeneration++;
        if (runId == 0) return;
        resetRun();
        disconnect();
        if (!closed) connect();
    }
    private synchronized void failRun(String message) {
        if (runId == 0) return;
        turnGeneration++;
        resetRun();
        disconnect();
        emit("client-error", json("message", message));
        if (!closed) connect();
    }
    private void resetRun() {
        runId = 0; handlerId = -1; finishing = false; sttReady = false; audioStage = false;
        pending.reset(); main.removeCallbacks(deadline);
    }
    private synchronized void reconnect(String message) {
        if (runId != 0) emit("client-error", json("message", "网络中断，会话已取消"));
        resetRun();
        disconnect();
        if (!closed) scheduleRetry(message);
    }
    private void scheduleRetry(String message) {
        status(message);
        main.removeCallbacks(retry);
        main.postDelayed(retry, Math.min(30000, 1000L << Math.min(attempt++, 5)));
    }
    private void disconnect() {
        ready = false; generation++;
        main.removeCallbacks(authDeadline);
        WebSocket old = socket; socket = null;
        if (old != null) old.cancel();
    }
    synchronized void close() {
        turnGeneration++;
        closed = true; resetRun(); disconnect();
        main.removeCallbacks(retry); http.dispatcher().cancelAll();
    }
    private void send(JSONObject message) {
        if (socket == null || !socket.send(message.toString())) reconnect("消息发送失败");
    }
    private void status(String text) {
        boolean available = ready;
        main.post(() -> listener.status(text, available));
    }
    private void emit(String type, JSONObject data) {
        int turn = turnGeneration;
        main.post(() -> {
            synchronized (AssistClient.this) {
                if (closed || turn != turnGeneration) return;
            }
            listener.event(type, data);
        });
    }
    static JSONObject json(String key, Object value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (Exception ignored) {}
        return result;
    }
}
