# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [1.0.2] - 2026-08-16

Full JS‑driven style configuration for both iOS & Android  

### Added

- New optional fields in DynamicIslandAttributes allow complete control over the Live Activity / notification UI without touching native code : titleColor,titleFontSize,statusColor,statusFontSize,brandColor,brandFontSize,progressColor,progressFontSize,iconColor,iconSize,

### Changed

- Default styling now matches the v1.0.1 look unless any v1.0.2 style fields are provided.


## [1.0.0] - 2026-08-09

Initial stable release.

### Added

- **Unified cross-platform API** — `areActivitiesEnabled()`,
  `startActivity(content, attributes?)`, `updateActivity(content)`,
  `endActivity()`. Identical call shape on iOS and Android.
- **iOS: real Live Activity + Dynamic Island support** via ActivityKit
  (iOS 16.2+), with lock screen, compact, and expanded Dynamic Island
  presentations.
- **Android: ongoing, in-place-updating lock-screen notification** as the
  platform-appropriate equivalent — persistent, single-instance, not
  swipeable while active.
- **Attributes vs. content state split** — `brandName`, `stepIcons`,
  `androidStepIcons`, and `logoAssetName` are set once at `startActivity`
  and held for the activity's lifetime; `title`, `status`, `eta`, and
  `progress` update freely via `updateActivity` without resending fixed
  metadata.
- **Progress step track UI** on both platforms — configurable step count
  and icons via `stepIcons` (SF Symbol names, iOS) and `androidStepIcons`
  (drawable resource names, Android), with a generic dot/checkmark fallback
  when custom icons aren't supplied.
- **Custom brand logo support** — `logoAssetName`, resolved from the
  Widget Extension's own asset catalog on iOS and the host app's
  `res/drawable` on Android. Falls back to a neutral drawn placeholder box
  when omitted or not found — never falls back to the app's launcher icon.
- **Deep linking** — optional `deepLink` field opens the app at a specific
  screen on tap, via `widgetURL` (iOS) and a `PendingIntent` with
  `ACTION_VIEW` (Android). Falls back to a plain app-foreground intent on
  Android when no `deepLink` is provided.
- **Android runtime resources ship inside the library** — layout,
  drawables, and notification channel setup require zero manual
  integration steps. All resource names are prefixed (`dynamicisland_`)
  and enforced via `resourcePrefix` in Gradle to avoid collisions with
  host app resources.
- **Activity recovery** on iOS — reattaches to an already-running Live
  Activity after a JS/React Native reload, since ActivityKit owns the
  activity independently of the RN process.
- **Classic `NativeModules` bridge** (no TurboModule/JSI) for maximum
  compatibility across React Native versions.
- Full TypeScript definitions for `DynamicIslandContentState` and
  `DynamicIslandAttributes`.
- Example app demonstrating a simulated order-tracking flow, notification
  permission handling, and deep link navigation on both platforms.

### Platform notes

- iOS requires a one-time Widget Extension target setup in Xcode — this is
  an Apple platform requirement and cannot be automated by an npm install.
  See the README for the full setup guide.
- Android has no equivalent of the Dynamic Island pill UI element; this is
  iPhone 14 Pro+ hardware/OS specific. The ongoing notification is the
  closest real-world equivalent used by production delivery/rideshare apps
  for the same use case.

[1.0.0]: https://github.com/NoobDigital/react-native-dynamic-island/releases/tag/v1.0.0