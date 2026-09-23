# HyperAgenda 0.4.12 Verification

Date: 2026-09-23

Target: Xiaomi 24129PN74C, SystemUI 17.03.260226.r (202602260).
Module: com.vince.hyperagenda, versionCode 16.

## Confirmed

- UI hierarchy: agenda -> keyguard_info_layer -> keyguard_panel_view.
  No agenda view is added to the window root or a WindowManager overlay.
- Clock geometry: foreground clock_animation_container bottom 1104 px,
  agenda outer top 1183 px, gap 79 px (configured 24 dp, rounded).
  Earlier samples also showed the top updating as the clock moved.
- Text style is inherited from text_area/ClassicTextAreaView before display.
- Tap: the event click callback ran, ActivityStarter was resolved via
  InterfacesImplManager, and startPendingIntentDismissingKeyguard was called.
  Calendar EventInfoActivity used the same event URI as the clicked event.
- Keyguard visibility logs included bouncer and not-keyguard. Calendar UI
  dumps after unlocking contained no agenda nodes.
- Swiping from inside a row returned the gesture to the system without a
  second event click. The normal unlock transition continued.
- Notification shade expansion/collapse: the user manually confirmed that
  the agenda and clock appear/disappear together.
- Settings now persist a separate "点击日程打开日历" switch. When disabled,
  rows remain visible for reading but are non-clickable and do not enter the
  agenda touch router.

No screenshots were taken. UI dumps, calendar contents, and device APKs
are excluded from the source archive. Final source removes temporary raw
touch-coordinate and hit-test diagnostics; display and gesture logic are
unchanged from the tested build.

## Limitations

- This round did not repeat charging animation, multiple pointers, every
  authentication mode, or other lockscreen themes/firmware.
- ADB motion injection intermittently failed in the system input service.
  Missing click logs from those attempts are not evidence of a module bug.
- There is no automated unit/instrumentation test suite in this project.
- The new click switch was statically verified in the APK/provider path but
  has not yet been toggled on-device in this round.
- The Java compile task emits updated class files but fails while closing
  R.jar with a Windows AccessDeniedException in this workspace. Packaging
  and lint use those verified fresh classes with the Java compile task
  excluded. A clean, uninterrupted Gradle build is still a tooling gap.

## Touch Ownership

Only the SystemUI NotificationShadeWindowView override is hooked for touch
routing. A single-pointer DOWN must hit an enabled, currently visible
agenda row. Motion events are mapped to that row's local coordinate system.
Drag, multiple pointers, or loss of eligibility cancels the row sequence
and returns a DOWN plus the current event to native dispatch. This hook
does not change view ownership, shade visibility rules, or authentication.
