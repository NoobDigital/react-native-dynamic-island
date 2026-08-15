import DynamicIslandNative from './NativeDynamicIsland';

/**
 * Content for a live-updating activity card.
 *
 * On iOS this drives your Widget Extension's Live Activity /
 * Dynamic Island UI. On Android it drives an ongoing notification
 * (Android has no Dynamic Island equivalent — this is the closest
 * real feature: a persistent, in-place-updating lock screen card).
 *
 * Recognized keys: title, status, eta, progress ("0"-"100" as a string),
 * deepLink (optional — a URL opened when the user taps the activity/notification).
 * Any other keys are passed through to native but only used on iOS,
 * where your widget UI decides what to read.
 */
export type DynamicIslandContentState = Record<string, string>;

/**
 * Fixed metadata for an activity, set once at startActivity() and unchanged
 * for its lifetime — as opposed to DynamicIslandContentState, which changes
 * on every update.
 *
 * Style fields below (titleColor, titleFontSize, progressColor,
 * progressFontSize, iconColor, iconSize) are all optional. Omit any of them
 * to keep the existing v1.0.1 default look — nothing changes unless you
 * explicitly set it.
 */
export interface DynamicIslandAttributes {
  /** Shown in the widget's brand/footer row. */
  brandName: string;
  /**
   * SF Symbol names, one per step, in stage order — drives the progress
   * track UI (iOS). Falls back to a default 4-step set on the native side if omitted.
   */
  stepIcons?: string[];
  /**
   * Name of an image in the Widget Extension's own Assets.xcassets.
   * Must be added there manually — a widget extension can't read the host
   * app's AppIcon at runtime. Falls back to a generic icon if omitted or
   * not found.
   */
  logoAssetName?: string;
  /**
   * Android only. Optional array of drawable resource names, one per step,
   * overriding the generic dot/checkmark track with custom icons.
   */
  androidStepIcons?: string[];

  // ── Style config (v1.0.2+) ────────────────────────────────────────────
  // All optional. Hex colors accept "#RRGGBB" (with or without the `#`),
  // or "#AARRGGBB" if you need alpha (e.g. statusColor's default is a
  // translucent white). Supported identically on iOS and Android.

  /** Color of the title text. Default: white (`#FFFFFF`). */
  titleColor?: string;
  /** Font size of the title text, in pt (iOS) / sp (Android). Default: 15. */
  titleFontSize?: number;
  /** Color of the status/subtitle text. Default: `#FFFFFF` at ~60% opacity. */
  statusColor?: string;
  /** Font size of the status/subtitle text, in pt (iOS) / sp (Android). Default: 13. */
  statusFontSize?: number;
  /** Color of the brand name text in the footer row. Default: orange (`#FF9500`). */
  brandColor?: string;
  /** Font size of the brand name text in the footer row, in pt (iOS) / sp (Android). Default: 12. */
  brandFontSize?: number;
  /**
   * Color of the "done" step circles and connector lines in the progress
   * track. Default: orange (`#FF9500`).
   */
  progressColor?: string;
  /**
   * Font size of the ETA label in the footer row (the text tied to the
   * progress/footer row — there's no separate progress-percentage text in
   * the current UI). In pt (iOS) / sp (Android). Default: 12.
   */
  progressFontSize?: number;
  /**
   * Color of the icon/checkmark drawn inside a completed ("done") step
   * circle. Default: white (`#FFFFFF`).
   */
  iconColor?: string;
  /**
   * Diameter of each step circle in the progress track, in pt (iOS) / dp
   * (Android). Default: 26.
   */
  iconSize?: number;
}

/**
 * Whether the platform will currently show live updates:
 * - iOS: OS version + user's Live Activities setting.
 * - Android: whether notifications are enabled for the app.
 */
export async function areActivitiesEnabled(): Promise<boolean> {
  return DynamicIslandNative.areActivitiesEnabled();
}

/**
 * Starts a live-updating activity (Live Activity on iOS, ongoing
 * notification on Android). Resolves with an id for the activity/notification.
 * Only one runs at a time — calling this again replaces the previous one.
 *
 * `attributes` is fixed metadata (brand name, step icons, logo, style) —
 * set once here and not resendable via updateActivity.
 */
export async function startActivity(
  content: DynamicIslandContentState,
  attributes?: DynamicIslandAttributes
): Promise<string> {
  return DynamicIslandNative.startActivity(content, attributes ?? {});
}

/**
 * Updates the currently running activity with new content.
 * Rejects with NO_ACTIVITY if nothing is running — call startActivity first.
 */
export async function updateActivity(
  content: DynamicIslandContentState
): Promise<boolean> {
  return DynamicIslandNative.updateActivity(content);
}

/**
 * Ends the currently running activity, if any. Safe to call with nothing running.
 */
export async function endActivity(): Promise<boolean> {
  return DynamicIslandNative.endActivity();
}

export default {
  areActivitiesEnabled,
  startActivity,
  updateActivity,
  endActivity,
};
