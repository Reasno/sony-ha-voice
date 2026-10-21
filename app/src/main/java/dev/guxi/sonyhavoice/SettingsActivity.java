package dev.guxi.sonyhavoice;

import android.app.Activity;
import android.content.Intent;
import android.media.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.ArrayList;

public final class SettingsActivity extends Activity {
    private JSONObject config;
    private LinearLayout layout;
    private EditText url, token, pipeline;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        config = new ConfigStore(this).load();
        ScrollView scroll = new ScrollView(this);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(60, 30, 60, 30);
        scroll.addView(layout); setContentView(scroll);
        label("连接与录音设置");
        url = field("HA 地址", config.optString("url"), false);
        token = field("访问令牌（留空保留已保存的凭据）", "", true);
        pipeline = field("语音流程 ID（留空使用 HA 首选）", config.optString("pipeline"), false);
        CheckBox boot = new CheckBox(this); boot.setText("开机启动连接服务");
        boot.setChecked(config.optBoolean("autostart", true)); layout.addView(boot);
        label("录音设备");
        ArrayList<String> devices = new ArrayList<>(); ArrayList<Integer> ids = new ArrayList<>();
        devices.add("自动路由"); ids.add(-1);
        for (AudioDeviceInfo device : getSystemService(AudioManager.class)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            devices.add("设备 " + device.getId() + " / 类型 " + device.getType()
                + " / " + device.getProductName()); ids.add(device.getId());
        }
        Spinner input = spinner(devices);
        int selected = ids.indexOf(config.optInt("input_device", -1));
        input.setSelection(selected < 0 ? 0 : selected);
        label("录音源");
        int[] sources = {1, 6, 7, 9};
        Spinner source = spinner(java.util.Arrays.asList("MIC", "VOICE_RECOGNITION",
            "VOICE_COMMUNICATION", "UNPROCESSED"));
        for (int i = 0; i < sources.length; i++)
            if (sources[i] == config.optInt("audio_source", 1)) source.setSelection(i);
        TextView feedback = label("");
        Button save = new Button(this); save.setText("保存并连接"); layout.addView(save);
        save.setOnClickListener(v -> {
            try {
                config.put("url", url.getText().toString());
                if (token.length() > 0) {
                    config.put("token", token.getText().toString().trim());
                    config.remove("refresh_token"); config.remove("expires_at"); config.remove("client_id");
                }
                config.put("pipeline", pipeline.getText().toString().trim());
                config.put("autostart", boot.isChecked());
                config.put("input_device", ids.get(input.getSelectedItemPosition()));
                config.put("audio_source", sources[source.getSelectedItemPosition()]);
                new ConfigStore(this).save(config);
                ConnectionService.start(this);
                startService(new Intent(this, ConnectionService.class).putExtra("reload", true));
                finish();
            } catch (Exception error) { feedback.setText(error.getMessage()); }
        });
    }
    private TextView label(String value) {
        TextView label = new TextView(this); label.setText(value); label.setTextSize(20);
        layout.addView(label); return label;
    }
    private EditText field(String name, String value, boolean secret) {
        label(name); EditText field = new EditText(this);
        field.setSingleLine(true); field.setText(value);
        field.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(field); return field;
    }
    private Spinner spinner(java.util.List<String> options) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        layout.addView(spinner); return spinner;
    }
}
