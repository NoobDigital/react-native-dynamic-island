import Foundation
import React
import ActivityKit

// MARK: - Shared Activity Attributes
//
// This definition must match the DynamicIslandAttributes definition
// used by the Widget Extension (DynamicIslandWidgetLiveActivity.swift).

@available(iOS 16.2, *)
public struct DynamicIslandAttributes: ActivityAttributes {

    public struct ContentState: Codable, Hashable {
        public var data: [String: String]

        public init(data: [String: String]) {
            self.data = data
        }
    }

    public var name: String
    public var brandName: String
    public var stepIcons: [String]
    public var logoAssetName: String?

    // ── Style config (v1.0.2+) ─────────────────────────────────────────
    // All optional; a nil value means "use the widget's built-in default".
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

    public init(
        name: String,
        brandName: String,
        stepIcons: [String],
        logoAssetName: String?,
        titleColor: String? = nil,
        titleFontSize: Double? = nil,
        statusColor: String? = nil,
        statusFontSize: Double? = nil,
        brandColor: String? = nil,
        brandFontSize: Double? = nil,
        progressColor: String? = nil,
        progressFontSize: Double? = nil,
        iconColor: String? = nil,
        iconSize: Double? = nil
    ) {
        self.name = name
        self.brandName = brandName
        self.stepIcons = stepIcons
        self.logoAssetName = logoAssetName
        self.titleColor = titleColor
        self.titleFontSize = titleFontSize
        self.statusColor = statusColor
        self.statusFontSize = statusFontSize
        self.brandColor = brandColor
        self.brandFontSize = brandFontSize
        self.progressColor = progressColor
        self.progressFontSize = progressFontSize
        self.iconColor = iconColor
        self.iconSize = iconSize
    }
}

// MARK: - React Native Module

@objc(DynamicIsland)
public class DynamicIsland: NSObject {

    @available(iOS 16.2, *)
    private static var currentActivity:
        Activity<DynamicIslandAttributes>?

    @objc
    public static func requiresMainQueueSetup() -> Bool {
        return false
    }

    // MARK: - Availability

    @objc
    public func areActivitiesEnabled(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard #available(iOS 16.2, *) else {
            resolve(false)
            return
        }

        resolve(
            ActivityAuthorizationInfo().areActivitiesEnabled
        )
    }

    // MARK: - Start

    @objc
    public func startActivity(
        _ content: NSDictionary,
        attributes attrs: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard #available(iOS 16.2, *) else {
            reject(
                "UNSUPPORTED_OS",
                "Live Activities require iOS 16.2+",
                nil
            )
            return
        }

        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            reject(
                "ACTIVITIES_DISABLED",
                "Live Activities are disabled for this app or device",
                nil
            )
            return
        }

        Task {
            do {
                // Keep the same behaviour as Android:
                // only one Live Activity at a time.
                if let existing = Self.getCurrentActivity() {
                    await existing.end(
                        nil,
                        dismissalPolicy: .immediate
                    )
                }

                let data = Self.stringDictionary(
                    from: content
                )

                let brandName = attrs["brandName"] as? String ?? "App"

                let stepIcons = (attrs["stepIcons"] as? [String])
                    ?? ["checkmark", "bag.fill", "bicycle", "mappin.and.ellipse"]

                let logoAssetName = attrs["logoAssetName"] as? String

                // Style config — all optional, nil falls through to the
                // widget's own defaults so existing integrations are unaffected.
                let titleColor = attrs["titleColor"] as? String
                let titleFontSize = Self.doubleValue(attrs["titleFontSize"])
                let statusColor = attrs["statusColor"] as? String
                let statusFontSize = Self.doubleValue(attrs["statusFontSize"])
                let brandColor = attrs["brandColor"] as? String
                let brandFontSize = Self.doubleValue(attrs["brandFontSize"])
                let progressColor = attrs["progressColor"] as? String
                let progressFontSize = Self.doubleValue(attrs["progressFontSize"])
                let iconColor = attrs["iconColor"] as? String
                let iconSize = Self.doubleValue(attrs["iconSize"])

                let activityAttributes = DynamicIslandAttributes(
                    name: "DynamicIslandActivity",
                    brandName: brandName,
                    stepIcons: stepIcons,
                    logoAssetName: logoAssetName,
                    titleColor: titleColor,
                    titleFontSize: titleFontSize,
                    statusColor: statusColor,
                    statusFontSize: statusFontSize,
                    brandColor: brandColor,
                    brandFontSize: brandFontSize,
                    progressColor: progressColor,
                    progressFontSize: progressFontSize,
                    iconColor: iconColor,
                    iconSize: iconSize
                )

                let state = DynamicIslandAttributes.ContentState(
                    data: data
                )

                let activity = try Activity.request(
                    attributes: activityAttributes,
                    content: ActivityContent(
                        state: state,
                        staleDate: nil
                    )
                )

                Self.currentActivity = activity

                resolve(activity.id)

            } catch {
                reject(
                    "START_FAILED",
                    error.localizedDescription,
                    error
                )
            }
        }
    }

    // MARK: - Update

    @objc
    public func updateActivity(
        _ content: NSDictionary,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard #available(iOS 16.2, *) else {
            reject(
                "UNSUPPORTED_OS",
                "Live Activities require iOS 16.2+",
                nil
            )
            return
        }

        guard let activity = Self.getCurrentActivity() else {
            reject(
                "NO_ACTIVITY",
                "No Live Activity is currently running",
                nil
            )
            return
        }

        let data = Self.stringDictionary(
            from: content
        )

        let state = DynamicIslandAttributes.ContentState(
            data: data
        )

        let activityContent = ActivityContent(
            state: state,
            staleDate: nil
        )

        Task {
            await activity.update(activityContent)

            Self.currentActivity = activity

            resolve(true)
        }
    }

    // MARK: - End

    @objc
    public func endActivity(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        guard #available(iOS 16.2, *) else {
            resolve(true)
            return
        }

        guard let activity = Self.getCurrentActivity() else {
            resolve(true)
            return
        }

        Task {
            await activity.end(
                nil,
                dismissalPolicy: .immediate
            )

            Self.currentActivity = nil

            resolve(true)
        }
    }

    // MARK: - Activity Recovery

    @available(iOS 16.2, *)
    private static func getCurrentActivity()
        -> Activity<DynamicIslandAttributes>? {

        // First use cached activity.
        if let current = currentActivity {
            return current
        }

        // React Native can be restarted/recreated while the
        // Live Activity is still running.
        //
        // ActivityKit owns the Live Activity, not the RN process.
        let activities =
            Activity<DynamicIslandAttributes>.activities

        guard let activity = activities.last else {
            return nil
        }

        currentActivity = activity

        return activity
    }

    // MARK: - Dictionary Conversion

    private static func stringDictionary(
        from dict: NSDictionary
    ) -> [String: String] {

        var result: [String: String] = [:]

        for (key, value) in dict {

            guard let keyString = key as? String else {
                continue
            }

            if value is NSNull {
                continue
            }

            if let stringValue = value as? String {
                result[keyString] = stringValue

            } else if let numberValue = value as? NSNumber {
                result[keyString] = numberValue.stringValue

            } else {
                result[keyString] = String(
                    describing: value
                )
            }
        }

        return result
    }

    // MARK: - Style value helpers

    /// Bridge sends numeric attrs (titleFontSize, iconSize, etc.) as NSNumber.
    /// Returns nil (not 0) when the key is absent, so the widget can fall
    /// back to its own default rather than rendering a zero-size element.
    private static func doubleValue(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber else {
            return nil
        }
        return number.doubleValue
    }
}
