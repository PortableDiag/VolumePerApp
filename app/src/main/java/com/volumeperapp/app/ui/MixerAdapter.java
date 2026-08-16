package com.volumeperapp.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.Slider;
import com.volumeperapp.app.R;
import com.volumeperapp.app.data.AppEntry;
import com.volumeperapp.app.data.VolumeStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders the mixer list. All state lives in the {@link Row} objects. */
public final class MixerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Callbacks {
        void onGainChanged(AppEntry app, int percent);

        void onMuteToggled(AppEntry app);

        void onRemove(AppEntry app);

        void onStreamChanged(int streamType, int volume);

        void onBannerAction();
    }

    private final Context context;
    private final Callbacks callbacks;
    private List<Row> rows = new ArrayList<>();

    public MixerAdapter(Context context, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;
        setHasStableIds(true);
    }

    public void submit(List<Row> next) {
        this.rows = next;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type();
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).id();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case Row.TYPE_BANNER:
                return new BannerVH(inf.inflate(R.layout.item_banner, parent, false));
            case Row.TYPE_SECTION:
                return new SectionVH(inf.inflate(R.layout.item_section, parent, false));
            case Row.TYPE_STREAM:
                return new StreamVH(inf.inflate(R.layout.item_stream, parent, false));
            case Row.TYPE_EMPTY:
                return new EmptyVH(inf.inflate(R.layout.item_empty, parent, false));
            default:
                return new ChannelVH(inf.inflate(R.layout.item_channel, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof BannerVH) ((BannerVH) holder).bind((Row.Banner) row);
        else if (holder instanceof SectionVH) ((SectionVH) holder).bind((Row.Section) row);
        else if (holder instanceof ChannelVH) ((ChannelVH) holder).bind((Row.Channel) row);
        else if (holder instanceof StreamVH) ((StreamVH) holder).bind((Row.Stream) row);
    }

    // ------------------------------------------------------------------ banner

    final class BannerVH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final View stripe;
        final TextView title;
        final TextView body;
        final MaterialButton action;

        BannerVH(View v) {
            super(v);
            card = (MaterialCardView) v;
            stripe = v.findViewById(R.id.banner_stripe);
            title = v.findViewById(R.id.banner_title);
            body = v.findViewById(R.id.banner_body);
            action = v.findViewById(R.id.banner_action);
        }

        void bind(Row.Banner b) {
            int fg;
            int bg;
            switch (b.level) {
                case OK:
                    fg = R.color.state_ok;
                    bg = R.color.state_ok_bg;
                    break;
                case WARN:
                    fg = R.color.state_warn;
                    bg = R.color.state_warn_bg;
                    break;
                default:
                    fg = R.color.text_secondary;
                    bg = R.color.surface_variant;
                    break;
            }
            card.setCardBackgroundColor(ContextCompat.getColor(context, bg));
            stripe.setBackgroundColor(ContextCompat.getColor(context, fg));
            title.setTextColor(ContextCompat.getColor(context, fg));
            title.setText(b.title);
            body.setText(b.body);
            if (b.action == null) {
                action.setVisibility(View.GONE);
            } else {
                action.setVisibility(View.VISIBLE);
                action.setText(b.action);
                action.setTextColor(ContextCompat.getColor(context, fg));
                action.setOnClickListener(v -> callbacks.onBannerAction());
            }
        }
    }

    static final class SectionVH extends RecyclerView.ViewHolder {
        final TextView title;

        SectionVH(View v) {
            super(v);
            title = v.findViewById(R.id.section_title);
        }

        void bind(Row.Section s) {
            title.setText(s.title);
        }
    }

    static final class EmptyVH extends RecyclerView.ViewHolder {
        EmptyVH(View v) {
            super(v);
        }
    }

    // ----------------------------------------------------------------- channel

    final class ChannelVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView readout;
        final TextView badgePlaying;
        final TextView badgeRouted;
        final TextView badgeBoost;
        final TextView badgeBlocked;
        final ImageButton mute;
        final ImageButton remove;
        final Slider fader;

        /** Set while binding so the value listener does not fire as a user edit. */
        boolean binding;

        ChannelVH(View v) {
            super(v);
            icon = v.findViewById(R.id.app_icon);
            name = v.findViewById(R.id.app_name);
            readout = v.findViewById(R.id.readout);
            badgePlaying = v.findViewById(R.id.badge_playing);
            badgeRouted = v.findViewById(R.id.badge_routed);
            badgeBoost = v.findViewById(R.id.badge_boost);
            badgeBlocked = v.findViewById(R.id.badge_blocked);
            mute = v.findViewById(R.id.btn_mute);
            remove = v.findViewById(R.id.btn_remove);
            fader = v.findViewById(R.id.fader);
        }

        void bind(Row.Channel c) {
            AppEntry app = c.app;
            binding = true;

            name.setText(app.label);
            if (app.icon == null) app.icon = null;
            icon.setImageDrawable(app.icon);

            fader.setValueTo(Math.max(100, c.maxPercent));
            int clamped = Math.min(app.gainPercent, (int) fader.getValueTo());
            fader.setValue(clamped);
            fader.setEnabled(!app.muted);

            readout.setText(app.muted
                    ? "—"
                    : String.format(Locale.US, "%d%%", app.gainPercent));
            readout.setAlpha(app.muted ? 0.5f : 1f);

            mute.setImageResource(app.muted ? R.drawable.ic_mute : R.drawable.ic_unmute);
            mute.setContentDescription(context.getString(
                    app.muted ? R.string.unmute : R.string.mute));

            badgePlaying.setVisibility(app.playing ? View.VISIBLE : View.GONE);
            badgeRouted.setVisibility(app.routed && c.live ? View.VISIBLE : View.GONE);
            badgeBoost.setVisibility(!app.muted && app.gainPercent > 100
                    ? View.VISIBLE : View.GONE);
            badgeBlocked.setVisibility(app.captureBlocked ? View.VISIBLE : View.GONE);

            // A fader that cannot act should not pretend it can.
            float alpha = c.live ? 1f : 0.55f;
            fader.setAlpha(alpha);
            readout.setAlpha(c.live ? readout.getAlpha() : 0.4f);
            if (c.live) {
                fader.setTrackActiveTintList(ColorStateList.valueOf(
                        app.gainPercent > 100
                                ? ContextCompat.getColor(context, R.color.boost)
                                : themeColor(com.google.android.material.R.attr.colorPrimary)));
            }

            binding = false;

            fader.clearOnChangeListeners();
            fader.addOnChangeListener((slider, value, fromUser) -> {
                if (binding || !fromUser) return;
                int pct = Math.round(value);
                app.gainPercent = pct;
                readout.setText(String.format(Locale.US, "%d%%", pct));
                badgeBoost.setVisibility(pct > 100 ? View.VISIBLE : View.GONE);
                callbacks.onGainChanged(app, pct);
            });

            mute.setOnClickListener(v -> callbacks.onMuteToggled(app));
            remove.setOnClickListener(v -> callbacks.onRemove(app));
        }

        private int themeColor(int attr) {
            android.util.TypedValue tv = new android.util.TypedValue();
            context.getTheme().resolveAttribute(attr, tv, true);
            return tv.data;
        }
    }

    // ------------------------------------------------------------------ stream

    final class StreamVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView readout;
        final Slider fader;
        boolean binding;

        StreamVH(View v) {
            super(v);
            name = v.findViewById(R.id.stream_name);
            readout = v.findViewById(R.id.stream_readout);
            fader = v.findViewById(R.id.stream_fader);
        }

        void bind(Row.Stream s) {
            binding = true;
            name.setText(s.label);
            fader.setValueTo(Math.max(1, s.max));
            fader.setValue(Math.min(s.volume, Math.max(1, s.max)));
            readout.setText(String.format(Locale.US, "%d/%d", s.volume, s.max));
            binding = false;

            fader.clearOnChangeListeners();
            fader.addOnChangeListener((slider, value, fromUser) -> {
                if (binding || !fromUser) return;
                int v = Math.round(value);
                s.volume = v;
                readout.setText(String.format(Locale.US, "%d/%d", v, s.max));
                callbacks.onStreamChanged(s.streamType, v);
            });
        }
    }

    /** Convenience for the "no channel strips" case. */
    public static List<Row> withEmpty(List<Row> rows) {
        rows.add(new Row.Empty());
        return rows;
    }

    public static int defaultPercent() {
        return VolumeStore.DEFAULT_PERCENT;
    }
}
