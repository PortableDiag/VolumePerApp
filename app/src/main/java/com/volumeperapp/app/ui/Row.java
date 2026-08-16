package com.volumeperapp.app.ui;

import com.volumeperapp.app.data.AppEntry;

/**
 * One line of the mixer list. A sealed-ish set of shapes rather than a bag of
 * nullable fields per type, so the adapter's {@code onBind} cannot read a field
 * that does not apply to the row it was handed.
 */
public abstract class Row {

    public static final int TYPE_BANNER = 0;
    public static final int TYPE_SECTION = 1;
    public static final int TYPE_CHANNEL = 2;
    public static final int TYPE_STREAM = 3;
    public static final int TYPE_EMPTY = 4;

    public abstract int type();

    /** Stable id so RecyclerView animations do not swap rows around. */
    public abstract long id();

    // ------------------------------------------------------------------------

    public static final class Banner extends Row {
        public enum Level { OK, WARN, INFO }

        public final Level level;
        public final String title;
        public final String body;
        public final String action;

        public Banner(Level level, String title, String body, String action) {
            this.level = level;
            this.title = title;
            this.body = body;
            this.action = action;
        }

        @Override public int type() { return TYPE_BANNER; }
        @Override public long id() { return 1; }
    }

    public static final class Section extends Row {
        public final String title;

        public Section(String title) {
            this.title = title;
        }

        @Override public int type() { return TYPE_SECTION; }
        @Override public long id() { return ("s:" + title).hashCode(); }
    }

    public static final class Channel extends Row {
        public final AppEntry app;
        /** True when the engine is privileged; a fader that cannot act says so. */
        public final boolean live;
        public final int maxPercent;

        public Channel(AppEntry app, boolean live, int maxPercent) {
            this.app = app;
            this.live = live;
            this.maxPercent = maxPercent;
        }

        @Override public int type() { return TYPE_CHANNEL; }
        @Override public long id() { return ("c:" + app.packageName).hashCode(); }
    }

    public static final class Stream extends Row {
        public final int streamType;
        public final String label;
        public int volume;
        public int max;

        public Stream(int streamType, String label, int volume, int max) {
            this.streamType = streamType;
            this.label = label;
            this.volume = volume;
            this.max = max;
        }

        @Override public int type() { return TYPE_STREAM; }
        @Override public long id() { return 1000L + streamType; }
    }

    public static final class Empty extends Row {
        @Override public int type() { return TYPE_EMPTY; }
        @Override public long id() { return 2; }
    }
}
