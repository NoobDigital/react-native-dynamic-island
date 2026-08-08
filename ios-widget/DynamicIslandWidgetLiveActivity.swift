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
// lifetime (brand, icons, logo).
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

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {

            // ── Top row: logo + title/subtitle ──────────────────────
            HStack(alignment: .center, spacing: 10) {

                logoView

                VStack(alignment: .leading, spacing: 2) {
                    Text(state.data["title"] ?? "")
                        .font(.system(size: 15, weight: .semibold))
                        // .foregroundColor(.primary) can be used to set the text color of the title. For example, you can use .foregroundColor(.primary) to match the system primary text color.
                         .foregroundColor(.white) 

                    Text(state.data["status"] ?? "")
                        .font(.system(size: 13))
                        // .foregroundColor(.secondary) can be used to set the text color of the status. For example, you can use .foregroundColor(.secondary) to match the system secondary text color.
                         .foregroundColor(Color.white.opacity(0.6)) 
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
                                // .fill(isDone ? Color.orange : Color(.systemGray5)) should be used to set the background color of the progress track. For example, you can use .fill(isDone ? Color.orange : Color(.systemGray5)) to match the system gray color for incomplete steps.
                                 .fill(isDone ? Color.orange : Color.white.opacity(0.15)) 
                                .frame(width: 26, height: 26)

                            Image(systemName: icon)
                                .font(.system(size: 12, weight: .semibold))
                                // .foregroundColor(isDone ? .white : .secondary) should be used to set the icon color of the progress track. For example, you can use .foregroundColor(isDone ? .white : .secondary) to match the system secondary text color for incomplete steps.
                                 .foregroundColor(isDone ? .white : Color.white.opacity(0.4))
                        }

                        if index != attributes.stepIcons.count - 1 {
                            Rectangle()
                                .fill(
                                    index < activeStepIndex
                                        ? Color.orange
                                        : Color(.systemGray5)
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
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(.orange)

                Spacer()

                if let eta = state.data["eta"] {
                    Text(eta)
                        .font(.system(size: 12, weight: .medium))
                        // .foregroundColor(.secondary) can be used to set the text color of the ETA. For example, you can use .foregroundColor(.secondary) to match the system secondary text color.
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
            // .activityBackgroundTint(Color(.systemBackground)) Can be used to set the background color of the live activity view. For example, you can use Color(.systemBackground) to match the system background color.
            .activityBackgroundTint(Color.black)  
            .activitySystemActionForegroundColor(.primary)
            .widgetURL(
                deepLinkURL(from: context.state)
            )

        } dynamicIsland: { context in

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
