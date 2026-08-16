package com.volumeperapp.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.volumeperapp.app.R;
import com.volumeperapp.app.data.AppEntry;
import com.volumeperapp.app.data.AppRepository;
import com.volumeperapp.app.data.VolumeStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adds a channel strip. Shows the uid beside each app because that is what the
 * routing engine actually matches on — and when two apps share a uid, one fader
 * genuinely moves both, which is easier to accept when you can see it.
 */
public final class AppPickerActivity extends AppCompatActivity {

    public static final String EXTRA_PACKAGE = "package";

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        VolumeStore store = new VolumeStore(this);
        setTheme(MixerActivity.themeFor(store.theme()));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        AppRepository repo = new AppRepository(this);
        all.addAll(repo.candidateApps());
        shown.addAll(all);

        adapter = new Adapter(repo);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        ((TextView) findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s.toString());
            }

            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.getDefault());
        shown.clear();
        if (q.isEmpty()) {
            shown.addAll(all);
        } else {
            for (AppEntry e : all) {
                if (e.label.toLowerCase(Locale.getDefault()).contains(q)
                        || e.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                    shown.add(e);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final AppRepository repo;

        Adapter(AppRepository repo) {
            this.repo = repo;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pick, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AppEntry e = shown.get(position);
            h.name.setText(e.label);
            h.pkg.setText(e.packageName);
            h.uid.setText("uid " + e.uid);
            h.icon.setImageDrawable(repo.iconFor(e.packageName));
            h.itemView.setOnClickListener(v -> {
                setResult(RESULT_OK, new android.content.Intent()
                        .putExtra(EXTRA_PACKAGE, e.packageName));
                finish();
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView pkg;
            final TextView uid;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.app_icon);
                name = v.findViewById(R.id.app_name);
                pkg = v.findViewById(R.id.app_pkg);
                uid = v.findViewById(R.id.app_uid);
            }
        }
    }
}
