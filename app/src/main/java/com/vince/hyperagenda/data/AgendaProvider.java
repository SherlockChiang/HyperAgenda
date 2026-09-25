package com.vince.hyperagenda.data;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public final class AgendaProvider extends ContentProvider {
    private static final Uri[] CALENDAR_OBSERVED_URIS = {
            CalendarContract.Events.CONTENT_URI,
            CalendarContract.Instances.CONTENT_URI,
            CalendarContract.Calendars.CONTENT_URI,
            CalendarContract.Attendees.CONTENT_URI
    };

    private static final String[] OUTPUT_COLUMNS = {
            AgendaContract.COL_EVENT_ID,
            AgendaContract.COL_TITLE,
            AgendaContract.COL_LOCATION,
            AgendaContract.COL_BEGIN,
            AgendaContract.COL_END,
            AgendaContract.COL_ALL_DAY,
            AgendaContract.COL_COLOR,
            AgendaContract.COL_CALENDAR_NAME
    };

    private boolean observerRegistered;
    private ContentObserver calendarObserver;

    @Override
    public boolean onCreate() {
        ensureCalendarObserver();
        return true;
    }

    @Override
    public void shutdown() {
        Context context = getContext();
        if (calendarObserver != null && context != null) {
            context.getContentResolver().unregisterContentObserver(calendarObserver);
        }
        calendarObserver = null;
        observerRegistered = false;
        super.shutdown();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        enforceAgendaUri(uri);
        enforceAllowedCaller();
        MatrixCursor output = new MatrixCursor(OUTPUT_COLUMNS);
        Context context = attachedContext();
        SharedPreferences prefs = context.getSharedPreferences(AgendaContract.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(AgendaContract.KEY_ENABLED, true)
                || context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return output;
        }

        ensureCalendarObserver();
        int maxEvents = clamp(prefs.getInt(AgendaContract.KEY_MAX_EVENTS, 1), 1, 3);
        int lookaheadDays = clamp(prefs.getInt(AgendaContract.KEY_LOOKAHEAD_DAYS, 7), 1, 30);
        String privacyMode = AgendaContract.readPrivacyMode(prefs);
        // SystemUI only ever receives what the current privacy mode allows it to see.
        boolean redacted = redactsDetails(context, privacyMode);
        boolean hideLocation = redacted || AgendaContract.hidesLocation(privacyMode);
        Set<Long> selectedCalendarIds = AgendaContract.readSelectedCalendarIds(prefs);
        boolean hasCalendarSelection = AgendaContract.hasCalendarSelection(prefs);
        long now = System.currentTimeMillis();
        long rangeStart = now - 24L * 60L * 60L * 1000L;
        long rangeEnd = now + lookaheadDays * 24L * 60L * 60L * 1000L;

        String[] calendarProjection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.STATUS,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        };

        try (Cursor cursor = CalendarContract.Instances.query(
                context.getContentResolver(), calendarProjection, rangeStart, rangeEnd)) {
            if (cursor == null) {
                return output;
            }
            int idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID);
            int titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE);
            int locationIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION);
            int beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN);
            int endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END);
            int allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY);
            int colorIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.DISPLAY_COLOR);
            int statusIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS);
            int attendeeIndex = cursor.getColumnIndexOrThrow(
                    CalendarContract.Instances.SELF_ATTENDEE_STATUS);
            int calendarIdIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID);
            int calendarNameIndex = cursor.getColumnIndexOrThrow(
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME);
            Comparator<CalendarRow> earliestFirst = Comparator
                    .comparingLong((CalendarRow row) -> row.begin)
                    .thenComparingLong(row -> row.id);
            PriorityQueue<CalendarRow> rows = new PriorityQueue<>(
                    maxEvents, earliestFirst.reversed());
            while (cursor.moveToNext()) {
                long end = cursor.getLong(endIndex);
                int status = cursor.getInt(statusIndex);
                int attendeeStatus = cursor.getInt(attendeeIndex);
                long calendarId = cursor.getLong(calendarIdIndex);
                if (end <= now
                        || status == CalendarContract.Events.STATUS_CANCELED
                        || attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                        || (hasCalendarSelection && !selectedCalendarIds.contains(calendarId))) {
                    continue;
                }
                String rawTitle = cursor.getString(titleIndex);
                String title = rawTitle != null && !rawTitle.trim().isEmpty()
                        ? rawTitle.trim()
                        : "日程";
                String rawLocation = cursor.getString(locationIndex);
                String location = hideLocation || rawLocation == null
                        ? ""
                        : rawLocation.trim();
                String calendarName = nonBlank(cursor.getString(calendarNameIndex), "");
                CalendarRow row = new CalendarRow(
                        cursor.getLong(idIndex),
                        redacted ? "" : title,
                        location,
                        cursor.getLong(beginIndex),
                        end,
                        cursor.getInt(allDayIndex),
                        cursor.getInt(colorIndex),
                        redacted ? "" : calendarName);
                if (rows.size() < maxEvents) {
                    rows.add(row);
                } else if (earliestFirst.compare(row, rows.peek()) < 0) {
                    rows.poll();
                    rows.add(row);
                }
            }
            List<CalendarRow> sortedRows = new ArrayList<>(rows);
            sortedRows.sort(earliestFirst);
            for (CalendarRow row : sortedRows) {
                output.addRow(row.toObjectArray());
            }
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the provider was alive.
        }
        return output;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceAllowedCaller();
        Context context = attachedContext();
        SharedPreferences prefs = context.getSharedPreferences(AgendaContract.PREFS, Context.MODE_PRIVATE);
        if (AgendaContract.METHOD_GET_CONFIG.equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean(AgendaContract.KEY_ENABLED,
                    prefs.getBoolean(AgendaContract.KEY_ENABLED, true));
            result.putBoolean(AgendaContract.KEY_OPEN_ON_LOCKSCREEN_CLICK,
                    prefs.getBoolean(AgendaContract.KEY_OPEN_ON_LOCKSCREEN_CLICK, true));
            result.putInt(AgendaContract.KEY_MAX_EVENTS,
                    clamp(prefs.getInt(AgendaContract.KEY_MAX_EVENTS, 1), 1, 3));
            result.putInt(AgendaContract.KEY_TOP_OFFSET_DP,
                    clamp(prefs.getInt(AgendaContract.KEY_TOP_OFFSET_DP, 210), 120, 520));
            result.putInt(AgendaContract.KEY_CLOCK_GAP_DP,
                    AgendaContract.readClockGapDp(prefs));
            String privacyMode = AgendaContract.readPrivacyMode(prefs);
            result.putString(AgendaContract.KEY_PRIVACY_MODE, privacyMode);
            result.putBoolean(AgendaContract.KEY_PRIVACY_REDACTED,
                    redactsDetails(context, privacyMode));
            result.putBoolean("calendar_permission",
                    context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                            == PackageManager.PERMISSION_GRANTED);
            return result;
        }
        if (AgendaContract.METHOD_REPORT_HOOK.equals(method)) {
            String version = extras == null ? "" : extras.getString(AgendaContract.KEY_SYSTEMUI_VERSION, "");
            prefs.edit()
                    .putLong(AgendaContract.KEY_LAST_HOOK_TIME, System.currentTimeMillis())
                    .putString(AgendaContract.KEY_SYSTEMUI_VERSION, version)
                    .apply();
            return Bundle.EMPTY;
        }
        if (AgendaContract.METHOD_LIST_CALENDARS.equals(method)) {
            return listCalendars(context);
        }
        return super.call(method, arg, extras);
    }

    private Bundle listCalendars(Context context) {
        Bundle result = new Bundle();
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> accounts = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{
                        CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Calendars.ACCOUNT_NAME,
                        CalendarContract.Calendars.VISIBLE
                },
                CalendarContract.Calendars.VISIBLE + "=1",
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    ids.add(cursor.getLong(0));
                    names.add(nonBlank(cursor.getString(1), "未命名日历"));
                    accounts.add(nonBlank(cursor.getString(2), "本机"));
                }
            }
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the provider was alive.
        }
        long[] idArray = new long[ids.size()];
        String[] nameArray = names.toArray(new String[0]);
        String[] accountArray = accounts.toArray(new String[0]);
        for (int i = 0; i < ids.size(); i++) {
            idArray[i] = ids.get(i);
        }
        result.putLongArray(AgendaContract.BUNDLE_CALENDAR_IDS, idArray);
        result.putStringArray(AgendaContract.BUNDLE_CALENDAR_NAMES, nameArray);
        result.putStringArray(AgendaContract.BUNDLE_CALENDAR_ACCOUNTS, accountArray);
        return result;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /**
     * True when the current privacy mode withholds titles and calendar names. The "认证后显示详情"
     * mode keeps the details hidden as long as the device actually requires authentication; a device
     * without a secure lock has nothing to authenticate, so nothing is withheld there.
     */
    private static boolean redactsDetails(Context context, String privacyMode) {
        if (AgendaContract.hidesDetails(privacyMode)) {
            return true;
        }
        if (!AgendaContract.PRIVACY_AFTER_AUTH.equals(privacyMode)) {
            return false;
        }
        KeyguardManager keyguard =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    @Override
    public String getType(Uri uri) {
        enforceAgendaUri(uri);
        enforceAllowedCaller();
        return "vnd.android.cursor.dir/vnd.hyperagenda.event";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        enforceAgendaUri(uri);
        enforceAllowedCaller();
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        enforceAgendaUri(uri);
        enforceAllowedCaller();
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        enforceAgendaUri(uri);
        enforceAllowedCaller();
        throw new UnsupportedOperationException("Read only");
    }

    private void enforceAgendaUri(Uri uri) {
        if (!AgendaContract.CONTENT_URI.equals(uri)) {
            throw new IllegalArgumentException("Unsupported agenda URI: " + uri);
        }
    }

    private void ensureCalendarObserver() {
        Context context = getContext();
        if (observerRegistered || context == null
                || context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    context.getContentResolver().notifyChange(AgendaContract.CONTENT_URI, null);
                }
            };
            int registered = 0;
            for (Uri uri : CALENDAR_OBSERVED_URIS) {
                try {
                    context.getContentResolver().registerContentObserver(uri, true, observer);
                    registered++;
                } catch (SecurityException ignored) {
                    // Some provider implementations may reject a secondary URI.
                }
            }
            if (registered > 0) {
                calendarObserver = observer;
                observerRegistered = true;
            }
        } catch (SecurityException ignored) {
            observerRegistered = false;
        }
    }

    private void enforceAllowedCaller() {
        Context context = attachedContext();
        int callerUid = Binder.getCallingUid();
        if (callerUid == android.os.Process.myUid()) {
            return;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
        if (packages != null && Arrays.asList(packages).contains("com.android.systemui")) {
            return;
        }
        throw new SecurityException("Agenda data is only available to SystemUI");
    }

    private Context attachedContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider is not attached");
        }
        return context;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class CalendarRow {
        final long id;
        final String title;
        final String location;
        final long begin;
        final long end;
        final int allDay;
        final int color;
        final String calendarName;

        CalendarRow(long id, String title, String location, long begin, long end,
                    int allDay, int color, String calendarName) {
            this.id = id;
            this.title = title;
            this.location = location;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
            this.color = color;
            this.calendarName = calendarName;
        }

        Object[] toObjectArray() {
            return new Object[]{
                    id, title, location, begin, end, allDay, color, calendarName
            };
        }
    }

}
