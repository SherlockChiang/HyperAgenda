package com.vince.hyperagenda.data;

import android.content.SharedPreferences;
import android.net.Uri;

public final class AgendaContract {
    public static final String AUTHORITY = "com.vince.hyperagenda.schedule";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/agenda");

    public static final String PREFS = "agenda_settings";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SHOW_TITLES = "show_titles";
    public static final String KEY_SHOW_LOCATION = "show_location";
    public static final String KEY_OPEN_ON_LOCKSCREEN_CLICK = "open_on_lockscreen_click";
    public static final String KEY_MAX_EVENTS = "max_events";
    public static final String KEY_LOOKAHEAD_DAYS = "lookahead_days";
    public static final String KEY_TOP_OFFSET_DP = "top_offset_dp";
    public static final String KEY_CLOCK_GAP_DP = "clock_gap_dp";
    public static final String KEY_CLOCK_GAP_V2_MIGRATED = "clock_gap_v2_migrated";
    public static final String KEY_LAST_HOOK_TIME = "last_hook_time";
    public static final String KEY_SYSTEMUI_VERSION = "systemui_version";

    public static final int DEFAULT_CLOCK_GAP_DP = 24;
    public static final int MIN_CLOCK_GAP_DP = 0;
    public static final int MAX_CLOCK_GAP_DP = 64;

    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String METHOD_REPORT_HOOK = "report_hook";

    public static final String COL_EVENT_ID = "event_id";
    public static final String COL_TITLE = "title";
    public static final String COL_LOCATION = "location";
    public static final String COL_BEGIN = "begin";
    public static final String COL_END = "end";
    public static final String COL_ALL_DAY = "all_day";
    public static final String COL_COLOR = "color";

    public static int readClockGapDp(SharedPreferences prefs) {
        if (!prefs.getBoolean(KEY_CLOCK_GAP_V2_MIGRATED, false)) {
            int migratedGap;
            if (prefs.contains(KEY_CLOCK_GAP_DP)) {
                int previousGap = clamp(
                        prefs.getInt(KEY_CLOCK_GAP_DP, 12),
                        MIN_CLOCK_GAP_DP,
                        MAX_CLOCK_GAP_DP);
                migratedGap = previousGap == 12 ? DEFAULT_CLOCK_GAP_DP : previousGap;
            } else {
                int legacyOffset = clamp(
                        prefs.getInt(KEY_TOP_OFFSET_DP, 210), 120, 520);
                migratedGap = clamp(
                        DEFAULT_CLOCK_GAP_DP + legacyOffset - 210,
                        MIN_CLOCK_GAP_DP,
                        MAX_CLOCK_GAP_DP);
            }
            prefs.edit()
                    .putInt(KEY_CLOCK_GAP_DP, migratedGap)
                    .putBoolean(KEY_CLOCK_GAP_V2_MIGRATED, true)
                    .apply();
            return migratedGap;
        }
        return clamp(
                prefs.getInt(KEY_CLOCK_GAP_DP, DEFAULT_CLOCK_GAP_DP),
                MIN_CLOCK_GAP_DP,
                MAX_CLOCK_GAP_DP);
    }

    public static boolean readShowLocation(SharedPreferences prefs) {
        if (!prefs.contains(KEY_SHOW_LOCATION) && prefs.contains(KEY_SHOW_TITLES)) {
            boolean legacyValue = prefs.getBoolean(KEY_SHOW_TITLES, true);
            prefs.edit().putBoolean(KEY_SHOW_LOCATION, legacyValue).apply();
            return legacyValue;
        }
        return prefs.getBoolean(KEY_SHOW_LOCATION, true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private AgendaContract() {
    }
}
