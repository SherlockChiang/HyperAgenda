package com.vince.hyperagenda.xposed;

import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.provider.CalendarContract;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class AgendaEventLauncher {
    private static final String CALENDAR_PACKAGE = "com.android.calendar";
    private static final String EVENT_MIME_TYPE = "vnd.android.cursor.item/event";

    private AgendaEventLauncher() {
    }

    /** Returns true when the event was handed to the system, so the caller can react to failure. */
    static boolean open(Context context, AgendaEvent event) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id),
                    EVENT_MIME_TYPE);
            intent.setPackage(CALENDAR_PACKAGE);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin);
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int requestCode = (int) (event.id ^ (event.id >>> 32));
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Object activityStarter = findActivityStarter(context);
            if (activityStarter != null) {
                if (callActivityStarter(activityStarter,
                        "startPendingIntentDismissingKeyguard", pendingIntent)) {
                    XposedBridge.log("HyperAgenda: event queued via "
                            + "startPendingIntentDismissingKeyguard id=" + event.id);
                    return true;
                }
                if (callActivityStarter(activityStarter,
                        "postStartActivityDismissingKeyguard", pendingIntent)) {
                    XposedBridge.log("HyperAgenda: event queued via "
                            + "postStartActivityDismissingKeyguard id=" + event.id);
                    return true;
                }
                if (callActivityStarter(activityStarter, "startActivity", intent, true)) {
                    XposedBridge.log("HyperAgenda: event queued via startActivity fallback id="
                            + event.id);
                    return true;
                }
            }

            pendingIntent.send();
            XposedBridge.log("HyperAgenda: event sent directly behind keyguard id=" + event.id);
            return true;
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: cannot open calendar event " + event.id + ": " + error);
            return false;
        }
    }

    private static Object findActivityStarter(Context context) {
        ClassLoader loader = context.getClassLoader();
        Class<?> activityStarterClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.plugins.ActivityStarter", loader);
        if (activityStarterClass == null) {
            XposedBridge.log("HyperAgenda: ActivityStarter class unavailable");
            return null;
        }

        try {
            Class<?> interfacesManager = XposedHelpers.findClassIfExists(
                    "com.miui.systemui.interfacesmanager.InterfacesImplManager", loader);
            if (interfacesManager != null) {
                Object starter = XposedHelpers.callStaticMethod(
                        interfacesManager, "getImpl", activityStarterClass);
                if (starter != null) {
                    XposedBridge.log("HyperAgenda: ActivityStarter source=interfaces-manager");
                    return starter;
                }
            }
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: InterfacesImplManager lookup failed: " + error);
        }

        try {
            Class<?> dependency = XposedHelpers.findClassIfExists(
                    "com.android.systemui.Dependency", loader);
            if (dependency != null) {
                Object starter = XposedHelpers.callStaticMethod(
                        dependency, "get", activityStarterClass);
                if (starter != null) {
                    XposedBridge.log("HyperAgenda: ActivityStarter source=dependency");
                    return starter;
                }
            }
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: Dependency lookup failed: " + error);
        }
        return null;
    }

    private static boolean callActivityStarter(Object activityStarter, String method,
                                               Object... arguments) {
        try {
            XposedHelpers.callMethod(activityStarter, method, arguments);
            return true;
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: ActivityStarter." + method + " failed: " + error);
            return false;
        }
    }
}
