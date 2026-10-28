package com.volumeperapp.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.volumeperapp.app.R;
import com.volumeperapp.app.api.ApiPrefs;
import com.volumeperapp.app.api.PhoneApi;
import com.volumeperapp.app.api.PhoneApiAccess;
import com.volumeperapp.app.data.VolumeStore;

/**
 * PhoneAPI asks for a local API key ({@code com.phoneapi.action.GRANT_KEY},
 * provider contract §3). Only PhoneAPI's release build, started for a result,
 * gets past the first check; then the owner picks what the key may do, on
 * VolumePerApp's own screen. The key and the proof key leave only as this
 * activity's result.
 */
public final class PhoneApiGrantActivity extends AppCompatActivity {

    private static final String TAG = "VPA.Api";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Themes.apply(this, new VolumeStore(this));
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        String caller = getCallingPackage();
        if (caller == null
                || !PhoneApi.callerTrusted(caller, PhoneApiAccess.certs(this, caller))) {
            Log.w(TAG, "phoneapi: key request refused (" + (caller == null
                    ? "not started for a result"
                    : caller + " is not PhoneAPI's release build") + ")");
            finish();
            return;
        }
        int eventPort = getIntent().getIntExtra(PhoneApi.EXTRA_EVENT_PORT, 0);
        String eventKey = getIntent().getStringExtra(PhoneApi.EXTRA_EVENT_KEY);

        setContentView(R.layout.activity_phoneapi_grant);

        StringBuilder body = new StringBuilder(getString(R.string.phoneapi_body));
        if (!ApiPrefs.enabled(this)) {
            body.append(' ').append(getString(R.string.phoneapi_turns_on));
        }
        if (PhoneApiAccess.grant(this) != null) {
            body.append(' ').append(getString(R.string.phoneapi_replaces));
        }
        ((TextView) findViewById(R.id.body)).setText(body);

        MaterialSwitch see = findViewById(R.id.see);
        MaterialSwitch change = findViewById(R.id.change);
        MaterialButton allow = findViewById(R.id.allow);
        // Everything starts off, and a key that may do nothing is not worth minting.
        Runnable sync = () -> allow.setEnabled(see.isChecked() || change.isChecked());
        see.setOnCheckedChangeListener((b, on) -> sync.run());
        change.setOnCheckedChangeListener((b, on) -> sync.run());

        findViewById(R.id.deny).setOnClickListener(v -> {
            Log.i(TAG, "phoneapi: key request declined");
            finish();
        });
        allow.setOnClickListener(v -> {
            PhoneApi.Grant g = PhoneApiAccess.create(this, see.isChecked(), change.isChecked(),
                    eventPort, eventKey);
            setResult(RESULT_OK, new Intent()
                    .putExtra("api_key", g.key)
                    .putExtra("proof_key", PhoneApi.b64url(g.proofKey))
                    .putExtra("port", ApiPrefs.port(this)));
            finish();
        });
    }
}
