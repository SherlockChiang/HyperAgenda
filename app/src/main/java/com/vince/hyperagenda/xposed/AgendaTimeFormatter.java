package com.vince.hyperagenda.xposed;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Turns raw event timestamps into the short, glanceable labels shown on the keyguard.
 *
 * <p>Only java.util is used, so the logic stays cheap on the keyguard main thread and can be
 * exercised without the Android framework.
 */
final class AgendaTimeFormatter {
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long DAY_MS = 24L * HOUR_MS;
    private static final long RELATIVE_HOUR_LIMIT_MS = 6L * HOUR_MS;
    private static final int WEEKDAY_LIMIT_DAYS = 7;

    /** Headline plus an optional cross-day end note. */
    static final class Label {
        final String headline;
        final String endNote;

        Label(String headline, String endNote) {
            this.headline = headline;
            this.endNote = endNote;
        }
    }

    private AgendaTimeFormatter() {
    }

    static Label describe(AgendaEvent event, long now, Locale locale) {
        return describe(event.allDay, event.begin, event.end, now, locale, TimeZone.getDefault());
    }

    static Label describe(boolean allDay, long begin, long end, long now,
                          Locale locale, TimeZone zone) {
        Locale effectiveLocale = locale == null ? Locale.getDefault() : locale;
        TimeZone effectiveZone = zone == null ? TimeZone.getDefault() : zone;
        long today = epochDay(now, effectiveZone);
        long beginDay = epochDay(begin, effectiveZone);
        long endDay = epochDay(end, effectiveZone);

        if (allDay) {
            // All-day events end at midnight of the following day, so the last day is end - 1ms.
            String endNote = endDay > beginDay + 1
                    ? "至 " + dayLabel(endDay - 1, today, end - 1, effectiveLocale, effectiveZone)
                    : null;
            return new Label(allDayHeadline(beginDay, today, begin, effectiveLocale, effectiveZone),
                    endNote);
        }

        if (begin <= now && end > now) {
            return new Label("进行中 · 还剩 " + duration(end - now),
                    endNote(beginDay, endDay, today, end, effectiveLocale, effectiveZone));
        }

        long delta = begin - now;
        String headline;
        if (delta <= MINUTE_MS) {
            headline = "即将开始";
        } else if (delta < HOUR_MS) {
            headline = Math.max(1L, delta / MINUTE_MS) + " 分钟后";
        } else if (beginDay == today && delta < RELATIVE_HOUR_LIMIT_MS) {
            headline = Math.max(1L, Math.round((double) delta / HOUR_MS)) + " 小时后";
        } else {
            headline = startHeadline(beginDay, today, begin, effectiveLocale, effectiveZone);
        }
        return new Label(headline,
                endNote(beginDay, endDay, today, end, effectiveLocale, effectiveZone));
    }

    /** Human readable length, used for "还剩 …" while an event is running. */
    static String duration(long millis) {
        long minutes = Math.max(0L, millis) / MINUTE_MS;
        if (minutes < 1L) {
            return "不到 1 分钟";
        }
        if (minutes < 60L) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        if (hours < 24L) {
            return restMinutes == 0L
                    ? hours + " 小时"
                    : hours + " 小时 " + restMinutes + " 分钟";
        }
        long days = hours / 24L;
        long restHours = hours % 24L;
        return restHours == 0L ? days + " 天" : days + " 天 " + restHours + " 小时";
    }

    private static String allDayHeadline(long beginDay, long today, long begin,
                                         Locale locale, TimeZone zone) {
        if (beginDay == today) {
            return "全天";
        }
        return dayLabel(beginDay, today, begin, locale, zone) + " 全天";
    }

    private static String startHeadline(long beginDay, long today, long begin,
                                        Locale locale, TimeZone zone) {
        long diff = beginDay - today;
        if (diff >= 0 && diff < WEEKDAY_LIMIT_DAYS) {
            return dayLabel(beginDay, today, begin, locale, zone)
                    + " " + format("HH:mm", begin, locale, zone);
        }
        return format("M/d HH:mm", begin, locale, zone);
    }

    private static String endNote(long beginDay, long endDay, long today, long end,
                                  Locale locale, TimeZone zone) {
        if (endDay <= beginDay) {
            return null;
        }
        return dayLabel(endDay, today, end, locale, zone)
                + " " + format("HH:mm", end, locale, zone) + " 结束";
    }

    private static String dayLabel(long day, long today, long millis, Locale locale, TimeZone zone) {
        long diff = day - today;
        if (diff == 0L) {
            return "今天";
        }
        if (diff == 1L) {
            return "明天";
        }
        if (diff == 2L) {
            return "后天";
        }
        if (diff > 2L && diff < WEEKDAY_LIMIT_DAYS) {
            return format("EEE", millis, locale, zone);
        }
        return format("M/d", millis, locale, zone);
    }

    private static String format(String pattern, long millis, Locale locale, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, locale);
        formatter.setTimeZone(zone);
        return formatter.format(new Date(millis));
    }

    private static long epochDay(long millis, TimeZone zone) {
        return Math.floorDiv(millis + zone.getOffset(millis), DAY_MS);
    }
}
