package com.vince.hyperagenda.xposed;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vince.hyperagenda.data.AgendaContract;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class AgendaOverlay {
    private static final String TAG = "hyperagenda_lock_screen_overlay";
    private static final float MIN_VISIBLE_CLOCK_ALPHA = 0.60f;
    private static final long BOUNCER_SCAN_INTERVAL_MS = 50L;
    private static final String KEYGUARD_INFO_LAYER_VIEW_ID = "keyguard_info_layer";
    private static final String FOREGROUND_CLOCK_CONTAINER_VIEW_ID =
            "miui_keyguard_foreground_clock_container";
    private static final String CLOCK_CONTAINER_VIEW_ID = "miui_keyguard_clock_container";
    private static final String CLOCK_ANIMATION_VIEW_ID = "clock_animation_container";
    private static final String[] CLOCK_STYLE_VIEW_IDS = {
            "text_area"
    };
    private static final String[] BOUNCER_VIEW_IDS = {
            "keyguard_bouncer_container",
            "keyguard_password_view",
            "keyguard_pattern_view",
            "keyguard_pin_view",
            "keyguard_sim_pin_view",
            "keyguard_security_lockout_view",
            "alternate_bouncer"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean(false);
    private static final ViewTreeObserver.OnPreDrawListener POSITION_LISTENER = () -> {
        ensureNativeOverlay();
        updatePosition();
        return true;
    };

    private static WeakReference<LinearLayout> overlayRef = new WeakReference<>(null);
    private static WeakReference<View> clockRef = new WeakReference<>(null);
    private static final List<WeakReference<View>> clockAnchorRefs = new ArrayList<>();
    private static WeakReference<FrameLayout> injectionHostRef = new WeakReference<>(null);
    private static WeakReference<FrameLayout> clockHostRef = new WeakReference<>(null);
    private static WeakReference<View> clockLayoutRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> layoutRootRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> bouncerRootRef = new WeakReference<>(null);
    private static WeakReference<ViewTreeObserver> layoutObserverRef = new WeakReference<>(null);
    private static boolean observersRegistered;
    private static boolean contentAvailable;
    private static boolean openOnLockscreenClick = true;
    private static int clockGapDp = AgendaContract.DEFAULT_CLOCK_GAP_DP;
    private static int[] clockStyleViewIds;
    private static int[] bouncerViewIds;
    private static WeakReference<TextView> nativeStyleSourceRef = new WeakReference<>(null);
    private static NativeTextStyle cachedNativeTextStyle;
    private static String cachedNativeTextStyleSource = "";
    private static int nativeStyleFingerprint;
    private static long lastNativeStyleSearchUptime;
    private static long lastNativeStyleCheckUptime;
    private static long lastBouncerScanUptime;
    private static boolean cachedBouncerShowing;
    private static String lastLoggedNativeStyleSource = "";
    private static int lastLoggedPositionTop = -1;
    private static String lastLoggedPositionSource = "";
    private static long lastPositionLogUptime;
    private static String lastLoggedVisibilityReason = "";
    private static String lastLoggedNativeHost = "";
    private static long launchSuppressedUntilUptime;
    private static String lastLoggedClockVisualSource = "";

    private AgendaOverlay() {
    }

    static void attach(View anchor) {
        try {
            View root = anchor.getRootView();
            if (!(root instanceof ViewGroup)) {
                return;
            }
            ViewGroup rootGroup = (ViewGroup) root;
            switchActiveRoot(rootGroup);
            rememberClockAnchor(anchor);
            observeLayout(rootGroup);
            ensureNativeOverlay();
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: overlay attach failed: " + error);
        }
    }

    static void detach(View anchor) {
        forgetClockAnchor(anchor);
        View clock = clockRef.get();
        if (anchor == clock) {
            setOverlayVisible(false);
            overlayRef = new WeakReference<>(null);
            clockRef = new WeakReference<>(null);
            injectionHostRef = new WeakReference<>(null);
            clockHostRef = new WeakReference<>(null);
            clockLayoutRef = new WeakReference<>(null);
        }
    }

    static View findTouchTarget(ViewGroup root, MotionEvent event) {
        if (root != layoutRootRef.get()) {
            return null;
        }
        updatePosition();
        LinearLayout overlay = overlayRef.get();
        if (overlay == null || !isEffectivelyVisible(overlay, root)) {
            return null;
        }
        Rect bounds = new Rect();
        for (int i = 0; i < overlay.getChildCount(); i++) {
            View row = overlay.getChildAt(i);
            if (isTouchTargetActive(root, row) && row.getGlobalVisibleRect(bounds)
                    && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()))) {
                return row;
            }
        }
        return null;
    }

    static boolean isTouchTargetActive(ViewGroup root, View target) {
        if (root != layoutRootRef.get() || target == null) {
            return false;
        }
        updatePosition();
        LinearLayout overlay = overlayRef.get();
        return openOnLockscreenClick && overlay != null && target.getParent() == overlay
                && overlay.getParent() == injectionHostRef.get()
                && target.isEnabled() && target.isClickable()
                && isEffectivelyVisible(target, root);
    }

    private static void switchActiveRoot(ViewGroup root) {
        ViewGroup previousRoot = layoutRootRef.get();
        if (previousRoot != null && previousRoot != root) {
            LinearLayout previousOverlay = overlayRef.get();
            if (previousOverlay != null) {
                previousOverlay.animate().cancel();
                previousOverlay.setVisibility(View.GONE);
                if (previousOverlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) previousOverlay.getParent()).removeView(previousOverlay);
                }
            }
            overlayRef = new WeakReference<>(null);
            clockRef = new WeakReference<>(null);
            injectionHostRef = new WeakReference<>(null);
            clockHostRef = new WeakReference<>(null);
            clockLayoutRef = new WeakReference<>(null);
            nativeStyleSourceRef = new WeakReference<>(null);
            nativeStyleFingerprint = 0;
            lastNativeStyleSearchUptime = 0L;
            lastNativeStyleCheckUptime = 0L;
            bouncerRootRef = new WeakReference<>(null);
            lastBouncerScanUptime = 0L;
            cachedBouncerShowing = false;
            lastLoggedClockVisualSource = "";
            lastLoggedNativeHost = "";
        }
        if (previousRoot != root) {
            XposedBridge.log("HyperAgenda: active keyguard root=" + root.getClass().getName());
        }
    }

    private static boolean isClockContainer(View view) {
        return view != null && "KeyguardClockContainer".equals(view.getClass().getSimpleName());
    }

    private static View findClockContainer(View root, boolean visibleOnly) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty() && visited < 4096) {
            View candidate = pending.removeFirst();
            visited++;
            if (isClockContainer(candidate)
                    && (!visibleOnly || isEffectivelyVisible(candidate, (ViewGroup) root))) {
                return candidate;
            }
            if (candidate instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) candidate;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    private static void rememberClockAnchor(View anchor) {
        for (int i = clockAnchorRefs.size() - 1; i >= 0; i--) {
            View candidate = clockAnchorRefs.get(i).get();
            if (candidate == null) {
                clockAnchorRefs.remove(i);
            } else if (candidate == anchor) {
                return;
            }
        }
        clockAnchorRefs.add(new WeakReference<>(anchor));
    }

    private static void forgetClockAnchor(View anchor) {
        for (int i = clockAnchorRefs.size() - 1; i >= 0; i--) {
            View candidate = clockAnchorRefs.get(i).get();
            if (candidate == null || candidate == anchor) {
                clockAnchorRefs.remove(i);
            }
        }
    }

    private static View findRememberedClock(ViewGroup root) {
        View fallback = null;
        for (int i = clockAnchorRefs.size() - 1; i >= 0; i--) {
            View candidate = clockAnchorRefs.get(i).get();
            if (candidate == null) {
                clockAnchorRefs.remove(i);
                continue;
            }
            if (candidate.getRootView() != root) {
                continue;
            }
            if (isEffectivelyVisible(candidate, root)) {
                return candidate;
            }
            fallback = candidate;
        }
        return fallback;
    }

    private static void ensureNativeOverlay() {
        ViewGroup root = layoutRootRef.get();
        if (root == null) {
            return;
        }

        LinearLayout currentOverlay = overlayRef.get();
        FrameLayout currentInjectionHost = injectionHostRef.get();
        FrameLayout currentClockHost = clockHostRef.get();
        View currentClockLayout = clockLayoutRef.get();
        View currentClock = clockRef.get();
        if (currentOverlay != null
                && currentInjectionHost != null
                && currentClockHost != null
                && currentClockLayout != null
                && currentClock != null
                && currentOverlay.getParent() == currentInjectionHost
                && currentInjectionHost.getRootView() == root
                && currentClockHost.getRootView() == root
                && currentClockLayout.getRootView() == root
                && currentClock.getRootView() == root) {
            keepOverlayOnTop(currentOverlay, currentInjectionHost);
            return;
        }

        NativeBinding binding = resolveNativeBinding(root);
        if (binding == null) {
            return;
        }

        if (currentOverlay != null && currentOverlay.getParent() != binding.injectionHost) {
            currentOverlay.animate().cancel();
            currentOverlay.setVisibility(View.GONE);
            if (currentOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) currentOverlay.getParent()).removeView(currentOverlay);
            }
            currentOverlay = null;
            overlayRef = new WeakReference<>(null);
        }

        clockRef = new WeakReference<>(binding.clock);
        injectionHostRef = new WeakReference<>(binding.injectionHost);
        clockHostRef = new WeakReference<>(binding.clockHost);
        clockLayoutRef = new WeakReference<>(binding.clockLayout);

        if (currentOverlay == null) {
            View existing = binding.injectionHost.findViewWithTag(TAG);
            if (existing instanceof LinearLayout
                    && existing.getParent() == binding.injectionHost) {
                currentOverlay = (LinearLayout) existing;
            } else {
                Context context = binding.injectionHost.getContext();
                currentOverlay = new LinearLayout(context);
                currentOverlay.setTag(TAG);
                currentOverlay.setOrientation(LinearLayout.VERTICAL);
                currentOverlay.setGravity(Gravity.CENTER_VERTICAL);
                currentOverlay.setPadding(
                        dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
                currentOverlay.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                currentOverlay.setClickable(false);
                currentOverlay.setFocusable(false);
                currentOverlay.setVisibility(View.GONE);

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                params.leftMargin = dp(context, 20);
                params.rightMargin = dp(context, 20);
                params.topMargin = 0;
                binding.injectionHost.addView(currentOverlay, params);
            }
            overlayRef = new WeakReference<>(currentOverlay);
            Context appContext = binding.injectionHost.getContext().getApplicationContext();
            registerObservers(appContext);
            refresh(appContext);
        }
        keepOverlayOnTop(currentOverlay, binding.injectionHost);

        String hostDescription = "injection="
                + resourceEntryName(binding.injectionHost) + "/"
                + binding.injectionHost.getClass().getSimpleName()
                + " clockHost=" + resourceEntryName(binding.clockHost) + "/"
                + binding.clockHost.getClass().getSimpleName() + " -> "
                + resourceEntryName(binding.clockLayout) + "/"
                + binding.clockLayout.getClass().getSimpleName() + " clock="
                + binding.clock.getClass().getName();
        if (!lastLoggedNativeHost.equals(hostDescription)) {
            lastLoggedNativeHost = hostDescription;
            XposedBridge.log("HyperAgenda: native host=" + hostDescription);
        }
    }

    private static NativeBinding resolveNativeBinding(ViewGroup root) {
        Context context = root.getContext();
        int injectionHostId = context.getResources().getIdentifier(
                KEYGUARD_INFO_LAYER_VIEW_ID, "id", "com.android.systemui");
        if (injectionHostId == 0) {
            return null;
        }
        View injectionHost = root.findViewById(injectionHostId);
        if (!(injectionHost instanceof FrameLayout)) {
            return null;
        }

        int foregroundId = context.getResources().getIdentifier(
                FOREGROUND_CLOCK_CONTAINER_VIEW_ID, "id", "com.android.systemui");
        if (foregroundId == 0) {
            return null;
        }
        View foreground = root.findViewById(foregroundId);
        if (!(foreground instanceof FrameLayout)) {
            return null;
        }

        int clockId = context.getResources().getIdentifier(
                CLOCK_CONTAINER_VIEW_ID, "id", "com.android.systemui");
        View clock = clockId == 0 ? null : root.findViewById(clockId);
        if (clock == null) {
            clock = findClockContainer(root, false);
        }
        if (clock == null) {
            clock = findRememberedClock(root);
        }
        if (clock == null) {
            return null;
        }

        int clockLayoutId = context.getResources().getIdentifier(
                CLOCK_ANIMATION_VIEW_ID, "id", "com.android.systemui");
        if (clockLayoutId == 0) {
            return null;
        }
        View clockLayout = foreground.findViewById(clockLayoutId);
        if (clockLayout == null || clockLayout == foreground) {
            return null;
        }
        return new NativeBinding(
                (FrameLayout) injectionHost,
                (FrameLayout) foreground,
                clock,
                clockLayout);
    }

    private static void keepOverlayOnTop(View overlay, FrameLayout injectionHost) {
        if (injectionHost.indexOfChild(overlay) != injectionHost.getChildCount() - 1) {
            overlay.bringToFront();
        }
    }

    private static void observeLayout(ViewGroup root) {
        ViewGroup previousRoot = layoutRootRef.get();
        ViewTreeObserver previousObserver = layoutObserverRef.get();
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (previousRoot == root && previousObserver == observer && observer.isAlive()) {
            return;
        }
        if (previousObserver != null && previousObserver.isAlive()) {
            previousObserver.removeOnPreDrawListener(POSITION_LISTENER);
        }
        if (observer.isAlive()) {
            observer.addOnPreDrawListener(POSITION_LISTENER);
            layoutRootRef = new WeakReference<>(root);
            layoutObserverRef = new WeakReference<>(observer);
        }
    }

    private static void registerObservers(Context context) {
        if (observersRegistered) {
            return;
        }
        try {
            ContentObserver observer = new ContentObserver(MAIN) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh(context);
                }
            };
            context.getContentResolver().registerContentObserver(
                    AgendaContract.CONTENT_URI, true, observer);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                            || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        setOverlayVisible(false);
                    } else {
                        refresh(context);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            observersRegistered = true;
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: observer registration failed: " + error);
        }
    }

    private static void refresh(Context context) {
        if (context == null) {
            return;
        }
        if (!REFRESHING.compareAndSet(false, true)) {
            REFRESH_PENDING.set(true);
            return;
        }
        WORKER.execute(() -> {
            try {
                ContentResolver resolver = context.getContentResolver();
                Bundle config = resolver.call(
                        AgendaContract.CONTENT_URI, AgendaContract.METHOD_GET_CONFIG, null, null);
                boolean enabled = config != null
                        && config.getBoolean(AgendaContract.KEY_ENABLED, true)
                        && config.getBoolean("calendar_permission", false);
                boolean openOnClick = config == null
                        || config.getBoolean(AgendaContract.KEY_OPEN_ON_LOCKSCREEN_CLICK, true);
                int gap = config == null ? AgendaContract.DEFAULT_CLOCK_GAP_DP
                        : config.getInt(
                                AgendaContract.KEY_CLOCK_GAP_DP,
                                AgendaContract.DEFAULT_CLOCK_GAP_DP);
                reportHook(resolver, context);

                List<AgendaEvent> events = enabled ? queryEvents(resolver) : new ArrayList<>();
                MAIN.post(() -> render(context, enabled, gap, openOnClick, events));
            } catch (Throwable error) {
                XposedBridge.log("HyperAgenda: refresh failed: " + error);
                MAIN.post(() -> setOverlayVisible(false));
            } finally {
                REFRESHING.set(false);
                if (REFRESH_PENDING.getAndSet(false)) {
                    refresh(context);
                }
            }
        });
    }

    private static List<AgendaEvent> queryEvents(ContentResolver resolver) {
        List<AgendaEvent> events = new ArrayList<>();
        try (Cursor cursor = resolver.query(AgendaContract.CONTENT_URI, null, null, null, null)) {
            if (cursor == null) {
                return events;
            }
            int titleIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_TITLE);
            int idIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_EVENT_ID);
            int locationIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_LOCATION);
            int beginIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_BEGIN);
            int endIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_END);
            int allDayIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_ALL_DAY);
            int colorIndex = cursor.getColumnIndexOrThrow(AgendaContract.COL_COLOR);
            while (cursor.moveToNext()) {
                events.add(new AgendaEvent(
                        cursor.getLong(idIndex),
                        cursor.getString(titleIndex),
                        cursor.getString(locationIndex),
                        cursor.getLong(beginIndex),
                        cursor.getLong(endIndex),
                        cursor.getInt(allDayIndex) != 0,
                        cursor.getInt(colorIndex)));
            }
        }
        return events;
    }

    private static void reportHook(ContentResolver resolver, Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.android.systemui", 0);
            long versionCode = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode;
            Bundle report = new Bundle();
            report.putString(AgendaContract.KEY_SYSTEMUI_VERSION,
                    info.versionName == null ? String.valueOf(versionCode) : info.versionName);
            resolver.call(AgendaContract.CONTENT_URI,
                    AgendaContract.METHOD_REPORT_HOOK, null, report);
        } catch (Throwable ignored) {
        }
    }

    private static void render(Context context, boolean enabled, int gap,
                               boolean openOnClick, List<AgendaEvent> events) {
        LinearLayout overlay = overlayRef.get();
        if (overlay == null) {
            return;
        }
        clockGapDp = Math.max(0, Math.min(64, gap));
        openOnLockscreenClick = openOnClick;
        contentAvailable = enabled && !events.isEmpty();
        overlay.removeAllViews();

        if (!contentAvailable || !shouldShowOnKeyguard(context)) {
            overlay.setVisibility(View.GONE);
            return;
        }

        long now = System.currentTimeMillis();
        for (int i = 0; i < events.size(); i++) {
            AgendaEvent event = events.get(i);
            AgendaTimeFormatter.Label label =
                    AgendaTimeFormatter.describe(event, now, Locale.getDefault());
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9));
            row.setClickable(openOnLockscreenClick);
            row.setFocusable(openOnLockscreenClick);
            row.setBackground(openOnLockscreenClick
                    ? createRippleBackground(context) : null);
            if (openOnLockscreenClick) {
                row.setOnClickListener(v -> openCalendarEvent(context, event));
            }

            View colorBar = new View(context);
            GradientDrawable barBackground = new GradientDrawable();
            barBackground.setColor(event.color == 0 ? Color.rgb(93, 168, 255) : opaque(event.color));
            barBackground.setCornerRadius(dp(context, 2));
            colorBar.setBackground(barBackground);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    dp(context, 3), dp(context, 38));
            barParams.rightMargin = dp(context, 12);
            row.addView(colorBar, barParams);

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);

            LinearLayout headline = new LinearLayout(context);
            headline.setOrientation(LinearLayout.HORIZONTAL);
            headline.setGravity(Gravity.CENTER_VERTICAL);
            TextView time = createText(context, label.headline, TextRole.TIME);
            LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeParams.rightMargin = dp(context, 10);
            headline.addView(time, timeParams);

            TextView title = createText(context, event.title, TextRole.TITLE);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            headline.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            textColumn.addView(headline);

            if (event.location != null && !event.location.isEmpty()) {
                TextView location = createText(
                        context, "地点 · " + event.location, TextRole.LOCATION);
                location.setMaxLines(1);
                location.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                locationParams.topMargin = dp(context, 3);
                textColumn.addView(location, locationParams);
            }
            row.addView(textColumn, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setContentDescription(describeForAccessibility(label, event));
            overlay.addView(row, rowParams(context, i == 0 ? 0 : 2, 0));
        }

        ViewGroup root = layoutRootRef.get();
        if (root != null) {
            updateNativeTextStyle(overlay, resolveVisibleClock(root), root, true);
        }
        updatePosition();
    }

    private static void updatePosition() {
        LinearLayout overlay = overlayRef.get();
        ViewGroup root = layoutRootRef.get();
        FrameLayout injectionHost = injectionHostRef.get();
        FrameLayout clockHost = clockHostRef.get();
        View clockLayout = clockLayoutRef.get();
        if (overlay == null || root == null || injectionHost == null || clockHost == null
                || clockLayout == null || overlay.getParent() != injectionHost) {
            return;
        }

        View clock = resolveVisibleClock(root);
        Integer nativeClockBottom = resolveNativeClockBottom(
                injectionHost, clockLayout, root);
        float clockAlpha = clock == null ? 0f : resolveClockVisualAlpha(clock, root);
        Context context = overlay.getContext();
        boolean onKeyguard = shouldShowOnKeyguard(context);
        boolean launchAllowed = SystemClock.uptimeMillis() >= launchSuppressedUntilUptime;
        boolean bouncerShowing = isBouncerShowing(root);
        boolean injectionHostVisible = isEffectivelyVisible(injectionHost, root);
        boolean clockHostVisible = isEffectivelyVisible(clockHost, root);
        boolean clockLayoutVisible = isEffectivelyVisible(clockLayout, root);
        boolean clockReady = nativeClockBottom != null
                && injectionHostVisible
                && clockHostVisible
                && clockLayoutVisible
                && clockAlpha > MIN_VISIBLE_CLOCK_ALPHA;
        boolean nativeStyleReady = cachedNativeTextStyle != null;
        if (contentAvailable && onKeyguard && launchAllowed && !bouncerShowing && clockReady) {
            nativeStyleReady = updateNativeTextStyle(overlay, clock, root, false);
        }
        boolean shouldBeVisible = contentAvailable
                && onKeyguard
                && launchAllowed
                && !bouncerShowing
                && clockReady
                && nativeStyleReady;
        String visibilityReason;
        if (!contentAvailable) {
            visibilityReason = "no-content";
        } else if (!onKeyguard) {
            visibilityReason = "not-keyguard";
        } else if (!launchAllowed) {
            visibilityReason = "launch-suppressed";
        } else if (bouncerShowing) {
            visibilityReason = "bouncer";
        } else if (!injectionHostVisible) {
            visibilityReason = "injection-host-not-visible";
        } else if (!clockHostVisible) {
            visibilityReason = "clock-host-not-visible";
        } else if (!clockLayoutVisible) {
            visibilityReason = "clock-layout-not-visible";
        } else if (clock == null || clockAlpha <= MIN_VISIBLE_CLOCK_ALPHA) {
            visibilityReason = "clock-not-visible";
        } else if (nativeClockBottom == null) {
            visibilityReason = "native-clock-position-unavailable";
        } else if (!nativeStyleReady) {
            visibilityReason = "style-not-ready";
        } else {
            visibilityReason = "visible";
        }
        if (!lastLoggedVisibilityReason.equals(visibilityReason)) {
            lastLoggedVisibilityReason = visibilityReason;
            XposedBridge.log("HyperAgenda: overlay visibility=" + visibilityReason
                    + " clockSource=native-clock-hierarchy"
                    + " clockAlpha=" + clockAlpha);
        }
        int targetVisibility = shouldBeVisible ? View.VISIBLE : View.GONE;
        if (overlay.getVisibility() != targetVisibility) {
            if (!shouldBeVisible) {
                overlay.animate().cancel();
                overlay.setAlpha(1f);
            }
            overlay.setVisibility(targetVisibility);
        }
        if (!shouldBeVisible) {
            return;
        }
        overlay.setAlpha(normalizeClockAlpha(clockAlpha));

        int top = nativeClockBottom + dp(context, clockGapDp);
        int minTop = dp(context, 120);
        if (top < minTop) {
            top = minTop;
        }

        ViewGroup.LayoutParams rawParams = overlay.getLayoutParams();
        if (!(rawParams instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) rawParams;
        if (params.topMargin != top) {
            params.topMargin = top;
            overlay.setLayoutParams(params);
        }
        if (Math.abs(overlay.getTranslationY()) >= 0.5f) {
            overlay.setTranslationY(0f);
        }

        long now = SystemClock.uptimeMillis();
        String positionSource = "native-clock-hierarchy";
        boolean sourceChanged = !lastLoggedPositionSource.equals(positionSource);
        boolean positionChanged = lastLoggedPositionTop != top;
        if (sourceChanged || (positionChanged && now - lastPositionLogUptime >= 750L)) {
            lastLoggedPositionTop = top;
            lastLoggedPositionSource = positionSource;
            lastPositionLogUptime = now;
            XposedBridge.log("HyperAgenda: overlay position source=" + positionSource
                    + " host=" + resourceEntryName(injectionHost)
                    + " clockHost=" + resourceEntryName(clockHost)
                    + " clockBottom=" + nativeClockBottom
                    + " gapDp=" + clockGapDp + " top=" + top);
        }
    }

    private static View resolveVisibleClock(ViewGroup root) {
        View clock = clockRef.get();
        FrameLayout clockHost = clockHostRef.get();
        if (clock != null && clockHost != null
                && clock.getRootView() == root
                && clockHost.getRootView() == root
                && isEffectivelyVisible(clock, root)) {
            return clock;
        }
        NativeBinding binding = resolveNativeBinding(root);
        if (binding != null && isEffectivelyVisible(binding.clock, root)) {
            clockRef = new WeakReference<>(binding.clock);
            injectionHostRef = new WeakReference<>(binding.injectionHost);
            clockHostRef = new WeakReference<>(binding.clockHost);
            clockLayoutRef = new WeakReference<>(binding.clockLayout);
            return binding.clock;
        }
        return null;
    }

    private static Integer resolveNativeClockBottom(ViewGroup injectionHost, View clockLayout,
                                                    ViewGroup root) {
        if (injectionHost.getRootView() != root || clockLayout.getRootView() != root) {
            return null;
        }
        Rect hostRect = new Rect();
        Rect clockRect = new Rect();
        if (!injectionHost.getGlobalVisibleRect(hostRect)
                || !clockLayout.getGlobalVisibleRect(clockRect)
                || hostRect.isEmpty() || clockRect.isEmpty()) {
            return null;
        }
        int bottom = clockRect.bottom - hostRect.top;
        return bottom > 0
                && (injectionHost.getHeight() <= 0 || bottom < injectionHost.getHeight())
                ? bottom : null;
    }

    private static float resolveClockVisualAlpha(View clock, ViewGroup root) {
        View visual = resolveClockVisualView(clock);
        String source = visual == clock
                ? "clock-container"
                : visual.getClass().getSimpleName() + "/" + resourceEntryName(visual);
        if (!lastLoggedClockVisualSource.equals(source)) {
            lastLoggedClockVisualSource = source;
            XposedBridge.log("HyperAgenda: clock visual source=" + source);
        }
        return effectiveAlpha(visual, root);
    }

    private static View resolveClockVisualView(View clock) {
        try {
            Object helper = XposedHelpers.getObjectField(clock, "mAnimationHelper");
            Object animation = helper == null ? null
                    : XposedHelpers.getObjectField(helper, "mClockAnima");
            Object allContainer = animation == null ? null
                    : XposedHelpers.getObjectField(animation, "mAllContainer");
            if (allContainer instanceof View) {
                View visual = (View) allContainer;
                if (visual.isAttachedToWindow()) {
                    return visual;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object controller = XposedHelpers.getObjectField(clock, "mMiuiClockController");
            Object clockView = controller == null ? null
                    : XposedHelpers.getObjectField(controller, "mClockView");
            if (clockView instanceof View) {
                View visual = (View) clockView;
                if (visual.isAttachedToWindow()) {
                    return visual;
                }
            }
        } catch (Throwable ignored) {
        }
        return clock;
    }

    private static float normalizeClockAlpha(float alpha) {
        float normalized = (alpha - MIN_VISIBLE_CLOCK_ALPHA)
                / (1f - MIN_VISIBLE_CLOCK_ALPHA);
        return Math.max(0f, Math.min(1f, normalized));
    }

    private static View findVisibleViewById(View searchRoot, ViewGroup root, int id) {
        if (searchRoot == null || id == 0) {
            return null;
        }
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(searchRoot);
        int visited = 0;
        View best = null;
        float bestAlpha = -1f;
        long bestArea = -1L;
        while (!pending.isEmpty() && visited < 4096) {
            View candidate = pending.removeFirst();
            visited++;
            if (candidate.getId() == id && isEffectivelyVisible(candidate, root)) {
                Rect rect = new Rect();
                if (candidate.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
                    float alpha = effectiveAlpha(candidate, root);
                    long area = (long) rect.width() * rect.height();
                    if (best == null || alpha > bestAlpha + 0.01f
                            || (Math.abs(alpha - bestAlpha) <= 0.01f && area > bestArea)) {
                        best = candidate;
                        bestAlpha = alpha;
                        bestArea = area;
                    }
                }
            }
            if (candidate instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) candidate;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        return best;
    }

    private static boolean updateNativeTextStyle(LinearLayout overlay, View clock,
                                                 ViewGroup root, boolean force) {
        long now = SystemClock.uptimeMillis();
        long retryInterval = cachedNativeTextStyle == null ? 100L : 1000L;
        if (!force && now - lastNativeStyleCheckUptime < retryInterval) {
            return cachedNativeTextStyle != null;
        }
        lastNativeStyleCheckUptime = now;
        TextView source = nativeStyleSourceRef.get();
        if (!isEffectivelyVisible(source, root)) {
            if (!force && now - lastNativeStyleSearchUptime < retryInterval) {
                return cachedNativeTextStyle != null;
            }
            lastNativeStyleSearchUptime = now;
            source = resolveNativeTextStyleSource(clock, root);
            nativeStyleSourceRef = new WeakReference<>(source);
        }

        NativeTextStyle style;
        String sourceName;
        if (source != null) {
            style = NativeTextStyle.from(source, overlay.getContext());
            sourceName = resourceEntryName(source) + "/" + source.getClass().getSimpleName();
            cachedNativeTextStyle = style;
            cachedNativeTextStyleSource = sourceName;
        } else if (cachedNativeTextStyle != null) {
            style = cachedNativeTextStyle;
            sourceName = "cached/" + cachedNativeTextStyleSource;
        } else {
            sourceName = "waiting";
            if (!lastLoggedNativeStyleSource.equals(sourceName)) {
                lastLoggedNativeStyleSource = sourceName;
                XposedBridge.log("HyperAgenda: text style source=" + sourceName);
            }
            return false;
        }

        int fingerprint = style.fingerprint();
        if (!force && fingerprint == nativeStyleFingerprint) {
            return true;
        }
        nativeStyleFingerprint = fingerprint;

        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(overlay);
        while (!pending.isEmpty()) {
            View candidate = pending.removeFirst();
            if (candidate instanceof TextView && candidate.getTag() instanceof TextRole) {
                style.apply((TextView) candidate, (TextRole) candidate.getTag());
            }
            if (candidate instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) candidate;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }

        if (!lastLoggedNativeStyleSource.equals(sourceName)) {
            lastLoggedNativeStyleSource = sourceName;
            XposedBridge.log("HyperAgenda: text style source=" + sourceName
                    + " sizePx=" + style.baseTextSizePx
                    + " color=#" + Integer.toHexString(style.textColor));
        }
        return true;
    }

    private static TextView resolveNativeTextStyleSource(View clock, ViewGroup root) {
        int[] ids = clockStyleViewIds;
        if (ids == null) {
            Context context = root.getContext();
            ids = new int[CLOCK_STYLE_VIEW_IDS.length];
            for (int i = 0; i < CLOCK_STYLE_VIEW_IDS.length; i++) {
                ids[i] = context.getResources().getIdentifier(
                        CLOCK_STYLE_VIEW_IDS[i], "id", "com.android.systemui");
            }
            clockStyleViewIds = ids;
        }

        for (int id : ids) {
            View styleArea = findVisibleViewById(root, root, id);
            TextView source = findBestVisibleTextView(styleArea, root);
            if (source != null) {
                return source;
            }
        }
        return findBestVisibleTextView(clock, root);
    }

    private static TextView findBestVisibleTextView(View searchRoot, ViewGroup root) {
        if (searchRoot == null) {
            return null;
        }
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(searchRoot);
        int visited = 0;
        TextView best = null;
        float bestScore = -Float.MAX_VALUE;
        float idealSize = sp(root.getContext(), 16f);
        while (!pending.isEmpty() && visited < 512) {
            View candidate = pending.removeFirst();
            visited++;
            if (candidate instanceof TextView && isEffectivelyVisible(candidate, root)) {
                TextView text = (TextView) candidate;
                CharSequence value = text.getText();
                CharSequence description = text.getContentDescription();
                if ((value != null && value.length() > 0)
                        || (description != null && description.length() > 0)) {
                    float score = effectiveAlpha(text, root) * 1000f
                            - Math.abs(text.getTextSize() - idealSize);
                    if (best == null || score > bestScore) {
                        best = text;
                        bestScore = score;
                    }
                }
            }
            if (candidate instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) candidate;
                for (int i = 0; i < group.getChildCount(); i++) {
                    pending.addLast(group.getChildAt(i));
                }
            }
        }
        return best;
    }

    private static String resourceEntryName(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "view";
        }
    }

    private static boolean isEffectivelyVisible(View view, ViewGroup root) {
        if (view == null || view.getRootView() != root || !view.isAttachedToWindow()
                || view.getVisibility() != View.VISIBLE || view.getWindowVisibility() != View.VISIBLE
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        return effectiveAlpha(view, root) > 0.02f;
    }

    private static float effectiveAlpha(View view, ViewGroup root) {
        if (view == null || view.getRootView() != root || !view.isAttachedToWindow()) {
            return 0f;
        }
        float alpha = 1f;
        View current = view;
        while (current != null) {
            if (current.getVisibility() != View.VISIBLE) {
                return 0f;
            }
            alpha *= current.getAlpha();
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }
        return alpha;
    }

    private static boolean isBouncerShowing(ViewGroup root) {
        long now = SystemClock.uptimeMillis();
        if (bouncerRootRef.get() == root
                && now - lastBouncerScanUptime < BOUNCER_SCAN_INTERVAL_MS) {
            return cachedBouncerShowing;
        }
        int[] ids = bouncerViewIds;
        if (ids == null) {
            Context context = root.getContext();
            ids = new int[BOUNCER_VIEW_IDS.length];
            for (int i = 0; i < BOUNCER_VIEW_IDS.length; i++) {
                ids[i] = context.getResources().getIdentifier(
                        BOUNCER_VIEW_IDS[i], "id", "com.android.systemui");
            }
            bouncerViewIds = ids;
        }
        boolean showing = false;
        for (int id : ids) {
            if (id == 0) {
                continue;
            }
            if (findVisibleViewById(root, root, id) != null) {
                showing = true;
                break;
            }
        }
        bouncerRootRef = new WeakReference<>(root);
        lastBouncerScanUptime = now;
        cachedBouncerShowing = showing;
        return showing;
    }

    private static boolean shouldShowOnKeyguard(Context context) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked()
                && power != null && power.isInteractive();
    }

    private static void setOverlayVisible(boolean visible) {
        LinearLayout overlay = overlayRef.get();
        if (overlay != null) {
            overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static String describeForAccessibility(AgendaTimeFormatter.Label label,
                                                   AgendaEvent event) {
        StringBuilder description = new StringBuilder(label.headline);
        if (event.title != null && !event.title.isEmpty()) {
            description.append('，').append(event.title);
        }
        if (event.location != null && !event.location.isEmpty()) {
            description.append("，地点 ").append(event.location);
        }
        if (label.endNote != null) {
            description.append('，').append(label.endNote);
        }
        return description.toString();
    }

    private static void openCalendarEvent(Context context, AgendaEvent event) {
        if (!openOnLockscreenClick) {
            return;
        }
        XposedBridge.log("HyperAgenda: event clicked id=" + event.id);
        launchSuppressedUntilUptime = SystemClock.uptimeMillis() + 1500L;
        setOverlayVisible(false);
        AgendaEventLauncher.open(context, event);
    }

    private static RippleDrawable createRippleBackground(Context context) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(Color.TRANSPARENT);
        content.setCornerRadius(dp(context, 16));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, 16));
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(45, 255, 255, 255)), content, mask);
    }

    private static TextView createText(Context context, String value, TextRole role) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTag(role);
        NativeTextStyle style = cachedNativeTextStyle;
        (style == null ? NativeTextStyle.fallback(context) : style).apply(view, role);
        return view;
    }

    private static LinearLayout.LayoutParams rowParams(Context context, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, top);
        params.bottomMargin = dp(context, bottom);
        return params;
    }

    private static int opaque(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static float sp(Context context, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                context.getResources().getDisplayMetrics());
    }

    private enum TextRole {
        TIME(0.88f, 0.90f),
        TITLE(1f, 1f),
        LOCATION(0.82f, 0.80f);

        final float sizeScale;
        final float alphaScale;

        TextRole(float sizeScale, float alphaScale) {
            this.sizeScale = sizeScale;
            this.alphaScale = alphaScale;
        }
    }

    private static final class NativeTextStyle {
        final Typeface typeface;
        final float baseTextSizePx;
        final int textColor;
        final float letterSpacing;
        final float textScaleX;
        final float shadowRadius;
        final float shadowDx;
        final float shadowDy;
        final int shadowColor;
        final boolean includeFontPadding;
        final boolean elegantTextHeight;
        final String fontFeatureSettings;
        final String fontVariationSettings;

        NativeTextStyle(Typeface typeface, float baseTextSizePx, int textColor,
                        float letterSpacing, float textScaleX, float shadowRadius,
                        float shadowDx, float shadowDy, int shadowColor,
                        boolean includeFontPadding, boolean elegantTextHeight,
                        String fontFeatureSettings, String fontVariationSettings) {
            this.typeface = typeface;
            this.baseTextSizePx = baseTextSizePx;
            this.textColor = textColor;
            this.letterSpacing = letterSpacing;
            this.textScaleX = textScaleX;
            this.shadowRadius = shadowRadius;
            this.shadowDx = shadowDx;
            this.shadowDy = shadowDy;
            this.shadowColor = shadowColor;
            this.includeFontPadding = includeFontPadding;
            this.elegantTextHeight = elegantTextHeight;
            this.fontFeatureSettings = fontFeatureSettings;
            this.fontVariationSettings = fontVariationSettings;
        }

        static NativeTextStyle from(TextView source, Context context) {
            if (source == null) {
                return fallback(context);
            }
            float minSize = sp(context, 13f);
            float maxSize = sp(context, 20f);
            float sourceSize = source.getTextSize();
            float baseSize = Math.max(minSize, Math.min(maxSize, sourceSize));
            int color = source.getCurrentTextColor();
            if (Color.alpha(color) == 0) {
                color = Color.WHITE;
            }
            Typeface sourceTypeface = source.getTypeface();
            return new NativeTextStyle(
                    sourceTypeface == null ? Typeface.DEFAULT : sourceTypeface,
                    baseSize,
                    color,
                    source.getLetterSpacing(),
                    source.getTextScaleX(),
                    source.getShadowRadius(),
                    source.getShadowDx(),
                    source.getShadowDy(),
                    source.getShadowColor(),
                    source.getIncludeFontPadding(),
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                            && source.isElegantTextHeight(),
                    source.getFontFeatureSettings(),
                    source.getFontVariationSettings());
        }

        static NativeTextStyle fallback(Context context) {
            return new NativeTextStyle(
                    Typeface.DEFAULT,
                    sp(context, 16f),
                    Color.WHITE,
                    0f,
                    1f,
                    dp(context, 1),
                    0f,
                    dp(context, 1),
                    Color.argb(150, 0, 0, 0),
                    false,
                    false,
                    null,
                    null);
        }

        void apply(TextView view, TextRole role) {
            view.setTextColor(multiplyAlpha(textColor, role.alphaScale));
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx * role.sizeScale);
            view.setTypeface(typeface);
            view.setLetterSpacing(letterSpacing);
            view.setTextScaleX(textScaleX);
            view.setIncludeFontPadding(includeFontPadding);
            view.setElegantTextHeight(elegantTextHeight);
            view.setFontFeatureSettings(fontFeatureSettings);
            view.setFontVariationSettings(fontVariationSettings);
            view.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);
        }

        int fingerprint() {
            int result = System.identityHashCode(typeface);
            result = 31 * result + Float.floatToIntBits(baseTextSizePx);
            result = 31 * result + textColor;
            result = 31 * result + Float.floatToIntBits(letterSpacing);
            result = 31 * result + Float.floatToIntBits(textScaleX);
            result = 31 * result + Float.floatToIntBits(shadowRadius);
            result = 31 * result + Float.floatToIntBits(shadowDx);
            result = 31 * result + Float.floatToIntBits(shadowDy);
            result = 31 * result + shadowColor;
            result = 31 * result + (includeFontPadding ? 1 : 0);
            result = 31 * result + (elegantTextHeight ? 1 : 0);
            result = 31 * result + (fontFeatureSettings == null
                    ? 0 : fontFeatureSettings.hashCode());
            result = 31 * result + (fontVariationSettings == null
                    ? 0 : fontVariationSettings.hashCode());
            return result;
        }

        private static int multiplyAlpha(int color, float scale) {
            int alpha = Math.max(0, Math.min(255, Math.round(Color.alpha(color) * scale)));
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    private static final class NativeBinding {
        final FrameLayout injectionHost;
        final FrameLayout clockHost;
        final View clock;
        final View clockLayout;

        NativeBinding(FrameLayout injectionHost, FrameLayout clockHost,
                      View clock, View clockLayout) {
            this.injectionHost = injectionHost;
            this.clockHost = clockHost;
            this.clock = clock;
            this.clockLayout = clockLayout;
        }
    }

}
