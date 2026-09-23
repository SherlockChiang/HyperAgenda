package com.vince.hyperagenda.data;

import android.Manifest;
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
            AgendaContract.COL_COLOR
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
        boolean showTitles = prefs.getBoolean(AgendaContract.KEY_SHOW_TITLES, true);
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
                CalendarContract.Instances.SELF_ATTENDEE_STATUS
        };

        try (Cursor cursor = CalendarContract.Instances.query(
                context.getContentResolver(), calendarProjection, rangeStart, rangeEnd)) {
            if (cursor == null) {
                return output;
            }
            Comparator<CalendarRow> earliestFirst = Comparator
                    .comparingLong((CalendarRow row) -> row.begin)
                    .thenComparingLong(row -> row.id);
            PriorityQueue<CalendarRow> rows = new PriorityQueue<>(
                    maxEvents, earliestFirst.reversed());
            while (cursor.moveToNext()) {
                long end = cursor.getLong(4);
                int status = cursor.getInt(7);
                int attendeeStatus = cursor.getInt(8);
                if (end <= now
                        || status == CalendarContract.Events.STATUS_CANCELED
                        || attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) {
                    continue;
                }
                String rawTitle = cursor.getString(1);
                String title = showTitles && rawTitle != null && !rawTitle.trim().isEmpty()
                        ? rawTitle.trim()
                        : "日程";
                String rawLocation = cursor.getString(2);
                String location = showTitles && rawLocation != null
                        ? rawLocation.trim()
                        : "";
                CalendarRow row = new CalendarRow(
                        cursor.getLong(0),
                        title,
                        location,
                        cursor.getLong(3),
                        end,
                        cursor.getInt(5),
                        cursor.getInt(6));
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
        return super.call(method, arg, extras);
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

        CalendarRow(long id, String title, String location, long begin, long end,
                    int allDay, int color) {
            this.id = id;
            this.title = title;
            this.location = location;
            this.begin = begin;
            this.end = end;
            this.allDay = allDay;
            this.color = color;
        }

        Object[] toObjectArray() {
            return new Object[]{id, title, location, begin, end, allDay, color};
        }
    }

}
