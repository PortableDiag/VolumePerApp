package com.volumeperapp.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.volumeperapp.app.BuildConfig;
import com.volumeperapp.app.R;
import com.volumeperapp.app.audio.AudioPolicyBridge;
import com.volumeperapp.app.audio.MixerService;
import com.volumeperapp.app.audio.RoutingEngine;
import com.volumeperapp.app.data.VolumeStore;

/**
 * Why the mixer is or is not working, in enough detail to act on.
 *
 * <p>This screen exists because every failure mode here is invisible from the
 * mixer itself: a fader that does nothing looks the same whether the permission
 * is missing, the install is not privileged, or the hidden API moved. It names
 * which one, and it re-probes live rather than reporting a cached verdict.
 */
public final class DiagnosticsActivity extends AppCompatActivity {

    private TextView report;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        VolumeStore store = new VolumeStore(this);
        setTheme(MixerActivity.themeFor(store.theme()));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        report = findViewById(R.id.report);
        ((MaterialButton) findViewById(R.id.btn_refresh)).setOnClickListener(v -> refresh());
        ((MaterialButton) findViewById(R.id.btn_copy)).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("VolumePerApp diagnostics",
                    report.getText()));
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show();
        });

        refresh();
    }

    private void refresh() {
        report.setText(build());
    }

    private String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("VolumePerApp ").append(BuildConfig.VERSION_NAME)
          .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append("  (").append(Build.FINGERPRINT).append(")\n\n");

        sb.append("== INSTALL ==\n");
        sb.append("uid: ").append(android.os.Process.myUid()).append('\n');
        sb.append("privileged system app: ").append(isPrivilegedInstall() ? "yes" : "no").append('\n');
        sb.append("source dir: ").append(getApplicationInfo().sourceDir).append('\n');
        sb.append("MODIFY_AUDIO_ROUTING: ").append(permState(
                "android.permission.MODIFY_AUDIO_ROUTING")).append('\n');
        sb.append("CAPTURE_MEDIA_OUTPUT: ").append(permState(
                "android.permission.CAPTURE_MEDIA_OUTPUT")).append('\n');
        sb.append("CAPTURE_AUDIO_OUTPUT: ").append(permState(
                "android.permission.CAPTURE_AUDIO_OUTPUT")).append('\n');
        sb.append('\n');

        sb.append("== HIDDEN API ==\n");
        AudioPolicyBridge.Capability cap = AudioPolicyBridge.probe(this);
        sb.append(cap.detail);
        sb.append('\n');

        sb.append("== ENGINE ==\n");
        RoutingEngine engine = MixerService.engine(this);
        sb.append(engine.report());
        sb.append('\n');

        sb.append("== VERDICT ==\n");
        if (cap.privileged()) {
            sb.append("Per-app volume is available. Faders divert, attenuate and re-render.\n");
        } else if (!cap.classesPresent) {
            sb.append("android.media.audiopolicy is not on this build. This route cannot\n")
              .append("work here at all; nothing in the app will change that.\n");
        } else if (!cap.recipeComplete) {
            sb.append("The audiopolicy classes exist but the call chain has moved on this\n")
              .append("Android version. The engine needs updating against this build.\n");
        } else if (!isPrivilegedInstall()) {
            sb.append("Everything the engine needs is present, but this APK is not installed\n")
              .append("as a privileged system app, so MODIFY_AUDIO_ROUTING is not granted.\n")
              .append("Flash the Magisk module (magisk/ in the repo) and reboot.\n");
        } else {
            sb.append("Installed privileged, but the policy was still refused. Check that\n")
              .append("privapp-permissions-volumeperapp.xml is in /system/etc/permissions\n")
              .append("and names this exact package.\n");
        }
        return sb.toString();
    }

    private boolean isPrivilegedInstall() {
        String dir = getApplicationInfo().sourceDir;
        return dir != null && (dir.startsWith("/system/priv-app")
                || dir.startsWith("/system_ext/priv-app")
                || dir.startsWith("/product/priv-app"));
    }

    private String permState(String permission) {
        int state = checkSelfPermission(permission);
        return state == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "denied";
    }
}
