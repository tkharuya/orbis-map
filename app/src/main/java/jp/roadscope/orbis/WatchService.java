package jp.roadscope.orbis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WatchService extends Service implements LocationListener {
    public static final String PREFS = "watch";
    public static final String KEY_DISTANCE = "distance_m";
    public static final String KEY_VOICE = "voice";
    public static final String ALERT_CHANNEL_ID = "camera_alerts";
    public static final String SERVICE_CHANNEL_ID = "drive_watch";
    public static final String IDLE_STATUS = "通知は停止中";

    private static final String ACTION_START = "jp.roadscope.orbis.WATCH_START";
    private static final String ACTION_STOP = "jp.roadscope.orbis.WATCH_STOP";
    private static final String EXTRA_SESSION = "session";
    private static final int WATCH_ID = 41;
    private static final int ALERT_ID = 42;
    private static final int TEST_ID = 43;
    private static final int DEFAULT_DISTANCE = 700;
    private static final long ALERT_DISPLAY_MS = 12_000;
    private static final String TAG = "OrbisWatch";
    private static final String GPS_WAIT = "GPSを取得中";
    private static final String GPS_DISABLED = "GPSがオフです";
    private static final String GPS_IMPRECISE = "GPS精度の改善を待機中";
    private static final String SEARCHING_STATUS = "前方を監視中";

    public static final class Snapshot {
        public final boolean running;
        public final Location location;
        public final String status;
        public final CameraPoint nearby;
        public final int distanceMeters;
        public final float speedKmh;
        public final String lastAlert;

        private Snapshot(boolean running, Location location, String status, CameraPoint nearby,
                int distanceMeters, float speedKmh, String lastAlert) {
            this.running = running;
            this.location = location;
            this.status = status;
            this.nearby = nearby;
            this.distanceMeters = distanceMeters;
            this.speedKmh = speedKmh;
            this.lastAlert = lastAlert;
        }
    }

    private static volatile Snapshot current = stopped(IDLE_STATUS, null);
    private static volatile long currentSession;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertEngine engine;
    private LocationManager gps;
    private NotificationManager notifications;
    private SharedPreferences prefs;
    private List<CameraPoint> cameras;
    private long cameraRevision;
    private long activeSession;
    private long lastFixMs;
    private long lastAlertMs;
    private String lastAlert;
    private String ongoingStatus;
    private TextToSpeech voice;
    private boolean voiceReady;
    private int voiceGeneration;

    private final Runnable checkFix = new Runnable() {
        @Override
        public void run() {
            if (!isCurrentSession()) return;
            if (gps.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    && lastFixMs > 0 && SystemClock.elapsedRealtime() - lastFixMs > 15_000) {
                engine.resetMotion();
                lastFixMs = 0;
                update(GPS_WAIT, null, null, -1);
            }
            main.postDelayed(this, 5_000);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChanges =
            (preferences, key) -> {
                if (KEY_VOICE.equals(key)) configureVoice();
            };

    public static Snapshot snapshot() {
        return current;
    }

    public static boolean isRunning() {
        return current.running;
    }

    public static synchronized void start(Context context) {
        if (current.running) return;
        long session = ++currentSession;
        current = new Snapshot(true, null, GPS_WAIT, null, -1, -1, null);
        Intent intent = new Intent(context, WatchService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_SESSION, session);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException failure) {
            current = stopped("通知を開始できませんでした", null);
            throw failure;
        }
    }

    public static synchronized void stop(Context context) {
        if (!current.running) return;
        ++currentSession;
        current = stopped(IDLE_STATUS, current.lastAlert);
        context.stopService(new Intent(context, WatchService.class));
    }

    public static void sendTestAlert(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ensureChannels(manager);
        if (!canPostAlerts(manager)) return;
        manager.cancel(TEST_ID);
        manager.notify(TEST_ID, alertNotification(context, "オービス通知のテスト",
                "接近したときは、この通知音と振動でお知らせします。", 8_000));
    }

    public static void prepareChannels(Context context) {
        ensureChannels(context.getSystemService(NotificationManager.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long session = intent == null ? 0 : intent.getLongExtra(EXTRA_SESSION, 0);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (session == currentSession) stop(this);
            if (!current.running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (session == 0 || session != currentSession || !current.running) {
            if (!current.running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (activeSession == session) return START_NOT_STICKY;

        if (activeSession != 0) releaseResources();
        activeSession = session;
        engine = new AlertEngine();
        lastFixMs = 0;
        lastAlertMs = 0;
        lastAlert = null;
        gps = getSystemService(LocationManager.class);
        notifications = getSystemService(NotificationManager.class);
        ensureChannels(notifications);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(WATCH_ID, watchNotification(GPS_WAIT),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(WATCH_ID, watchNotification(GPS_WAIT));
            }
            ongoingStatus = GPS_WAIT;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                fail("正確な位置情報の許可が必要です");
                return START_NOT_STICKY;
            }
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(preferenceChanges);
            refreshCameraList();
            configureVoice();
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000, 0, this,
                    Looper.getMainLooper());
            if (!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                update(GPS_DISABLED, null, null, -1);
            }
            main.postDelayed(checkFix, 5_000);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Starting GPS monitoring failed", failure);
            fail("GPSによる監視を開始できませんでした");
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(Location fix) {
        if (!isCurrentSession()) return;
        long fixMs = fix.getElapsedRealtimeNanos() / 1_000_000;
        long ageMs = SystemClock.elapsedRealtime() - fixMs;
        if (fixMs <= lastFixMs || ageMs < 0 || ageMs > 8_000) return;
        lastFixMs = fixMs;
        if (!fix.hasAccuracy() || fix.getAccuracy() > 45 || !Float.isFinite(fix.getAccuracy())) {
            engine.resetMotion();
            update(GPS_IMPRECISE, fix, null, -1);
            return;
        }

        if (CameraRepository.revision() != cameraRevision) refreshCameraList();
        float speed = fix.hasSpeed() ? fix.getSpeed() : Float.NaN;
        boolean headingReliable = fix.hasBearing()
                && (!fix.hasBearingAccuracy() || fix.getBearingAccuracyDegrees() <= 35);
        float heading = headingReliable ? fix.getBearing() : Float.NaN;
        int distance = Math.max(250, Math.min(1_500, prefs.getInt(KEY_DISTANCE, DEFAULT_DISTANCE)));
        AlertEngine.Fix input = new AlertEngine.Fix(fix.getLatitude(), fix.getLongitude(),
                fix.getAccuracy(), speed, heading, fixMs);
        AlertEngine.Result result = engine.update(input, cameras, distance);
        if (result.alert != null) sendApproachAlert(result.alert);

        String status = SEARCHING_STATUS;
        if (Float.isFinite(result.speedMps) && result.speedMps < AlertEngine.MIN_MOVING_SPEED) {
            status = "走行待ち";
        } else if (!result.hasHeading) {
            status = "進行方向を取得中";
        } else if (!Float.isFinite(result.speedMps)) {
            status = "走行状況を取得中";
        }
        update(status, fix, result.nearby, Float.isFinite(result.speedMps) ? result.speedMps * 3.6f : -1);
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (isCurrentSession() && LocationManager.GPS_PROVIDER.equals(provider)) {
            update(GPS_WAIT, null, null, -1);
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (isCurrentSession() && LocationManager.GPS_PROVIDER.equals(provider)) {
            lastFixMs = 0;
            engine.resetMotion();
            update(GPS_DISABLED, null, null, -1);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Older Android versions still deliver this callback.
    }

    @Override
    public void onDestroy() {
        if (isCurrentSession()) current = stopped(IDLE_STATUS, lastAlert);
        releaseResources();
        super.onDestroy();
    }

    private void releaseResources() {
        main.removeCallbacksAndMessages(null);
        if (gps != null) gps.removeUpdates(this);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(preferenceChanges);
        closeVoice();
    }

    private boolean isCurrentSession() {
        return activeSession != 0 && activeSession == currentSession && current.running;
    }

    private void refreshCameraList() {
        cameraRevision = CameraRepository.revision();
        cameras = CameraRepository.load(this);
    }

    private void fail(String status) {
        if (isCurrentSession()) current = stopped(status, lastAlert);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void update(String status, Location location, AlertEngine.Candidate candidate,
            float speedKmh) {
        if (lastAlert != null && (location == null
                || SystemClock.elapsedRealtime() - lastAlertMs >= ALERT_DISPLAY_MS)) {
            lastAlert = null;
        }
        current = new Snapshot(true, location == null ? null : new Location(location), status,
                candidate == null ? null : candidate.point,
                candidate == null ? -1 : (int) Math.round(candidate.meters), speedKmh, lastAlert);
        if (!status.equals(ongoingStatus)) {
            ongoingStatus = status;
            notifications.notify(WATCH_ID, watchNotification(status));
        }
    }

    private void sendApproachAlert(AlertEngine.Candidate candidate) {
        int roundedMeters = Math.max(10, (int) Math.round(candidate.meters / 10) * 10);
        String name = candidate.point.name == null || candidate.point.name.isEmpty()
                ? "固定式オービス" : candidate.point.name;
        String distance = "前方 約" + roundedMeters + " m";
        lastAlertMs = SystemClock.elapsedRealtime();
        lastAlert = distance + " · " + name;
        if (!canPostAlerts(notifications)) return;
        String detail = distance + " · " + name;
        if (candidate.point.speed != null) detail += " · 制限 " + candidate.point.speed + " km/h";
        notifications.cancel(ALERT_ID);
        notifications.notify(ALERT_ID, alertNotification(this, "オービス接近", detail, ALERT_DISPLAY_MS));
        AudioManager audio = getSystemService(AudioManager.class);
        if (voiceReady && voice != null
                && notifications.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL
                && audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            voice.speak("前方、約 " + roundedMeters + " メートルにオービスがあります。",
                    TextToSpeech.QUEUE_FLUSH, null, "camera-" + candidate.point.id);
        }
    }

    private void configureVoice() {
        if (!isCurrentSession()) return;
        if (!prefs.getBoolean(KEY_VOICE, false)) {
            closeVoice();
            return;
        }
        if (voice != null) return;
        int generation = ++voiceGeneration;
        try {
            voice = new TextToSpeech(this, result -> main.post(() -> {
                if (generation == voiceGeneration) selectOfflineVoice(result);
            }));
        } catch (RuntimeException failure) {
            Log.w(TAG, "Initializing optional voice failed", failure);
        }
    }

    private void selectOfflineVoice(int status) {
        if (!isCurrentSession() || voice == null || status != TextToSpeech.SUCCESS) return;
        Set<Voice> available = voice.getVoices();
        if (available == null) return;
        Voice selected = null;
        for (Voice candidate : available) {
            Set<String> features = candidate.getFeatures();
            if (!Locale.JAPANESE.getLanguage().equals(candidate.getLocale().getLanguage())
                    || candidate.isNetworkConnectionRequired()
                    || (features != null
                            && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) continue;
            if (selected == null || candidate.getQuality() > selected.getQuality()) selected = candidate;
        }
        if (selected == null || voice.setVoice(selected) != TextToSpeech.SUCCESS) return;
        voice.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        voiceReady = true;
    }

    private void closeVoice() {
        ++voiceGeneration;
        voiceReady = false;
        if (voice == null) return;
        voice.stop();
        voice.shutdown();
        voice = null;
    }

    private Notification watchNotification(String status) {
        Intent stop = new Intent(this, WatchService.class).setAction(ACTION_STOP);
        stop.putExtra(EXTRA_SESSION, activeSession);
        PendingIntent stopIntent = PendingIntent.getService(this, 9, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder notification = new Notification.Builder(this, SERVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_camera)
                .setContentTitle("オービス監視中")
                .setContentText(status)
                .setContentIntent(openApp(this))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止", stopIntent).build());
        if (Build.VERSION.SDK_INT >= 31) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return notification.build();
    }

    private static Notification alertNotification(Context context, String title, String detail,
            long timeoutMs) {
        return new Notification.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_camera)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(openApp(context))
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setTimeoutAfter(timeoutMs)
                .build();
    }

    private static PendingIntent openApp(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static boolean canPostAlerts(NotificationManager manager) {
        NotificationChannel channel = manager.getNotificationChannel(ALERT_CHANNEL_ID);
        return manager.areNotificationsEnabled()
                && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static void ensureChannels(NotificationManager manager) {
        NotificationChannel status = new NotificationChannel(SERVICE_CHANNEL_ID,
                "走行中の監視状況", NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        manager.createNotificationChannel(status);
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL_ID,
                "オービス接近通知", NotificationManager.IMPORTANCE_HIGH);
        alerts.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build());
        alerts.enableVibration(true);
        alerts.setVibrationPattern(new long[] {0, 170, 100, 170});
        alerts.setShowBadge(false);
        manager.createNotificationChannel(alerts);
    }

    private static Snapshot stopped(String status, String lastAlert) {
        return new Snapshot(false, null, status, null, -1, -1, lastAlert);
    }
}
