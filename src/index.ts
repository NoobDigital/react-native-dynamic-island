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
 * iOS only. Android ignores this (it derives its icon from the host app
 * automatically and has no per-step icon track).
 */
export interface DynamicIslandAttributes {
  /** Shown in the widget's brand/footer row. */
  brandName: string;
  /**
   * SF Symbol names, one per step, in stage order — drives the progress
   * track UI. Falls back to a default 4-step set on the native side if omitted.
   */
  stepIcons?: string[];
  /**
   * Name of an image in the Widget Extension's own Assets.xcassets.
   * Must be added there manually — a widget extension can't read the host
   * app's AppIcon at runtime. Falls back to a generic icon if omitted or
   * not found.
   */
  logoAssetName?: string;
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
 * `attributes` is iOS-only fixed metadata (brand name, step icons, logo) —
 * set once here and not resendable via updateActivity. Ignored on Android.
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