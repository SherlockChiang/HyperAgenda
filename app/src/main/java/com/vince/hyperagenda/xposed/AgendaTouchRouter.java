package com.vince.hyperagenda.xposed;

import android.graphics.Matrix;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class AgendaTouchRouter extends XC_MethodHook {
    private WeakReference<ViewGroup> rootRef = new WeakReference<>(null);
    private WeakReference<View> targetRef = new WeakReference<>(null);
    private MotionEvent downEvent;
    private int touchSlop;
    private boolean replaying;

    static void install(ClassLoader loader) {
        Class<?> windowClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.shade.NotificationShadeWindowView", loader);
        if (windowClass == null) {
            return;
        }
        try {
            // Hook this SystemUI override, never the framework-wide ViewGroup method.
            XposedBridge.hookMethod(windowClass.getDeclaredMethod(
                    "dispatchTouchEvent", MotionEvent.class), new AgendaTouchRouter());
            XposedBridge.log("HyperAgenda: agenda row touch dispatch installed");
        } catch (Throwable error) {
            XposedBridge.log("HyperAgenda: cannot install row touch dispatch: " + error);
        }
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (replaying || !(param.thisObject instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) param.thisObject;
        MotionEvent event = (MotionEvent) param.args[0];
        int action = event.getActionMasked();
        try {
            if (downEvent != null && rootRef.get() != root) {
                clear();
            }
            if (action == MotionEvent.ACTION_DOWN) {
                cancelTarget(event);
                clear();
                View target = AgendaOverlay.findTouchTarget(root, event);
                if (target == null || event.getPointerCount() != 1) {
                    return;
                }
                rootRef = new WeakReference<>(root);
                targetRef = new WeakReference<>(target);
                downEvent = MotionEvent.obtain(event);
                touchSlop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
                if (!dispatch(target, root, event, action)) {
                    XposedBridge.log("HyperAgenda: agenda row rejected touch down");
                    clear();
                    return;
                }
                param.setResult(true);
                XposedBridge.log("HyperAgenda: agenda row touch down");
                return;
            }

            if (downEvent == null || rootRef.get() != root) {
                return;
            }
            View target = targetRef.get();
            if (action == MotionEvent.ACTION_CANCEL) {
                cancelTarget(event);
                clear();
                param.setResult(true);
                return;
            }

            float dx = event.getRawX() - downEvent.getRawX();
            float dy = event.getRawY() - downEvent.getRawY();
            boolean drag = dx * dx + dy * dy > (float) touchSlop * touchSlop;
            if (drag || event.getPointerCount() != 1
                    || !AgendaOverlay.isTouchTargetActive(root, target)) {
                cancelTarget(event);
                // Restore a complete DOWN -> MOVE/UP stream for native lockscreen gestures.
                MotionEvent originalDown = MotionEvent.obtain(downEvent);
                clear();
                replaying = true;
                try {
                    root.dispatchTouchEvent(originalDown);
                } finally {
                    replaying = false;
                    originalDown.recycle();
                }
                XposedBridge.log("HyperAgenda: agenda gesture returned to system");
                return;
            }

            dispatch(target, root, event, action);
            param.setResult(true);
            if (action == MotionEvent.ACTION_UP) {
                clear();
            }
        } catch (Throwable error) {
            clear();
            XposedBridge.log("HyperAgenda: row touch dispatch failed: " + error);
        }
    }

    private void cancelTarget(MotionEvent event) {
        View target = targetRef.get();
        ViewGroup root = rootRef.get();
        if (target != null && root != null && downEvent != null) {
            dispatch(target, root, event, MotionEvent.ACTION_CANCEL);
        }
    }

    private static boolean dispatch(View target, ViewGroup root, MotionEvent event, int action) {
        MotionEvent local = MotionEvent.obtain(event);
        try {
            local.setAction(action);
            if (Build.VERSION.SDK_INT >= 29) {
                Matrix transform = new Matrix();
                root.transformMatrixToGlobal(transform);
                target.transformMatrixToLocal(transform);
                local.transform(transform);
            } else {
                int[] rootLocation = new int[2];
                int[] targetLocation = new int[2];
                root.getLocationOnScreen(rootLocation);
                target.getLocationOnScreen(targetLocation);
                local.offsetLocation(rootLocation[0] - targetLocation[0],
                        rootLocation[1] - targetLocation[1]);
            }
            return target.dispatchTouchEvent(local);
        } finally {
            local.recycle();
        }
    }

    private void clear() {
        if (downEvent != null) {
            downEvent.recycle();
            downEvent = null;
        }
        targetRef.clear();
        rootRef.clear();
    }
}
