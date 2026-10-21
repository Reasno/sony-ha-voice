package dev.guxi.sonyhavoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ConfigStore {
    private static final String KEY_ALIAS = "sony-ha-voice-config";
    private final SharedPreferences preferences;

    ConfigStore(Context context) {
        preferences = context.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    JSONObject load() {
        try {
            String encrypted = preferences.getString("encrypted", "");
            if (encrypted.isEmpty()) return defaults();
            String[] parts = encrypted.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8"));
        } catch (Exception error) {
            return defaults();
        }
    }

    void save(JSONObject config) throws Exception {
        config.put("url", normalizeUrl(config.optString("url")));
        if (config.optString("token").isEmpty() && config.optString("refresh_token").isEmpty())
            throw new IllegalArgumentException("请填写访问令牌或通过电脑完成授权");
        int source = config.optInt("audio_source", 1);
        if (source != 1 && source != 6 && source != 7 && source != 9)
            throw new IllegalArgumentException("录音源仅支持 MIC、VOICE_RECOGNITION、VOICE_COMMUNICATION、UNPROCESSED");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(config.toString().getBytes("UTF-8")), Base64.NO_WRAP);
        if (!preferences.edit().putString("encrypted", encrypted)
            .putBoolean("autostart", config.optBoolean("autostart", true)).commit())
            throw new IllegalStateException("无法保存配置");
    }

    boolean autostart() {
        return preferences.getBoolean("autostart", true);
    }

    static JSONObject defaults() {
        JSONObject config = new JSONObject();
        try {
            config.put("url", "http://homeassistant.local:8123");
            config.put("token", "");
            config.put("pipeline", "");
            config.put("device_id", "");
            config.put("input_device", -1);
            config.put("audio_source", 1);
            config.put("autostart", true);
        } catch (Exception ignored) {}
        return config;
    }

    static String normalizeUrl(String url) throws Exception {
        URI uri = new URI(url.trim());
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("HA 地址必须是有效的 http:// 或 https:// 地址");
        String normalized = uri.toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(KEY_ALIAS, null);
    }
}
