package app.panelled;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The normal, on-screen way to configure the MQTT bridge -- every field here can also be set over
 * adb (ConfigReceiver, see docs/mqtt-bridge.md); this screen and that receiver write the same
 * storage (BridgeConfig), so either way works and neither is the "real" one. The password field
 * is intentionally never pre-filled with the stored value, even if one is set -- only whether one
 * is set, so a glance at this screen never discloses it.
 */
public class SettingsActivity extends Activity {
    private EditText host, port, user, password, slug;
    private Switch enabled;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BridgeConfig cfg = new BridgeConfig(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("MQTT bridge settings");
        title.setTextSize(20);
        root.addView(title);

        host = field(root, "Broker host", cfg.host(), InputType.TYPE_CLASS_TEXT);
        port = field(root, "Broker port", String.valueOf(cfg.port()), InputType.TYPE_CLASS_NUMBER);
        user = field(root, "Username (optional)", cfg.user(), InputType.TYPE_CLASS_TEXT);
        password = field(root, cfg.password() == null ? "Password (none set)" : "Password (set -- leave blank to keep it)",
                null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        slug = field(root, "Device slug (e.g. living_room_panel)", cfg.slug(), InputType.TYPE_CLASS_TEXT);

        LinearLayout enabledRow = new LinearLayout(this);
        enabledRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView enabledLabel = new TextView(this);
        enabledLabel.setText("Bridge enabled");
        enabled = new Switch(this);
        enabled.setChecked(cfg.enabled());
        enabledRow.addView(enabledLabel);
        enabledRow.addView(enabled);
        root.addView(enabledRow);

        TextView status = new TextView(this);
        status.setText(cfg.describe());
        status.setTextSize(12);
        root.addView(status);

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener((View v) -> {
            cfg.setHost(textOrNull(host));
            try {
                cfg.setPort(Integer.parseInt(port.getText().toString().trim()));
            } catch (NumberFormatException ignored) { /* keep the previous port */ }
            cfg.setUser(textOrNull(user));
            String newPass = textOrNull(password);
            if (newPass != null) cfg.setPassword(newPass); // blank = keep whatever was already stored
            cfg.setSlug(textOrNull(slug));
            cfg.setEnabled(enabled.isChecked());
            MqttBridgeService.applyConfigChange(SettingsActivity.this);
            status.setText(cfg.describe());
            password.setText("");
        });
        root.addView(save);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String label, String value, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        root.addView(l);
        EditText e = new EditText(this);
        e.setInputType(inputType);
        if (value != null) e.setText(value);
        root.addView(e);
        return e;
    }

    private static String textOrNull(EditText e) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? null : s;
    }
}
