package dev.guxi.sonyhavoice;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.*;
import android.os.*;
import android.provider.Settings;

public final class MainActivity extends ConnectedActivity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        createLayout("Sony HA Voice");
        text("按遥控器语音键启动，页面显示“正在聆听”后讲话。", 20);
        button("开始语音", v -> startActivity(new Intent(this, VoiceActivity.class))).requestFocus();
        button("连接与录音设置", v -> startActivity(new Intent(this, SettingsActivity.class)));
        button("设为系统助手", v -> {
            RoleManager roles = Build.VERSION.SDK_INT >= 29 ? getSystemService(RoleManager.class) : null;
            try {
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 1);
                else startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
            } catch (Exception error) {
                statusView.setText("请在系统的默认应用设置中选择 Sony HA Voice");
            }
        });
        button("文字测试", v -> startActivity(new Intent(this, VoiceActivity.class)
            .putExtra("text_mode", true)));
        text("默认开机启动连接服务；麦克风仅在语音页面使用。", 16);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
    }
}
