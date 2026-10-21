package dev.guxi.sonyhavoice;

import android.content.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

public final class ConfigureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        File input = new File(context.getFilesDir(), "incoming-config.json");
        try {
            JSONObject config = new JSONObject(new String(Files.readAllBytes(input.toPath()),
                StandardCharsets.UTF_8));
            new ConfigStore(context).save(config);
            ConnectionService.start(context);
            context.startService(new Intent(context, ConnectionService.class).putExtra("reload", true));
            setResultCode(1);
            setResultData("配置已加密保存");
        } catch (Exception error) {
            setResultCode(-1);
            setResultData("配置导入失败，请检查地址、凭据和录音参数");
        } finally { input.delete(); }
    }
}
