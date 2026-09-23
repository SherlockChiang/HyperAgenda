package com.vince.hyperagenda.xposed;

import android.view.View;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HyperAgendaHook implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String LISTENER_MARKER = "hyperagenda_listener_attached";

    private static final String[] ANCHOR_CLASSES = {
            "com.android.keyguard.KeyguardClockContainer",
            "com.android.keyguard.clock.KeyguardClockContainer"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!SYSTEM_UI.equals(loadPackageParam.packageName)) {
            return;
        }

        int hookedClasses = 0;
        for (String className : ANCHOR_CLASSES) {
            Class<?> candidate = XposedHelpers.findClassIfExists(className, loadPackageParam.classLoader);
            if (candidate == null || !View.class.isAssignableFrom(candidate)) {
                continue;
            }
            try {
                Set<?> hooks = XposedBridge.hookAllConstructors(candidate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View anchor = (View) param.thisObject;
                            if (XposedHelpers.getAdditionalInstanceField(anchor, LISTENER_MARKER) != null) {
                                return;
                            }
                            XposedHelpers.setAdditionalInstanceField(anchor, LISTENER_MARKER, Boolean.TRUE);
                            anchor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                                @Override
                                public void onViewAttachedToWindow(View v) {
                                    AgendaOverlay.attach(v);
                                }

                                @Override
                                public void onViewDetachedFromWindow(View v) {
                                    AgendaOverlay.detach(v);
                                }
                            });
                            if (anchor.isAttachedToWindow()) {
                                AgendaOverlay.attach(anchor);
                            }
                        } catch (Throwable error) {
                            XposedBridge.log("HyperAgenda: anchor callback failed: " + error);
                        }
                    }
                });
                if (!hooks.isEmpty()) {
                    hookedClasses++;
                }
            } catch (Throwable error) {
                XposedBridge.log("HyperAgenda: cannot hook " + className + ": " + error);
            }
        }
        AgendaTouchRouter.install(loadPackageParam.classLoader);
        XposedBridge.log("HyperAgenda: SystemUI loaded, anchor classes=" + hookedClasses);
    }
}
