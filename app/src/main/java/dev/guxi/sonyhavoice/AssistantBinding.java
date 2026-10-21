package dev.guxi.sonyhavoice;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/** Keep the selected assistant role and the platform voice bindings consistent. */
final class AssistantBinding {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reconcile = this::reconcile;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            main.removeCallbacks(reconcile);
            // A role change can update the secure settings and role store separately.
            main.postDelayed(reconcile, 1000);
        }
    };
    AssistantBinding(Context context) { this.context = context; }
    void start() {
        for (String key : new String[]{"assistant", "voice_interaction_service"})
            context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(key), false, observer);
        reconcile();
    }
    void reconcile() {
        if (Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        RoleManager roles = context.getSystemService(RoleManager.class);
        if (roles == null || !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return;
        String component = new ComponentName(context, AssistantService.class).flattenToShortString();
        for (String key : new String[]{"assistant", "voice_interaction_service"}) {
            String current = Settings.Secure.getString(context.getContentResolver(), key);
            ComponentName parsed = current == null ? null : ComponentName.unflattenFromString(current);
            if (new ComponentName(context, AssistantService.class).equals(parsed)) continue;
            try {
                if (Settings.Secure.putString(context.getContentResolver(), key, component))
                    Log.i("SonyHAVoice", "assistant_binding_restored=" + key);
            } catch (SecurityException denied) {
                Log.w("SonyHAVoice", "assistant_binding_permission_missing");
            }
        }
    }
    void close() {
        context.getContentResolver().unregisterContentObserver(observer);
        main.removeCallbacks(reconcile);
    }
}
