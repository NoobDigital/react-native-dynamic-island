package com.dynamicisland

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap

/**
 * Android has no equivalent of the iOS "Dynamic Island" UI element — that's
 * a physical cutout + OS animation specific to iPhone 14 Pro+ hardware.
 *
 * The closest functional match (what real delivery/rideshare apps actually
 * ship on Android) is an ONGOING, live-updating notification: it survives
 * on the lock screen, updates in place without re-alerting, and can't be
 * swiped away while active — same job as a Live Activity, different visuals.
 * This module implements that with a fully custom RemoteViews layout that
 * mirrors the iOS Live Activity design (logo, title/status, progress track,
 * brand + ETA footer, black card background).
 */
class DynamicIslandModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val CHANNEL_ID = "dynamic_island_live_updates"
        // Single fixed id: mirrors the iOS module, which also tracks only
        // one running activity at a time.
        private const val NOTIFICATION_ID = 20260807

        // Persists currentAttributes across process death — Android has no
        // ActivityKit-style OS-level Attributes storage, so without this,
        // backgrounding the app long enough for the process to be killed
        // (a very normal thing to do while testing a lock-screen card)
        // loses brandName/style config on the next updateActivity() call,
        // even though the notification itself and the JS-side stage index
        // both survive fine.
        private const val PREFS_NAME = "dynamic_island_attrs"
        private const val KEY_BRAND_NAME = "brandName"
        private const val KEY_STEP_COUNT = "stepCount"
        private const val KEY_ANDROID_STEP_ICONS = "androidStepIcons" // joined with KEY_LIST_DELIMITER
        private const val KEY_LOGO_RESOURCE_NAME = "logoResourceName"
        private const val KEY_TITLE_COLOR = "titleColor"
        private const val KEY_TITLE_FONT_SIZE = "titleFontSize"
        private const val KEY_STATUS_COLOR = "statusColor"
        private const val KEY_STATUS_FONT_SIZE = "statusFontSize"
        private const val KEY_BRAND_COLOR = "brandColor"
        private const val KEY_BRAND_FONT_SIZE = "brandFontSize"
        private const val KEY_PROGRESS_COLOR = "progressColor"
        private const val KEY_PROGRESS_FONT_SIZE = "progressFontSize"
        private const val KEY_ICON_COLOR = "iconColor"
        private const val KEY_ICON_SIZE = "iconSize"
        private const val KEY_HAS_SAVED_ATTRS = "hasSavedAttrs"
        private const val LIST_DELIMITER = "\u0001"

        // ── Style defaults (v1.0.2+) ────────────────────────────────────
        // These match the exact visual look shipped in v1.0.1, so an
        // integration that doesn't set any style attrs renders identically
        // to before.
        private const val DEFAULT_TITLE_COLOR = "#FFFFFF"
        private const val DEFAULT_TITLE_FONT_SIZE_SP = 15f
        private const val DEFAULT_STATUS_COLOR = "#99FFFFFF" // white @ ~60% alpha
        private const val DEFAULT_STATUS_FONT_SIZE_SP = 13f
        private const val DEFAULT_BRAND_COLOR = "#FF9500"
        private const val DEFAULT_BRAND_FONT_SIZE_SP = 12f
        private const val DEFAULT_PROGRESS_COLOR = "#FF9500"
        private const val DEFAULT_PROGRESS_FONT_SIZE_SP = 12f
        private const val DEFAULT_ICON_COLOR = "#FFFFFF"
        private const val DEFAULT_ICON_SIZE_DP = 26f
    }

    /**
     * Fixed-for-lifetime metadata, mirroring iOS's `DynamicIslandAttributes`.
     * Set once on startActivity(), reused by every subsequent updateActivity()
     * call — same as ActivityKit's Attributes vs. ContentState split.
     */
    private data class LiveActivityAttributes(
        val brandName: String,
        val stepCount: Int,
        val androidStepIcons: List<String>?, // optional drawable resource names, per step
        val logoResourceName: String?,
        // ── Style config (v1.0.2+) ──────────────────────────────────────
        val titleColor: Int,
        val titleFontSizeSp: Float,
        val statusColor: Int,
        val statusFontSizeSp: Float,
        val brandColor: Int,
        val brandFontSizeSp: Float,
        val progressColor: Int,
        val progressFontSizeSp: Float,
        val iconColor: Int,
        val iconSizeDp: Float
    )

    // Stored in-memory for the life of the module, reused across update calls
    // — Android has no ActivityKit-style persistent Attributes object, so we
    // hold it here ourselves. Backed by SharedPreferences (see below) so it
    // also survives process death, not just module reuse within one process.
    private var currentAttributes: LiveActivityAttributes? = null

    private val prefs: SharedPreferences
        get() = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        createChannelIfNeeded()
    }

    override fun getName(): String = "DynamicIsland"

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Live Updates",
                    NotificationManager.IMPORTANCE_LOW // no sound/heads-up on update
                )
                channel.description =
                    "Ongoing tracking notifications (Android equivalent of iOS Live Activities)"
                manager.createNotificationChannel(channel)
            }
        }
    }

    @ReactMethod
    fun areActivitiesEnabled(promise: Promise) {
        // True if notifications are enabled for the app at all. This is the
        // real Android gate — there's no separate "Live Activities" toggle.
        promise.resolve(NotificationManagerCompat.from(reactContext).areNotificationsEnabled())
    }

    @ReactMethod
    fun startActivity(content: ReadableMap, attributes: ReadableMap, promise: Promise) {
        val parsed = parseAttributes(attributes)
        currentAttributes = parsed
        persistAttributes(parsed)
        postOrUpdate(content, promise)
    }

    @ReactMethod
    fun updateActivity(content: ReadableMap, promise: Promise) {
        if (!NotificationManagerCompat.from(reactContext).activeNotifications
                .any { it.id == NOTIFICATION_ID }
        ) {
            promise.reject("NO_ACTIVITY", "No live-update notification is currently running")
            return
        }
        // currentAttributes (brand/icons/logo/style) persists from startActivity —
        // never resent here, same as iOS only touching ContentState on update.
        postOrUpdate(content, promise)
    }

    @ReactMethod
    fun endActivity(promise: Promise) {
        NotificationManagerCompat.from(reactContext).cancel(NOTIFICATION_ID)
        currentAttributes = null
        clearPersistedAttributes()
        promise.resolve(true)
    }

    // MARK: - Attributes parsing

    private fun parseAttributes(attrs: ReadableMap): LiveActivityAttributes {
        val brandName = if (attrs.hasKey("brandName")) attrs.getString("brandName") ?: "App" else "App"

        val stepIcons: List<String>? = if (attrs.hasKey("stepIcons")) {
            readStringArray(attrs.getArray("stepIcons"))
        } else null

        val androidStepIcons: List<String>? = if (attrs.hasKey("androidStepIcons")) {
            readStringArray(attrs.getArray("androidStepIcons"))
        } else null

        // Step count comes from stepIcons length if provided (shared with iOS),
        // defaulting to 4 to match the iOS default set.
        val stepCount = stepIcons?.size?.takeIf { it > 0 } ?: 4

        val logoResourceName = if (attrs.hasKey("logoAssetName")) {
            attrs.getString("logoAssetName")
        } else null

        // ── Style config (v1.0.2+) — each falls back to the v1.0.1 default
        // look if the key is absent or the hex string fails to parse.
        val titleColor = parseColorOrDefault(attrs, "titleColor", DEFAULT_TITLE_COLOR)
        val titleFontSizeSp = readFloat(attrs, "titleFontSize", DEFAULT_TITLE_FONT_SIZE_SP)
        val statusColor = parseColorOrDefault(attrs, "statusColor", DEFAULT_STATUS_COLOR)
        val statusFontSizeSp = readFloat(attrs, "statusFontSize", DEFAULT_STATUS_FONT_SIZE_SP)
        val brandColor = parseColorOrDefault(attrs, "brandColor", DEFAULT_BRAND_COLOR)
        val brandFontSizeSp = readFloat(attrs, "brandFontSize", DEFAULT_BRAND_FONT_SIZE_SP)
        val progressColor = parseColorOrDefault(attrs, "progressColor", DEFAULT_PROGRESS_COLOR)
        val progressFontSizeSp = readFloat(attrs, "progressFontSize", DEFAULT_PROGRESS_FONT_SIZE_SP)
        val iconColor = parseColorOrDefault(attrs, "iconColor", DEFAULT_ICON_COLOR)
        val iconSizeDp = readFloat(attrs, "iconSize", DEFAULT_ICON_SIZE_DP)

        return LiveActivityAttributes(
            brandName = brandName,
            stepCount = stepCount,
            androidStepIcons = androidStepIcons,
            logoResourceName = logoResourceName,
            titleColor = titleColor,
            titleFontSizeSp = titleFontSizeSp,
            statusColor = statusColor,
            statusFontSizeSp = statusFontSizeSp,
            brandColor = brandColor,
            brandFontSizeSp = brandFontSizeSp,
            progressColor = progressColor,
            progressFontSizeSp = progressFontSizeSp,
            iconColor = iconColor,
            iconSizeDp = iconSizeDp
        )
    }

    private fun readStringArray(array: ReadableArray?): List<String>? {
        if (array == null) return null
        val result = mutableListOf<String>()
        for (i in 0 until array.size()) {
            array.getString(i)?.let { result.add(it) }
        }
        return result
    }

    private fun readFloat(attrs: ReadableMap, key: String, default: Float): Float {
        if (!attrs.hasKey(key)) return default
        return try {
            attrs.getDouble(key).toFloat()
        } catch (e: Exception) {
            default
        }
    }

    /** Parses a "#RRGGBB" hex string; falls back to [defaultHex] if absent, wrong type, or malformed. */
    private fun parseColorOrDefault(attrs: ReadableMap, key: String, defaultHex: String): Int {
        val hex = try {
            if (attrs.hasKey(key)) attrs.getString(key) else null
        } catch (e: Exception) {
            // Key present but not a string (e.g. a number was passed by mistake).
            null
        }
        return try {
            Color.parseColor(if (hex.isNullOrBlank()) defaultHex else hex)
        } catch (e: IllegalArgumentException) {
            Color.parseColor(defaultHex)
        }
    }

    // MARK: - Attributes persistence (survives process death)

    private fun persistAttributes(attrs: LiveActivityAttributes) {
        try {
            prefs.edit().apply {
                putBoolean(KEY_HAS_SAVED_ATTRS, true)
                putString(KEY_BRAND_NAME, attrs.brandName)
                putInt(KEY_STEP_COUNT, attrs.stepCount)
                putString(KEY_ANDROID_STEP_ICONS, attrs.androidStepIcons?.joinToString(LIST_DELIMITER))
                putString(KEY_LOGO_RESOURCE_NAME, attrs.logoResourceName)
                putInt(KEY_TITLE_COLOR, attrs.titleColor)
                putFloat(KEY_TITLE_FONT_SIZE, attrs.titleFontSizeSp)
                putInt(KEY_STATUS_COLOR, attrs.statusColor)
                putFloat(KEY_STATUS_FONT_SIZE, attrs.statusFontSizeSp)
                putInt(KEY_BRAND_COLOR, attrs.brandColor)
                putFloat(KEY_BRAND_FONT_SIZE, attrs.brandFontSizeSp)
                putInt(KEY_PROGRESS_COLOR, attrs.progressColor)
                putFloat(KEY_PROGRESS_FONT_SIZE, attrs.progressFontSizeSp)
                putInt(KEY_ICON_COLOR, attrs.iconColor)
                putFloat(KEY_ICON_SIZE, attrs.iconSizeDp)
                apply()
            }
        } catch (e: Exception) {
            // Non-fatal: worst case a future process restart falls back to
            // hardcoded defaults instead of the real attrs — same as today.
        }
    }

    private fun loadPersistedAttributes(): LiveActivityAttributes? {
        return try {
            if (!prefs.getBoolean(KEY_HAS_SAVED_ATTRS, false)) return null

            val stepIconsRaw = prefs.getString(KEY_ANDROID_STEP_ICONS, null)
            val androidStepIcons = stepIconsRaw
                ?.takeIf { it.isNotEmpty() }
                ?.split(LIST_DELIMITER)

            LiveActivityAttributes(
                brandName = prefs.getString(KEY_BRAND_NAME, "App") ?: "App",
                stepCount = prefs.getInt(KEY_STEP_COUNT, 4),
                androidStepIcons = androidStepIcons,
                logoResourceName = prefs.getString(KEY_LOGO_RESOURCE_NAME, null),
                titleColor = prefs.getInt(KEY_TITLE_COLOR, Color.parseColor(DEFAULT_TITLE_COLOR)),
                titleFontSizeSp = prefs.getFloat(KEY_TITLE_FONT_SIZE, DEFAULT_TITLE_FONT_SIZE_SP),
                statusColor = prefs.getInt(KEY_STATUS_COLOR, Color.parseColor(DEFAULT_STATUS_COLOR)),
                statusFontSizeSp = prefs.getFloat(KEY_STATUS_FONT_SIZE, DEFAULT_STATUS_FONT_SIZE_SP),
                brandColor = prefs.getInt(KEY_BRAND_COLOR, Color.parseColor(DEFAULT_BRAND_COLOR)),
                brandFontSizeSp = prefs.getFloat(KEY_BRAND_FONT_SIZE, DEFAULT_BRAND_FONT_SIZE_SP),
                progressColor = prefs.getInt(KEY_PROGRESS_COLOR, Color.parseColor(DEFAULT_PROGRESS_COLOR)),
                progressFontSizeSp = prefs.getFloat(KEY_PROGRESS_FONT_SIZE, DEFAULT_PROGRESS_FONT_SIZE_SP),
                iconColor = prefs.getInt(KEY_ICON_COLOR, Color.parseColor(DEFAULT_ICON_COLOR)),
                iconSizeDp = prefs.getFloat(KEY_ICON_SIZE, DEFAULT_ICON_SIZE_DP)
            )
        } catch (e: Exception) {
            // Corrupt/partial prefs (e.g. an app update changed the schema) —
            // fall back to hardcoded defaults rather than crash.
            null
        }
    }

    private fun clearPersistedAttributes() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            // Non-fatal.
        }
    }

    // MARK: - Notification building

    private fun postOrUpdate(content: ReadableMap, promise: Promise) {
        try {
            val map = content.toHashMap()

            // In-memory first; if this module instance was recreated after
            // process death, recover from SharedPreferences before falling
            // back to hardcoded defaults.
            val attrs = currentAttributes
                ?: loadPersistedAttributes()?.also { currentAttributes = it }
                ?: parseAttributes(com.facebook.react.bridge.Arguments.createMap())

            val title = map["title"] as? String ?: ""
            val status = map["status"] as? String ?: ""
            val eta = map["eta"] as? String ?: ""
            val progress = (map["progress"] as? String)?.toIntOrNull()?.coerceIn(0, 100) ?: 0

            val remoteViews = buildRemoteViews(attrs, title, status, eta, progress)

            val builder = NotificationCompat.Builder(reactContext, CHANNEL_ID)
                .setSmallIcon(resolveSmallIcon())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(buildTapPendingIntent(map["deepLink"] as? String))
                .setAutoCancel(false) // it's "ongoing" — don't dismiss on tap
                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())

            NotificationManagerCompat.from(reactContext).notify(NOTIFICATION_ID, builder.build())
            promise.resolve(NOTIFICATION_ID.toString())
        } catch (e: SecurityException) {
            // Most likely POST_NOTIFICATIONS not granted on Android 13+.
            promise.reject("PERMISSION_DENIED", "Notification permission not granted", e)
        } catch (e: Exception) {
            promise.reject("UPDATE_FAILED", e.message, e)
        }
    }

    private fun buildRemoteViews(
        attrs: LiveActivityAttributes,
        title: String,
        status: String,
        eta: String,
        progress: Int
    ): RemoteViews {
        val views = RemoteViews(reactContext.packageName, R.layout.dynamicisland_notification_live_activity)
        views.setTextViewText(R.id.tvTitle, title)
        views.setTextViewText(R.id.tvStatus, status)
        views.setTextViewText(R.id.tvBrand, attrs.brandName)
        views.setTextViewText(R.id.tvEta, eta)

        // ── Style config (v1.0.2+) ──────────────────────────────────────
        views.setTextColor(R.id.tvTitle, attrs.titleColor)
        views.setTextViewTextSize(R.id.tvTitle, TypedValue.COMPLEX_UNIT_SP, attrs.titleFontSizeSp)
        views.setTextColor(R.id.tvStatus, attrs.statusColor)
        views.setTextViewTextSize(R.id.tvStatus, TypedValue.COMPLEX_UNIT_SP, attrs.statusFontSizeSp)
        views.setTextColor(R.id.tvBrand, attrs.brandColor)
        views.setTextViewTextSize(R.id.tvBrand, TypedValue.COMPLEX_UNIT_SP, attrs.brandFontSizeSp)
        views.setTextColor(R.id.tvEta, attrs.progressColor)
        views.setTextViewTextSize(R.id.tvEta, TypedValue.COMPLEX_UNIT_SP, attrs.progressFontSizeSp)

        views.setImageViewBitmap(R.id.ivLogo, resolveLogoBitmap(attrs.logoResourceName))
        val activeStepIndex = currentStepIndex(progress, attrs.stepCount)
        val trackBitmap = drawProgressTrack(
            stepCount = attrs.stepCount,
            activeStepIndex = activeStepIndex,
            androidStepIcons = attrs.androidStepIcons,
            progressColor = attrs.progressColor,
            iconColor = attrs.iconColor,
            iconSizeDp = attrs.iconSizeDp
        )
        views.setImageViewBitmap(R.id.ivTrack, trackBitmap)

        return views
    }

    private fun currentStepIndex(progress: Int, stepCount: Int): Int {
        if (stepCount <= 0) return 0
        val stepSize = (100 / stepCount).coerceAtLeast(1)
        return (progress / stepSize).coerceAtMost(stepCount - 1)
    }

    /**
     * Draws the progress track as a single bitmap: filled circles connected
     * by lines, [progressColor] for completed steps, translucent white for
     * pending — same visual language as the iOS SwiftUI HStack track, just
     * rasterized since RemoteViews can't host a dynamic per-step layout
     * natively.
     *
     * If [androidStepIcons] is provided, draws those drawable resources
     * inside each circle. Otherwise falls back to a [iconColor]-tinted
     * checkmark for done steps and a plain dot for pending ones.
     * [iconSizeDp] controls the circle diameter (default 26dp, matches v1.0.1).
     */
    private fun drawProgressTrack(
        stepCount: Int,
        activeStepIndex: Int,
        androidStepIcons: List<String>?,
        progressColor: Int,
        iconColor: Int,
        iconSizeDp: Float
    ): Bitmap {
        val density = reactContext.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (26 * density).toInt()
        val circleRadius = 13f * density
        val lineHeight = 3f * density

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = progressColor }
        val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26FFFFFF") }
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            strokeWidth = 2.5f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        // Pending-step dot: iconColor at ~40% alpha (0x66 of 0xFF), matching
        // the translucency of the v1.0.1 hardcoded "#66FFFFFF".
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(iconColor, 0x66)
        }

        val slotWidth = width.toFloat() / stepCount
        val centerY = height / 2f

        for (i in 0 until stepCount) {
            val centerX = slotWidth * i + slotWidth / 2f
            val isDone = i <= activeStepIndex

            // connecting line to the next step
            if (i < stepCount - 1) {
                val nextCenterX = slotWidth * (i + 1) + slotWidth / 2f
                val linePaint = if (i < activeStepIndex) donePaint else pendingPaint
                canvas.drawRect(
                    centerX + circleRadius, centerY - lineHeight / 2,
                    nextCenterX - circleRadius, centerY + lineHeight / 2,
                    linePaint
                )
            }

            // circle
            canvas.drawCircle(centerX, centerY, circleRadius, if (isDone) donePaint else pendingPaint)

            // icon inside — custom drawable if supplied, else generic check/dot
            val customRes = androidStepIcons?.getOrNull(i)?.let { resolveDrawableId(it) }
            if (customRes != null) {
                val drawable = ContextCompat.getDrawable(reactContext, customRes)
                if (drawable is BitmapDrawable) {
                    val iconPixelSize = (circleRadius * 1.1f).toInt()
                    val bmp = Bitmap.createScaledBitmap(drawable.bitmap, iconPixelSize, iconPixelSize, true)
                    canvas.drawBitmap(
                        bmp,
                        centerX - iconPixelSize / 2f,
                        centerY - iconPixelSize / 2f,
                        null
                    )
                }
            } else if (isDone) {
                // simple checkmark path
                val r = circleRadius * 0.5f
                canvas.drawLine(centerX - r, centerY, centerX - r * 0.2f, centerY + r * 0.7f, checkPaint)
                canvas.drawLine(centerX - r * 0.2f, centerY + r * 0.7f, centerX + r, centerY - r * 0.6f, checkPaint)
            } else {
                canvas.drawCircle(centerX, centerY, circleRadius * 0.18f, dotPaint)
            }
        }

        return bitmap
    }

    private fun resolveLogoBitmap(logoResourceName: String?): Bitmap {
        val resId = logoResourceName
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveDrawableId(it) }

        if (resId != null) {
            val drawable = ContextCompat.getDrawable(reactContext, resId)
            if (drawable != null) {
                return if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val bmp = Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(1),
                        drawable.intrinsicHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp
                }
            }
        }

        return drawFallbackLogoBox()
    }

    /**
     * Generic placeholder: orange-tinted rounded square with a simple box icon,
     * drawn entirely in code — no shipped drawable needed, always matches the
     * orange accent used elsewhere in the card.
     */
    private fun drawFallbackLogoBox(): Bitmap {
        val density = reactContext.resources.displayMetrics.density
        val size = (30 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#26FF9500") // orange @ ~15% opacity, matches iOS
        }
        val cornerRadius = 10f * density
        canvas.drawRoundRect(
            0f, 0f, size.toFloat(), size.toFloat(),
            cornerRadius, cornerRadius, bgPaint
        )

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9500")
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // simple box/package outline, centered
        val pad = size * 0.28f
        val boxTop = size * 0.32f
        canvas.drawRect(pad, boxTop, size - pad, size - pad, iconPaint)
        canvas.drawLine(pad, boxTop, size / 2f, size * 0.18f, iconPaint)
        canvas.drawLine(size - pad, boxTop, size / 2f, size * 0.18f, iconPaint)
        canvas.drawLine(size / 2f, size * 0.18f, size / 2f, boxTop, iconPaint)

        return bitmap
    }

    /**
     * Looks up a drawable by name in the HOST APP's own resources — mirrors
     * iOS requiring logoAssetName to exist in the Widget Extension's own
     * Assets.xcassets. The host app adds the drawable once; this just resolves it.
     */
    private fun resolveDrawableId(name: String): Int? {
        val resId = reactContext.resources.getIdentifier(name, "drawable", reactContext.packageName)
        return resId.takeIf { it != 0 }
    }

    private fun buildTapPendingIntent(deepLink: String?): PendingIntent? {
        val packageName = reactContext.packageName

        val intent: Intent = if (!deepLink.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                setPackage(packageName)
            }
        } else {
            reactContext.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getActivity(reactContext, NOTIFICATION_ID, intent, flags)
    }

    private fun resolveSmallIcon(): Int {
        // Falls back to a system icon so the library doesn't require the
        // host app to ship a specific drawable name.
        return try {
            reactContext.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_popup_reminder
        } catch (e: Exception) {
            android.R.drawable.ic_popup_reminder
        }
    }
}