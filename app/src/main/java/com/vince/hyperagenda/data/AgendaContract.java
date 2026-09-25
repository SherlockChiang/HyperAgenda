package com.vince.hyperagenda.data;

import android.content.SharedPreferences;
import android.net.Uri;

import java.util.HashSet;
import java.util.Set;

public final class AgendaContract {
    public static final String AUTHORITY = "com.vince.hyperagenda.schedule";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/agenda");

    public static final String PREFS = "agenda_settings";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SHOW_TITLES = "show_titles";
    public static final String KEY_SHOW_LOCATION = "show_location";
    public static final String KEY_PRIVACY_MODE = "privacy_mode";
    public static final String KEY_PRIVACY_REDACTED = "privacy_redacted";
    public static final String KEY_SELECTED_CALENDAR_IDS = "selected_calendar_ids";
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

    /** Title, location and calendar name are all shown on the lockscreen. */
    public static final String PRIVACY_FULL = "full";
    /** Title and calendar name are shown, the location is withheld. */
    public static final String PRIVACY_NO_LOCATION = "no_location";
    /** The lockscreen only states that something is scheduled. */
    public static final String PRIVACY_SUMMARY = "summary";
    /** Details are withheld until the device needs no authentication, e.g. no secure lock. */
    public static final String PRIVACY_AFTER_AUTH = "after_auth";

    public static final String[] PRIVACY_MODES = {
            PRIVACY_FULL,
            PRIVACY_NO_LOCATION,
            PRIVACY_SUMMARY,
            PRIVACY_AFTER_AUTH
    };

    public static final String METHOD_GET_CONFIG = "get_config";
    public static final String METHOD_REPORT_HOOK = "report_hook";
    public static final String METHOD_LIST_CALENDARS = "list_calendars";
    public static final String METHOD_GET_STATUS = "get_status";

    public static final String KEY_STATUS_ENABLED = "status_enabled";
    public static final String KEY_STATUS_PERMISSION = "status_permission";
    public static final String KEY_STATUS_VISIBLE_CALENDARS = "status_visible_calendars";
    public static final String KEY_STATUS_SELECTED_CALENDARS = "status_selected_calendars";
    public static final String KEY_STATUS_SELECTION_CONFIGURED = "status_selection_configured";
    public static final String KEY_STATUS_MATCHING_EVENTS = "status_matching_events";
    public static final String KEY_STATUS_NEXT_EVENT_AT = "status_next_event_at";
    public static final String KEY_STATUS_LOOKAHEAD_DAYS = "status_lookahead_days";

    public static final String BUNDLE_CALENDAR_IDS = "calendar_ids";
    public static final String BUNDLE_CALENDAR_NAMES = "calendar_names";
    public static final String BUNDLE_CALENDAR_ACCOUNTS = "calendar_accounts";

    public static final String COL_EVENT_ID = "event_id";
    public static final String COL_TITLE = "title";
    public static final String COL_LOCATION = "location";
    public static final String COL_BEGIN = "begin";
    public static final String COL_END = "end";
    public static final String COL_ALL_DAY = "all_day";
    public static final String COL_COLOR = "color";
    public static final String COL_CALENDAR_NAME = "calendar_name";

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

    /**
     * Current privacy mode, migrating the legacy "显示地点" switch into the equivalent mode so an
     * existing install keeps the visibility it already had.
     */
    public static String readPrivacyMode(SharedPreferences prefs) {
        String stored = prefs.getString(KEY_PRIVACY_MODE, null);
        if (isKnownPrivacyMode(stored)) {
            return stored;
        }
        String migrated = readShowLocation(prefs) ? PRIVACY_FULL : PRIVACY_NO_LOCATION;
        prefs.edit().putString(KEY_PRIVACY_MODE, migrated).apply();
        return migrated;
    }

    public static boolean isKnownPrivacyMode(String mode) {
        if (mode == null) {
            return false;
        }
        for (String candidate : PRIVACY_MODES) {
            if (candidate.equals(mode)) {
                return true;
            }
        }
        return false;
    }

    /** Modes that never expose the location, whatever the keyguard state is. */
    public static boolean hidesLocation(String mode) {
        return PRIVACY_NO_LOCATION.equals(mode) || PRIVACY_SUMMARY.equals(mode);
    }

    /** Modes that never expose the title or the calendar name. */
    public static boolean hidesDetails(String mode) {
        return PRIVACY_SUMMARY.equals(mode);
    }

    public static Set<Long> readSelectedCalendarIds(SharedPreferences prefs) {
        Set<String> raw = prefs.getStringSet(KEY_SELECTED_CALENDAR_IDS, null);
        if (raw == null || raw.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (String value : raw) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // Ignore stale values left by an older build.
            }
        }
        return ids;
    }

    public static boolean hasCalendarSelection(SharedPreferences prefs) {
        return prefs.contains(KEY_SELECTED_CALENDAR_IDS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private AgendaContract() {
    }
}
