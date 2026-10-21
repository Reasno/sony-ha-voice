package dev.guxi.sonyhavoice;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.Arrays;

final class PcmRecorder {
    interface Listener {
        void audio(byte[] pcm);
        void level(double rms, String route, long samples);
        void ended(String error);
    }
    private volatile boolean running;
    private AudioRecord record;
    private final Handler main = new Handler(Looper.getMainLooper());
    synchronized void start(Context context, JSONObject config, Listener listener) throws Exception {
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("设备不支持 16 kHz 录音");
        record = new AudioRecord.Builder().setAudioSource(config.optInt("audio_source", 1))
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(min * 2, 6400)).build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release(); record = null;
            throw new IllegalStateException("录音初始化失败");
        }
        int selected = config.optInt("input_device", -1);
        if (selected >= 0) {
            AudioDeviceInfo device = null;
            AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo candidate : manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                if (candidate.getId() == selected) device = candidate;
            if (device == null || !record.setPreferredDevice(device)) {
                record.release(); record = null;
                throw new IllegalStateException("所选录音设备不可用，请选择自动路由");
            }
        }
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release(); record = null;
            throw new IllegalStateException("电视拒绝启动录音");
        }
        running = true;
        AudioRecord current = record;
        new Thread(() -> {
            String error = null;
            byte[] buffer = new byte[640];
            Pcm.EndDetector detector = new Pcm.EndDetector();
            long samples = 0;
            try {
                while (running) {
                    int count = current.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (!running) break;
                    if (count < 0) throw new IllegalStateException("录音读取失败：" + count);
                    if (count == 0) continue;
                    byte[] pcm = Arrays.copyOf(buffer, count - count % 2);
                    listener.audio(pcm);
                    double rms = Pcm.rms(pcm);
                    samples += pcm.length / 2;
                    if (samples % 3200 < 320) {
                        AudioDeviceInfo route = current.getRoutedDevice();
                        String name = route == null ? "路由尚未报告"
                            : "设备 " + route.getId() + " / 类型 " + route.getType()
                                + " / " + route.getAddress();
                        long received = samples;
                        main.post(() -> listener.level(rms, name, received));
                    }
                    if (detector.add(rms, pcm.length * 1000 / 32000)) break;
                }
            } catch (Exception failure) {
                if (running) error = failure.getMessage();
            } finally {
                synchronized (PcmRecorder.this) {
                    running = false;
                    try { current.stop(); } catch (Exception ignored) {}
                    current.release();
                    if (record == current) record = null;
                }
                String result = error;
                main.post(() -> listener.ended(result));
            }
        }, "ha-pcm").start();
    }
    synchronized void stop() {
        running = false;
        if (record != null) try { record.stop(); } catch (Exception ignored) {}
    }
}
