import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  ActivityIndicator,
  TouchableOpacity,
  Platform,
  PermissionsAndroid,
  Linking,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  areActivitiesEnabled,
  startActivity,
  updateActivity,
  endActivity,
  type DynamicIslandContentState,
  DynamicIslandAttributes,
} from '@noobdigital/react-native-dynamic-island';

// ─── Demo data ──────────────────────────────────────────────────────────────
// Mimics a talabat-style order tracking card, stage by stage.

const DEEP_LINK = 'sampleapp://order-tracking/4821';

const BRAND: DynamicIslandAttributes = {
  brandName: 'NoobEats',
  stepIcons: ['checkmark', 'bag.fill', 'bicycle', 'mappin.and.ellipse'], // For ios, optional. Android ignores this. can be any SF Symbol name, one per stage, in order. Drives the progress track UI. https://developer.apple.com/sf-symbols/
  logoAssetName: Platform.OS === 'ios' ? 'BrandLogo' : 'noobeats_logo', // optional
};

const STAGES: DynamicIslandContentState[] = [
  {
    title: 'Order confirmed',
    status: 'Order #4821 · 3 items',
    eta: '11:20 PM',
    progress: '10',
    deepLink: DEEP_LINK,
  },
  {
    title: 'Preparing your order',
    status: 'NoobEats kitchen is on it',
    eta: '11:05 PM',
    progress: '35',
    deepLink: DEEP_LINK,
  },
  {
    title: 'Rider picked up your order',
    status: 'On the way to you',
    eta: '10:58 PM',
    progress: '65',
    deepLink: DEEP_LINK,
  },
  {
    title: 'Arriving now',
    status: 'Your rider is almost there',
    eta: '10:56 PM',
    progress: '90',
    deepLink: DEEP_LINK,
  },
  {
    title: 'Delivered',
    status: 'Enjoy your meal! 🎉',
    eta: '10:55 PM',
    progress: '100',
    deepLink: DEEP_LINK,
  },
];
// ── Persistence ─────────────────────────────────────────────────────────────
// Keeps the in-app UI in sync with whatever the OS-level Live Activity /
// notification is currently showing. Without this, the JS component's
// state (stageIndex/running) is purely in-memory: if the app process is
// recreated — e.g. tapping the notification when the app wasn't already
// resumed, low-memory kill, etc. — the UI resets to the start screen even
// though the notification itself is still showing a later stage.

const STORAGE_KEY = '@dynamic_island_demo/state';

type PersistedState = { stageIndex: number };

async function persistStage(stageIndex: number | null) {
  try {
    if (stageIndex === null) {
      await AsyncStorage.removeItem(STORAGE_KEY);
    } else {
      const value: PersistedState = { stageIndex };
      await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(value));
    }
  } catch (e) {
    // Non-fatal: worst case the UI just won't rehydrate next launch.
    console.warn('[DynamicIsland] failed to persist stage:', e);
  }
}

async function readPersistedStage(): Promise<number | null> {
  try {
    const raw = await AsyncStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as PersistedState;
    if (
      typeof parsed.stageIndex === 'number' &&
      parsed.stageIndex >= 0 &&
      parsed.stageIndex < STAGES.length
    ) {
      return parsed.stageIndex;
    }
    return null;
  } catch {
    return null;
  }
}

type PermissionState = 'unknown' | 'granted' | 'denied' | 'not-required';

// ─── Sub-components ───────────────────────────────────────────────────────────

function LiveCardPreview({ content }: { content: DynamicIslandContentState | null }) {
  if (!content) {
    return (
      <View style={[styles.liveCard, styles.liveCardEmpty]}>
        <Text style={styles.liveCardEmptyText}>No live activity running</Text>
      </View>
    );
  }

  const progress = Number(content.progress ?? '0');

  return (
    <View style={styles.liveCard}>
      <View style={styles.liveCardTopRow}>
        <View style={styles.liveCardDot} />
        <Text style={styles.liveCardTitle}>{content.title}</Text>
        <Text style={styles.liveCardEta}>{content.eta}</Text>
      </View>
      <Text style={styles.liveCardStatus}>{content.status}</Text>
      <View style={styles.liveCardBarBg}>
        <View style={[styles.liveCardBarFill, { width: `${progress}%` as any }]} />
      </View>
      <Text style={styles.liveCardPlatformNote}>
        {Platform.OS === 'ios'
          ? 'Rendering as a Live Activity / Dynamic Island'
          : 'Rendering as an ongoing lock-screen notification'}
      </Text>
    </View>
  );
}

