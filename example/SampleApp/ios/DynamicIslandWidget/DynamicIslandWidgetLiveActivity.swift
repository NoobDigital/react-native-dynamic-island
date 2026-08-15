// DynamicIslandWidgetLiveActivity.swift
//
// This file must live in your Widget Extension target (not the main app
// target). The `DynamicIslandAttributes` struct here must stay in sync
// field-for-field with the copy in DynamicIsland.swift (the native module) —
// each target compiles its own copy since they can't share Swift files
// directly across a React Native / ActivityKit bridge setup.

import ActivityKit
import WidgetKit
import SwiftUI

// MARK: - Shared Activity Attributes
//
// `Attributes` = set ONCE at startActivity(), fixed for the activity's
// lifetime (brand, icons, logo, style).
// `ContentState` = sent on every startActivity()/updateActivity() call
// (title, status, eta, progress, deepLink).

@available(iOS 16.2, *)
struct DynamicIslandAttributes: ActivityAttributes {

    public struct ContentState: Codable, Hashable {
        public var data: [String: String]
    }

    public var name: String
    public var brandName: String
    public var stepIcons: [String]       // SF Symbol names, in stage order
    public var logoAssetName: String?    // must exist in this extension's Assets.xcassets

    // ── Style config (v1.0.2+) ─────────────────────────────────────────
    // All optional. nil == "use the default below". Hex strings accept
    // "#RRGGBB" or "RRGGBB".
    public var titleColor: String?
    public var titleFontSize: Double?
    public var statusColor: String?
    public var statusFontSize: Double?
    public var brandColor: String?
    public var brandFontSize: Double?
    public var progressColor: String?
    public var progressFontSize: Double?
    public var iconColor: String?
    public var iconSize: Double?
}

// MARK: - Style defaults
//
// These match the exact visual look shipped in v1.0.1, so an integration
// that doesn't set any style attrs renders pixel-identical to before.

private enum StyleDefaults {
    static let titleColor = Color.white
    static let titleFontSize: CGFloat = 15
    static let statusColor = Color.white.opacity(0.6)
    static let statusFontSize: CGFloat = 13
    static let brandColor = Color.orange
    static let brandFontSize: CGFloat = 12
    static let progressColor = Color.orange // #FF9500
    static let progressFontSize: CGFloat = 12 // applied to the ETA label
    static let pendingTrackColor = Color(.systemGray5)
    static let iconColor = Color.white
    static let pendingIconColor = Color.white.opacity(0.4)
    static let iconSize: CGFloat = 26
}

// MARK: - Hex color helper

private extension Color {
    /// Parses a "#RRGGBB" / "RRGGBB" hex string. Falls back to `fallback`
    /// if the string is nil, malformed, or not 6 hex digits.
    init(hexString: String?, fallback: Color) {
        guard let hexString,
              let parsed = Color.parseHex(hexString) else {
            self = fallback
            return
        }
        self = parsed
    }

    private static func parseHex(_ hex: String) -> Color? {
        var cleaned = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("#") {
            cleaned.removeFirst()
        }

        var rgbValue: UInt64 = 0
        guard Scanner(string: cleaned).scanHexInt64(&rgbValue) else {
            return nil
        }

        switch cleaned.count {
        case 6:
            // RRGGBB
            let r = Double((rgbValue & 0xFF0000) >> 16) / 255
            let g = Double((rgbValue & 0x00FF00) >> 8) / 255
            let b = Double(rgbValue & 0x0000FF) / 255
            return Color(red: r, green: g, blue: b)

        case 8:
            // AARRGGBB — matches Android's Color.parseColor 8-digit format,
            // so the same hex string (e.g. statusColor: "#99FFFFFF") works
            // identically on both platforms.
            let a = Double((rgbValue & 0xFF000000) >> 24) / 255
            let r = Double((rgbValue & 0x00FF0000) >> 16) / 255
            let g = Double((rgbValue & 0x0000FF00) >> 8) / 255
            let b = Double(rgbValue & 0x000000FF) / 255
            return Color(red: r, green: g, blue: b, opacity: a)

        default:
            return nil
        }
    }
}

// MARK: - Deep link helper

@available(iOS 16.2, *)
private func deepLinkURL(
    from state: DynamicIslandAttributes.ContentState
) -> URL? {

    guard let raw = state.data["deepLink"],
          !raw.isEmpty else {
        return nil
    }

    return URL(string: raw)
}

// MARK: - Progress helpers

@available(iOS 16.2, *)
private func currentStepIndex(
    progress: Int,
    stepCount: Int
) -> Int {

    guard stepCount > 0 else { return 0 }

    let stepSize = max(100 / stepCount, 1)

    return min(progress / stepSize, stepCount - 1)
}

// MARK: - Lock Screen / Banner presentation

@available(iOS 16.2, *)
struct LiveActivityTrackView: View {

    let attributes: DynamicIslandAttributes
    let state: DynamicIslandAttributes.ContentState

    private var progress: Int {
        Int(state.data["progress"] ?? "0") ?? 0
    }

    private var activeStepIndex: Int {
        currentStepIndex(
            progress: progress,
            stepCount: attributes.stepIcons.count
        )
    }

