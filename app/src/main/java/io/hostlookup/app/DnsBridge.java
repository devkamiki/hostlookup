package io.hostlookup.app;

import android.content.Context;

final class DnsBridge {
    static {
        System.loadLibrary("hostlookup");
    }

    private DnsBridge() {}

    static native void initialize(Context context);

    static native String lookup(String domain);
}