function ActionButton({
  label,
  onPress,
  loading,
  disabled,
  variant = 'primary',
}: {
  label: string;
  onPress: () => void;
  loading?: boolean;
  disabled?: boolean;
  variant?: 'primary' | 'secondary' | 'danger';
}) {
  return (
    <TouchableOpacity
      style={[
        styles.actionButton,
        variant === 'secondary' && styles.actionButtonSecondary,
        variant === 'danger' && styles.actionButtonDanger,
        disabled && styles.actionButtonDisabled,
      ]}
      onPress={onPress}
      disabled={disabled || loading}
      activeOpacity={0.8}
    >
      {loading ? (
        <ActivityIndicator size="small" color={variant === 'secondary' ? '#6C63FF' : '#FFF'} />
      ) : (
        <Text
          style={[
            styles.actionButtonText,
            variant === 'secondary' && styles.actionButtonTextSecondary,
          ]}
        >
          {label}
        </Text>
      )}
    </TouchableOpacity>
  );
}

// ─── Main screen ──────────────────────────────────────────────────────────────

export default function SampleAppScreen() {
  const [checkingAvailability, setCheckingAvailability] = useState(true);
  const [enabled, setEnabled] = useState<boolean>(false);
  const [permission, setPermission] = useState<PermissionState>('unknown');

  const [restoringState, setRestoringState] = useState(true);
  const [running, setRunning] = useState(false);
  const [stageIndex, setStageIndex] = useState(-1);
  const [currentContent, setCurrentContent] = useState<DynamicIslandContentState | null>(null);

  const [starting, setStarting] = useState(false);
  const [advancing, setAdvancing] = useState(false);
  const [ending, setEnding] = useState(false);
  // ── Check Navigation from deep link on mount, so the app can route to the correct screen if launched from a notification tap.
  useEffect(() => {
  const handleUrl = ({ url }: { url: string }) => {
    if (!url) return;
    routeFromUrl(url);
  };

  const sub = Linking.addEventListener('url', handleUrl);

  Linking.getInitialURL().then((url) => {
    if (url) handleUrl({ url });
  });

  return () => sub.remove();
}, []);


  function routeFromUrl(url: string) {
    if (!url) return;

    if (!url.startsWith('sampleapp://')) return;

    console.log('Deep Link', `Navigating to Order Tracking screen for URL: ${url}`);
  }



  // ── Check platform availability on mount
  useEffect(() => {
    async function check() {
      try {
        const result = await areActivitiesEnabled();
        console.log('MB : Live Activities enabled:', result);
        setEnabled(result);
      } catch {
        setEnabled(false);
      } finally {
        setCheckingAvailability(false);
      }
    }
    check();
  }, []);

  // ── Rehydrate stage/running state from AsyncStorage on mount, so the UI
  // matches whatever the OS Live Activity / notification is currently
  // showing rather than always resetting to the start screen.
  useEffect(() => {
    async function restore() {
      try {
        const savedStage = await readPersistedStage();
        if (savedStage !== null) {
          setCurrentContent(STAGES[savedStage]);
          setStageIndex(savedStage);
          setRunning(true);
        }
      } finally {
        setRestoringState(false);
      }
    }
    restore();
  }, []);

  

  // ── Android 13+ needs a runtime permission before notifications will post
  useEffect(() => {
    async function checkPermission() {
      if (Platform.OS !== 'android' || Platform.Version < 33) {
        setPermission('not-required');
        return;
      }
      const granted = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      );
      setPermission(granted ? 'granted' : 'denied');
    }
    checkPermission();
  }, []);

  const requestPermission = useCallback(async () => {
    if (Platform.OS !== 'android') return;
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    );
    setPermission(result === PermissionsAndroid.RESULTS.GRANTED ? 'granted' : 'denied');
    const result2 = await areActivitiesEnabled();
    setEnabled(result2);
  }, []);

  // ── End activity on unmount so a demo doesn't leave a stuck notification.
  // Also clears persisted state so it stays in sync with the cancelled
  // notification — otherwise a future mount would rehydrate into a stage
  // that no longer has a live notification behind it.
  useEffect(() => {
    return () => {
      endActivity().catch(() => {});
      persistStage(null);
    };
  }, []);

  // ── Actions
  const handleStart = useCallback(async () => {
    setStarting(true);
    try {
      await startActivity(STAGES[0],BRAND);
  
      setCurrentContent(STAGES[0]);
      setStageIndex(0);
      setRunning(true);
      await persistStage(0);
    } catch (e) {
      console.warn('[DynamicIsland] startActivity failed:', e);
    } finally {
      setStarting(false);
    }
  }, []);

  const handleAdvance = useCallback(async () => {
    if (stageIndex < 0 || stageIndex >= STAGES.length - 1) return;
    setAdvancing(true);
    try {
      const next = stageIndex + 1;
      await updateActivity(STAGES[next]); 
      setCurrentContent(STAGES[next]);
      setStageIndex(next);
      await persistStage(next);
    } catch (e) {
      console.warn('[DynamicIsland] updateActivity failed:', e);
    } finally {
      setAdvancing(false);
    }
  }, [stageIndex]);

  const handleEnd = useCallback(async () => {
    setEnding(true);
    try {
      await endActivity();
      setCurrentContent(null);
      setStageIndex(-1);
      setRunning(false);
      await persistStage(null);
    } catch (e) {
      console.warn('[DynamicIsland] endActivity failed:', e);
    } finally {
      setEnding(false);
    }
  }, []);

  const isLastStage = stageIndex === STAGES.length - 1;
  const needsAndroidPermission = Platform.OS === 'android' && permission === 'denied';

  // ─── Loading state ─────────────────────────────────────────────────────────

  if (checkingAvailability || restoringState) {
    return (
      <View style={styles.centerScreen}>
        <ActivityIndicator size="large" color="#6C63FF" />
        <Text style={styles.loadingText}>Checking live activity support…</Text>
      </View>
    );
  }

  // ─── Render ────────────────────────────────────────────────────────────────

  return (
    <ScrollView contentContainerStyle={styles.container}>

      <View style={styles.branding}>
        <Text style={styles.brandUrl}>www.noobdigital.com</Text>
      </View>

      <Text style={styles.title}>Dynamic Island Live Demo</Text>

      {/* ── Availability Card ─────────────────────────────────────────────── */}
      <View
        style={[
          styles.availabilityCard,
          { borderColor: enabled ? '#5cb85c' : '#e67e22' },
        ]}
      >
        <View style={styles.availabilityRow}>
          <Text style={styles.availabilityIcon}>{enabled ? '✅' : '⚠️'}</Text>
          <View style={{ flex: 1 }}>
            <Text style={styles.availabilityTitle}>
              {enabled ? 'Live updates available' : 'Live updates unavailable'}
            </Text>
            <Text style={styles.availabilitySubtitle}>
              {Platform.OS === 'ios'
                ? 'Requires iOS 16.1+ and Live Activities enabled in Settings'
                : needsAndroidPermission
                  ? 'Notification permission not granted'
                  : 'Requires notifications enabled for this app'}
            </Text>
          </View>
        </View>

        {needsAndroidPermission && (
          <TouchableOpacity style={styles.permissionButton} onPress={requestPermission}>
            <Text style={styles.permissionButtonText}>Grant Notification Permission</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* ── Live Card Preview ─────────────────────────────────────────────── */}
      <Text style={styles.sectionTitle}>Live Card Preview</Text>
      <LiveCardPreview content={currentContent} />

      {/* ── Controls ───────────────────────────────────────────────────────── */}
      <Text style={[styles.sectionTitle, { marginTop: 24 }]}>Controls</Text>

      {!running ? (
        <ActionButton
          label="Start Order Tracking"
          onPress={handleStart}
          loading={starting}
          disabled={!enabled}
        />
      ) : (
        <View style={styles.controlRow}>
          <ActionButton
            label={isLastStage ? 'All stages shown' : 'Advance Stage'}
            onPress={handleAdvance}
            loading={advancing}
            disabled={isLastStage}
            variant="secondary"
          />
          <ActionButton
            label="End"
            onPress={handleEnd}
            loading={ending}
            variant="danger"
          />
        </View>
      )}

      {!enabled && (
        <Text style={styles.disabledHint}>
          {Platform.OS === 'ios'
            ? 'Enable Live Activities for this app in iOS Settings to try the demo.'
            : 'Grant the notification permission above to try the demo.'}
        </Text>
      )}

      {/* ── Platform Notes ────────────────────────────────────────────────── */}
      <View style={styles.hintBox}>
        <Text style={styles.hintText}>
          💡 On iOS this drives a real Live Activity / Dynamic Island via
          ActivityKit (requires a Widget Extension target — see the package
          README). On Android there's no hardware equivalent to the Dynamic
          Island pill, so the same calls drive an ongoing, lock-screen
          notification instead — the closest real feature Android offers for
          live order tracking.
        </Text>
      </View>

      <View style={styles.footer} />
    </ScrollView>
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    flexGrow: 1,
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#FAFAFA',
  },
  centerScreen: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#FAFAFA',
  },
  loadingText: { marginTop: 12, fontSize: 15, color: '#555' },

  branding: { marginTop: 40, alignItems: 'center', marginBottom: 4 },
  brandUrl: { fontSize: 13, color: '#888', fontWeight: '500' },

  title: {
    fontSize: 18,
    fontWeight: '700',
    color: '#111',
    marginTop: 20,
    marginBottom: 20,
    textAlign: 'center',
  },

  // ── Availability card
  availabilityCard: {
    width: '100%',
    backgroundColor: '#FFF',
    borderRadius: 16,
    borderWidth: 1.5,
    padding: 18,
    marginBottom: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  availabilityRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  availabilityIcon: { fontSize: 24 },
  availabilityTitle: { fontSize: 15, fontWeight: '700', color: '#111' },
  availabilitySubtitle: { fontSize: 12, color: '#6B7280', marginTop: 2, lineHeight: 17 },
  permissionButton: {
    marginTop: 14,
    backgroundColor: '#6C63FF',
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
  permissionButtonText: { color: '#FFF', fontSize: 13, fontWeight: '700' },

  // ── Section headers
  sectionTitle: {
    width: '100%',
    fontSize: 12,
    fontWeight: '700',
    color: '#999',
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginBottom: 10,
  },

  // ── Live card preview (mimics the OS card)
  liveCard: {
    width: '100%',
    backgroundColor: '#1C1C1E',
    borderRadius: 20,
    padding: 16,
    marginBottom: 8,
  },
  liveCardEmpty: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 30,
    backgroundColor: '#F3F4F6',
  },
  liveCardEmptyText: { color: '#9CA3AF', fontSize: 13, fontWeight: '500' },
  liveCardTopRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 6 },
  liveCardDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#34D399',
    marginRight: 8,
  },
  liveCardTitle: { flex: 1, color: '#FFF', fontSize: 14, fontWeight: '700' },
  liveCardEta: { color: '#D1D5DB', fontSize: 12, fontWeight: '600' },
  liveCardStatus: { color: '#E5E7EB', fontSize: 13, marginBottom: 10 },
  liveCardBarBg: {
    height: 5,
    backgroundColor: '#3A3A3C',
    borderRadius: 3,
    overflow: 'hidden',
    marginBottom: 10,
  },
  liveCardBarFill: { height: 5, backgroundColor: '#6C63FF', borderRadius: 3 },
  liveCardPlatformNote: { color: '#8E8E93', fontSize: 10, fontStyle: 'italic' },

  // ── Controls
  controlRow: { width: '100%', flexDirection: 'row', gap: 10 },
  actionButton: {
    flex: 1,
    backgroundColor: '#6C63FF',
    borderRadius: 12,
    paddingVertical: 14,
    paddingHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionButtonSecondary: {
    backgroundColor: '#EEF2FF',
    borderWidth: 1,
    borderColor: '#6C63FF',
  },
  actionButtonDanger: { backgroundColor: '#d9534f' },
  actionButtonDisabled: { opacity: 0.5 },
  actionButtonText: { color: '#FFF', fontSize: 14, fontWeight: '700' },
  actionButtonTextSecondary: { color: '#6C63FF' },

  disabledHint: {
    width: '100%',
    fontSize: 12,
    color: '#9CA3AF',
    marginTop: 10,
    textAlign: 'center',
  },

  // ── Hint box
  hintBox: {
    width: '100%',
    marginTop: 28,
    padding: 14,
    backgroundColor: '#F9FAFB',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#E5E7EB',
  },
  hintText: { fontSize: 12, color: '#6B7280', lineHeight: 18 },

  footer: { height: 40 },
});