    // ── Resolved style values (attrs override, else default) ───────────
    private var resolvedTitleColor: Color {
        Color(hexString: attributes.titleColor, fallback: StyleDefaults.titleColor)
    }
    private var resolvedTitleFontSize: CGFloat {
        attributes.titleFontSize.map { CGFloat($0) } ?? StyleDefaults.titleFontSize
    }
    private var resolvedStatusColor: Color {
        Color(hexString: attributes.statusColor, fallback: StyleDefaults.statusColor)
    }
    private var resolvedStatusFontSize: CGFloat {
        attributes.statusFontSize.map { CGFloat($0) } ?? StyleDefaults.statusFontSize
    }
    private var resolvedBrandColor: Color {
        Color(hexString: attributes.brandColor, fallback: StyleDefaults.brandColor)
    }
    private var resolvedBrandFontSize: CGFloat {
        attributes.brandFontSize.map { CGFloat($0) } ?? StyleDefaults.brandFontSize
    }
    private var resolvedProgressColor: Color {
        Color(hexString: attributes.progressColor, fallback: StyleDefaults.progressColor)
    }
    private var resolvedProgressFontSize: CGFloat {
        attributes.progressFontSize.map { CGFloat($0) } ?? StyleDefaults.progressFontSize
    }
    private var resolvedIconColor: Color {
        Color(hexString: attributes.iconColor, fallback: StyleDefaults.iconColor)
    }
    private var resolvedIconSize: CGFloat {
        attributes.iconSize.map { CGFloat($0) } ?? StyleDefaults.iconSize
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {

            // ── Top row: logo + title/subtitle ──────────────────────
            HStack(alignment: .center, spacing: 10) {

                logoView

                VStack(alignment: .leading, spacing: 2) {
                    Text(state.data["title"] ?? "")
                        .font(.system(size: resolvedTitleFontSize, weight: .semibold))
                        .foregroundColor(resolvedTitleColor)

                    Text(state.data["status"] ?? "")
                        .font(.system(size: resolvedStatusFontSize))
                        .foregroundColor(resolvedStatusColor)
                }

                Spacer()
            }

            // ── Progress track ───────────────────────────────────────
            if !attributes.stepIcons.isEmpty {
                HStack(spacing: 0) {
                    ForEach(
                        Array(attributes.stepIcons.enumerated()),
                        id: \.offset
                    ) { index, icon in

                        let isDone = index <= activeStepIndex

                        ZStack {
                            Circle()
                                .fill(isDone ? resolvedProgressColor : StyleDefaults.pendingTrackColor)
                                .frame(width: resolvedIconSize, height: resolvedIconSize)

                            Image(systemName: icon)
                                .font(.system(size: resolvedIconSize * 0.46, weight: .semibold))
                                .foregroundColor(isDone ? resolvedIconColor : StyleDefaults.pendingIconColor)
                        }

                        if index != attributes.stepIcons.count - 1 {
                            Rectangle()
                                .fill(
                                    index < activeStepIndex
                                        ? resolvedProgressColor
                                        : StyleDefaults.pendingTrackColor
                                )
                                .frame(height: 3)
                        }
                    }
                }
                .padding(.top, 2)
            }

            // ── Brand + ETA footer ───────────────────────────────────
            HStack {
                Text(attributes.brandName)
                    .font(.system(size: resolvedBrandFontSize, weight: .bold))
                    .foregroundColor(resolvedBrandColor)

                Spacer()

                if let eta = state.data["eta"] {
                    Text(eta)
                        .font(.system(size: resolvedProgressFontSize, weight: .medium))
                        .foregroundColor(Color.white.opacity(0.6))
                }
            }
        }
        .padding(16)
    }

    @ViewBuilder
    private var logoView: some View {
        if let name = attributes.logoAssetName,
           UIImage(named: name) != nil {

            Image(name)
                .resizable()
                .frame(width: 30, height: 30)
                .clipShape(RoundedRectangle(cornerRadius: 10))

        } else {

            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.orange.opacity(0.15))
                    .frame(width: 30, height: 30)

                Image(systemName: "shippingbox.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.orange)
            }
        }
    }
}

// MARK: - Widget

@available(iOS 16.2, *)
struct DynamicIslandWidgetLiveActivity: Widget {

    var body: some WidgetConfiguration {

        ActivityConfiguration(
            for: DynamicIslandAttributes.self
        ) { context in

            LiveActivityTrackView(
                attributes: context.attributes,
                state: context.state
            )
            .activityBackgroundTint(Color.black)
            .activitySystemActionForegroundColor(.primary)
            .widgetURL(
                deepLinkURL(from: context.state)
            )

        } dynamicIsland: { context in

            // Note: the compact/expanded Dynamic Island pill itself keeps
            // its default system styling — Apple's Dynamic Island regions
            // are tightly space-constrained and don't take custom
            // font sizes well. Only the lock-screen / banner card above
            // (LiveActivityTrackView) is styled from titleColor/progressColor/
            // iconColor/etc.

            DynamicIsland {

                DynamicIslandExpandedRegion(.leading) {
                    Text(context.attributes.brandName)
                        .font(.caption)
                        .foregroundColor(.orange)
                }

                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.data["eta"] ?? "")
                        .font(.caption)
                }

                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.data["title"] ?? "")
                        .font(.caption)
                }

                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.data["status"] ?? "")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

            } compactLeading: {

                Image(systemName: currentCompactIcon(context: context))
                    .foregroundColor(.orange)

            } compactTrailing: {

                Text(context.state.data["eta"] ?? "")
                    .font(.caption2)

            } minimal: {

                Image(systemName: currentCompactIcon(context: context))
                    .foregroundColor(.orange)
            }
            .widgetURL(
                deepLinkURL(from: context.state)
            )
        }
    }

    private func currentCompactIcon(
        context: ActivityViewContext<DynamicIslandAttributes>
    ) -> String {

        let progress = Int(context.state.data["progress"] ?? "0") ?? 0
        let icons = context.attributes.stepIcons

        guard !icons.isEmpty else { return "circle.fill" }

        let index = currentStepIndex(
            progress: progress,
            stepCount: icons.count
        )

        return icons[index]
    }
}
