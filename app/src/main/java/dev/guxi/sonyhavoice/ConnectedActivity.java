package dev.guxi.sonyhavoice;

import android.app.Activity;
import android.content.*;
import android.os.IBinder;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

abstract class ConnectedActivity extends Activity implements ConnectionService.Observer {
    ConnectionService service;
    LinearLayout layout;
    TextView statusView;
    boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ConnectionService.LocalBinder)binder).service();
            service.observe(ConnectedActivity.this);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };
    void createLayout(String title) {
        getWindow().getDecorView().setBackgroundColor(Color.rgb(16, 22, 33));
        ScrollView scroll = new ScrollView(this);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 36, 60, 36);
        scroll.addView(layout);
        setContentView(scroll);
        text(title, 30);
        statusView = text("正在连接服务", 20);
    }
    TextView text(String text, int size) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE); view.setTextSize(size); view.setText(text);
        view.setPadding(0, 8, 0, 8);
        layout.addView(view);
        return view;
    }
    Button button(String label, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label); button.setAllCaps(false); button.setOnClickListener(click);
        layout.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    @Override protected void onStart() {
        super.onStart();
        ConnectionService.start(this);
        bound = bindService(new Intent(this, ConnectionService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (service != null) service.remove(this);
        if (bound) unbindService(connection);
        service = null; bound = false;
        super.onStop();
    }
    @Override public void status(String text, boolean ready) { statusView.setText("HA：" + text); }
    @Override public void event(String type, JSONObject data) {}
}
