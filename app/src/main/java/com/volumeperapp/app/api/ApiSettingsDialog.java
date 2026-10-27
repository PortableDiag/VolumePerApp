package com.volumeperapp.app.api;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.volumeperapp.app.R;

/**
 * The Local API panel — the only place the socket is opened from.
 *
 * <p>The switch acts at once rather than on OK: a socket you think you closed
 * and did not is the worst outcome on offer here. The key is generated, never
 * typed. The port is the one typed field, so it is the one checked.
 */
public final class ApiSettingsDialog {

    private ApiSettingsDialog() {
    }

    public static void show(Activity activity) {
        Context c = activity;
        View root = activity.getLayoutInflater().inflate(R.layout.dialog_api, null);
        MaterialSwitch enable = root.findViewById(R.id.api_enable);
        TextView status = root.findViewById(R.id.api_status);
        TextView key = root.findViewById(R.id.api_key);
        EditText port = root.findViewById(R.id.api_port);
        MaterialButton copy = root.findViewById(R.id.api_copy);
        MaterialButton regenerate = root.findViewById(R.id.api_regenerate);

        Runnable renderStatus = () -> {
            String error = ApiStatus.error();
            if (ApiStatus.running()) {
                status.setText(c.getString(R.string.api_running, ApiStatus.address()));
            } else if (error != null && ApiPrefs.enabled(c)) {
                status.setText(c.getString(R.string.api_failed, ApiPrefs.port(c), error));
            } else {
                status.setText(R.string.api_stopped);
            }
        };
        Runnable renderKey = () -> {
            String k = ApiPrefs.key(c);
            key.setText(k.isEmpty() ? c.getString(R.string.api_key_none) : k);
        };

        enable.setChecked(ApiPrefs.enabled(c));
        port.setText(String.valueOf(ApiPrefs.port(c)));
        renderKey.run();
        renderStatus.run();

        ApiStatus.Listener listener = () -> activity.runOnUiThread(renderStatus);
        ApiStatus.addListener(listener);

        enable.setOnCheckedChangeListener((b, on) -> {
            ApiPrefs.setEnabled(c, on);
            renderKey.run();
            if (on) ApiService.startIfEnabled(c);
            else ApiService.stop(c);
            renderStatus.run();
        });
        copy.setOnClickListener(v -> {
            String k = ApiPrefs.key(c);
            if (k.isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("VolumePerApp API key", k));
            Toast.makeText(c, R.string.api_copied, Toast.LENGTH_SHORT).show();
        });
        regenerate.setOnClickListener(v -> {
            ApiPrefs.regenerateKey(c);
            renderKey.run();
            Toast.makeText(c, R.string.api_regenerated, Toast.LENGTH_SHORT).show();
        });

        new AlertDialog.Builder(activity)
                .setTitle(R.string.api)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int typed;
                    try {
                        typed = Integer.parseInt(port.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        typed = -1;
                    }
                    if (typed < ApiPrefs.MIN_PORT || typed > ApiPrefs.MAX_PORT) {
                        Toast.makeText(c, R.string.api_port_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (typed != ApiPrefs.port(c)) {
                        ApiPrefs.setPort(c, typed);
                        if (ApiPrefs.enabled(c)) ApiService.restart(c);
                    }
                })
                .setOnDismissListener(d -> ApiStatus.removeListener(listener))
                .show();
    }
}
