package jp.roadscope.orbis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = 0xff122c30;
    private static final int MUTED = 0xff466269;
    private static final int BORDER = 0xffd8e5e3;
    private static final int PALE = 0xffedf5f3;
    private static final int ACCENT = 0xff126654;
    private static final int WHITE = Color.WHITE;
    private static final int REQ_LOCATION = 11;
    private static final int REQ_NOTIFICATIONS = 12;
    private static final int REQ_TEST = 13;
    private static final double INITIAL_LAT = 34.0703;
    private static final double INITIAL_LON = 134.5548;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService searchWorker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            main.postDelayed(this, 750);
        }
    };

    private final LocationListener previewListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            if (WatchService.isRunning()) return;
            if (location.hasAccuracy() && location.getAccuracy() > 150) return;
            showLocation(location);
        }
        @Override public void onProviderEnabled(String provider) { renderStatus(); }
        @Override public void onProviderDisabled(String provider) { renderStatus(); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    private SlippyMapView map;
    private FrameLayout root;
    private LinearLayout header, footer, destination;
    private TextView watchButton, state, nearest, speed, destinationName;
    private LocationManager gps;
    private Location displayedFix;
    private List<CameraPoint> points;
    private SharedPreferences watchPrefs;
    private boolean previewing, visible, resumeStart;
    private long changingUntil;
    private Double destinationLat, destinationLon;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) resumeStart = saved.getBoolean("resumeStart");
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        gps = getSystemService(LocationManager.class);
        watchPrefs = getSharedPreferences(WatchService.PREFS, MODE_PRIVATE);
        WatchService.prepareChannels(this);
        points = CameraRepository.load(this);
        makeLayout();
        map.centerOn(34.085, INITIAL_LON, 12.3);
        if (saved != null && saved.containsKey("destinationLat")) {
            setDestination(saved.getDouble("destinationLat"), saved.getDouble("destinationLon"),
                    saved.getString("destinationName", "目的地"));
        }
        renderStatus();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("resumeStart", resumeStart);
        if (destinationLat != null && destinationLon != null) {
            out.putDouble("destinationLat", destinationLat);
            out.putDouble("destinationLon", destinationLon);
            out.putString("destinationName", destinationName.getText().toString());
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        refresh.run();
    }

    @Override protected void onStop() {
        visible = false;
        main.removeCallbacks(refresh);
        stopPreview();
        super.onStop();
    }

    @Override protected void onDestroy() {
        searchWorker.shutdownNow();
        if (map != null) map.close();
        super.onDestroy();
    }

    private void makeLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(PALE);
        map = new SlippyMapView(this, points);
        map.setId(R.id.main_map);
        map.setOnPointClick(this::showCamera);
        map.setOnLongPress(this::showMapPoint);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        header = column();
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(header, headerParams);

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(3), dp(4), dp(3));
        top.setBackground(surface(WHITE, 24, BORDER));
        top.setElevation(dp(5));
        header.addView(top, new LinearLayout.LayoutParams(-1, -2));

        TextView search = action("地名・住所を検索", 15, INK, WHITE, 20);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(15), 0, dp(4), 0);
        search.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        search.setOnClickListener(v -> showSearch());
        top.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));

        TextView list = action("一覧", 13, ACCENT, PALE, 16);
        list.setContentDescription("オービス一覧");
        list.setOnClickListener(v -> showCameraList());
        LinearLayout.LayoutParams topAction = new LinearLayout.LayoutParams(dp(54), dp(46));
        topAction.setMargins(0, 0, dp(4), 0);
        top.addView(list, topAction);

        TextView settings = action("設定", 13, ACCENT, PALE, 16);
        settings.setContentDescription("通知設定とデータ出典");
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(54), dp(46)));

        destination = row();
        destination.setGravity(Gravity.CENTER_VERTICAL);
        destination.setPadding(dp(12), dp(4), dp(4), dp(4));
        destination.setBackground(surface(WHITE, 16, BORDER));
        destination.setElevation(dp(3));
        LinearLayout.LayoutParams destinationParams = new LinearLayout.LayoutParams(-1, -2);
        destinationParams.topMargin = dp(8);
        header.addView(destination, destinationParams);
        destination.setVisibility(View.GONE);
        destinationName = label("", 14, INK, true);
        destinationName.setMaxLines(2);
        destinationName.setEllipsize(TextUtils.TruncateAt.END);
        destination.addView(destinationName, new LinearLayout.LayoutParams(0, -2, 1));
        TextView route = action("経路", 13, ACCENT, PALE, 12);
        route.setContentDescription("Google マップで経路を開く");
        route.setOnClickListener(v -> {
            if (destinationLat != null && destinationLon != null) {
                openDirections(destinationLat, destinationLon);
            }
        });
        destination.addView(route, new LinearLayout.LayoutParams(dp(54), dp(48)));
        TextView clear = action("×", 22, MUTED, WHITE, 12);
        clear.setContentDescription("検索結果のピンを消す");
        clear.setOnClickListener(v -> clearDestination());
        destination.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));

        footer = column();
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(footer, footerParams);
        LinearLayout controls = column();
        controls.setGravity(Gravity.END);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-2, -2);
        controlsParams.gravity = Gravity.END;
        footer.addView(controls, controlsParams);

        TextView plus = mapButton("+", "拡大");
        plus.setOnClickListener(v -> map.zoomBy(1));
        controls.addView(plus, controlParams());
        TextView minus = mapButton("−", "縮小");
        minus.setOnClickListener(v -> map.zoomBy(-1));
        controls.addView(minus, controlParams());
        TextView gpsButton = mapButton("◎", "現在地");
        gpsButton.setOnClickListener(v -> requestRecenter());
        controls.addView(gpsButton, controlParams());

        TextView attribution = action("© OpenStreetMap contributors", 11, MUTED, WHITE, 8);
        attribution.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        attribution.setPadding(dp(8), dp(3), dp(8), dp(3));
        attribution.setOnClickListener(v -> openLink("https://www.openstreetmap.org/copyright"));
        LinearLayout.LayoutParams attributionParams = new LinearLayout.LayoutParams(-2, -2);
        attributionParams.setMargins(0, dp(2), 0, dp(5));
        footer.addView(attribution, attributionParams);

        LinearLayout card = column();
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(surface(WHITE, 22, BORDER));
        card.setElevation(dp(6));
        footer.addView(card, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout cardTitle = row();
        cardTitle.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(cardTitle, new LinearLayout.LayoutParams(-1, -2));
        state = label("", 18, INK, true);
        cardTitle.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
        speed = label("", 13, ACCENT, true);
        speed.setBackground(surface(PALE, 12, null));
        speed.setPadding(dp(9), dp(4), dp(9), dp(4));
        cardTitle.addView(speed);

        nearest = label("", 13, MUTED, false);
        nearest.setPadding(0, dp(4), 0, dp(10));
        nearest.setMaxLines(2);
        nearest.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(nearest, new LinearLayout.LayoutParams(-1, -2));

        watchButton = action("通知を開始", 17, WHITE, ACCENT, 15);
        watchButton.setMinHeight(dp(52));
        watchButton.setOnClickListener(v -> toggleWatch());
        card.addView(watchButton, new LinearLayout.LayoutParams(-1, -2));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset, bottomInset, leftInset, rightInset;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topInset = safe.top;
                bottomInset = safe.bottom;
                leftInset = safe.left;
                rightInset = safe.right;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
                leftInset = insets.getSystemWindowInsetLeft();
                rightInset = insets.getSystemWindowInsetRight();
            }
            headerParams.setMargins(leftInset + dp(12), topInset + dp(8), rightInset + dp(12), 0);
            footerParams.setMargins(leftInset + dp(12), 0, rightInset + dp(12), bottomInset + dp(8));
            header.setLayoutParams(headerParams);
            footer.setLayoutParams(footerParams);
            return insets;
        });
        setContentView(root);
    }

    private void renderStatus() {
        boolean running = WatchService.isRunning();
        if (visible && !running && hasPreciseLocation()) startPreview();
        else stopPreview();
        WatchService.Snapshot snapshot = WatchService.snapshot();
        if (running && snapshot.location != null) showLocation(snapshot.location);
        if (displayedFix != null && SystemClock.elapsedRealtimeNanos()
                - displayedFix.getElapsedRealtimeNanos() > 30_000_000_000L) {
            displayedFix = null;
            map.setLocation(null);
        }
        watchButton.setEnabled(SystemClock.elapsedRealtime() >= changingUntil);
        String button = running ? "通知を停止" : "通知を開始";
        if (!button.contentEquals(watchButton.getText())) {
            watchButton.setText(button);
            watchButton.setTextColor(running ? ACCENT : WHITE);
            watchButton.setBackground(ripple(running ? PALE : ACCENT, 15));
        }
        if (running) {
            state.setText("通知中");
            if (!canPostAlerts()) {
                nearest.setText("端末の設定でオービス通知が無効です");
            } else if (snapshot.lastAlert != null && !snapshot.lastAlert.isEmpty()) {
                nearest.setText(snapshot.lastAlert);
            } else if (snapshot.nearby != null && snapshot.distanceMeters >= 0) {
                nearest.setText(cameraName(snapshot.nearby) + "まで " + distance(snapshot.distanceMeters));
            } else {
                nearest.setText(snapshot.status);
            }
        } else {
            state.setText("オービス通知");
            String details = distance(watchPrefs.getInt(WatchService.KEY_DISTANCE, 700))
                    + "手前から・登録地点の接近を通知";
            if (hasPreciseLocation() && !gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                details = "端末の位置情報がオフです";
            }
            nearest.setText(WatchService.IDLE_STATUS.equals(snapshot.status) ? details : snapshot.status);
        }
        float velocity = running ? snapshot.speedKmh : -1;
        speed.setVisibility(velocity >= 0 ? View.VISIBLE : View.GONE);
        if (velocity >= 0) speed.setText(Math.round(velocity) + " km/h");
    }

    private void showLocation(Location location) {
        if (displayedFix == null || location.getElapsedRealtimeNanos() > displayedFix.getElapsedRealtimeNanos()) {
            displayedFix = new Location(location);
            map.setLocation(displayedFix);
        }
    }

    private void startPreview() {
        if (previewing || !gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) return;
        try {
            Location recent = gps.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (recent != null && SystemClock.elapsedRealtimeNanos() - recent.getElapsedRealtimeNanos()
                    < 30_000_000_000L && (!recent.hasAccuracy() || recent.getAccuracy() <= 150)) {
                showLocation(recent);
            }
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500, 5,
                    previewListener, Looper.getMainLooper());
            previewing = true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            previewing = false;
        }
    }

    private void stopPreview() {
        if (!previewing) return;
        gps.removeUpdates(previewListener);
        previewing = false;
    }

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canPostAlerts() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(WatchService.ALERT_CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void toggleWatch() {
        if (SystemClock.elapsedRealtime() < changingUntil) return;
        if (WatchService.isRunning()) {
            WatchService.stop(this);
            changingUntil = SystemClock.elapsedRealtime() + 600;
            renderStatus();
        } else {
            if (!hasPreciseLocation()) {
                resumeStart = true;
                askLocation();
            } else {
                requestWatchStart();
            }
        }
    }

    private void requestWatchStart() {
        if (!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showLocationSettings();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        if (!canPostAlerts()) {
            showNotificationSettings();
            return;
        }
        try {
            WatchService.start(this);
            map.recenter();
            changingUntil = SystemClock.elapsedRealtime() + 900;
            renderStatus();
        } catch (IllegalStateException | SecurityException error) {
            toast("通知を開始できませんでした。位置情報の許可を確認してください。");
        }
    }

    private void requestRecenter() {
        if (!hasPreciseLocation()) {
            resumeStart = false;
            askLocation();
        } else if (!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showLocationSettings();
        } else if (!map.recenter()) {
            toast("GPSで現在地を測定中です");
        }
    }

    private void askLocation() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_LOCATION) {
            boolean start = resumeStart;
            resumeStart = false;
            if (hasPreciseLocation()) {
                if (start) requestWatchStart();
                else requestRecenter();
            } else {
                new AlertDialog.Builder(this).setTitle("正確な位置情報を許可")
                        .setMessage("現在地の表示とオービスとの距離の測定に使います。")
                        .setNegativeButton("閉じる", null)
                        .setPositiveButton("アプリの設定", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())))).show();
            }
        } else if (request == REQ_NOTIFICATIONS || request == REQ_TEST) {
            if (results.length != 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (request == REQ_TEST) requestTest();
                else requestWatchStart();
            } else showNotificationSettings();
        }
        renderStatus();
    }

    private void showLocationSettings() {
        new AlertDialog.Builder(this).setTitle("位置情報をオンにする")
                .setMessage("オービスとの距離をGPSで測定します。")
                .setNegativeButton("閉じる", null)
                .setPositiveButton("端末の設定", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();
    }

    private void showNotificationSettings() {
        new AlertDialog.Builder(this).setTitle("オービス通知を許可")
                .setMessage("接近時の音とバイブレーションは端末の通知設定に従います。")
                .setNegativeButton("閉じる", null)
                .setPositiveButton("通知設定", (dialog, which) -> {
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    if (manager.getNotificationChannel(WatchService.ALERT_CHANNEL_ID) != null
                            && manager.areNotificationsEnabled()) {
                        intent.setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                        intent.putExtra(Settings.EXTRA_CHANNEL_ID, WatchService.ALERT_CHANNEL_ID);
                    }
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(intent);
                }).show();
    }

    private void requestTest() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_TEST);
        } else if (!canPostAlerts()) showNotificationSettings();
        else WatchService.sendTestAlert(this);
    }

    private void showSettings() {
        LinearLayout form = dialogBody();
        TextView distanceLabel = label("通知距離", 15, INK, true);
        form.addView(distanceLabel);
        LinearLayout distances = row();
        distances.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(distances, new LinearLayout.LayoutParams(-1, -2));
        int[] meters = {300, 500, 700, 1000, 1500};
        List<TextView> distanceButtons = new ArrayList<>();
        for (int value : meters) {
            TextView button = action(distance(value, false), 12, ACCENT, PALE, 12);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(0, dp(8), dp(3), dp(9));
            distances.addView(button, params);
            distanceButtons.add(button);
            button.setOnClickListener(v -> {
                watchPrefs.edit().putInt(WatchService.KEY_DISTANCE, value).apply();
                highlightDistances(distanceButtons, meters, value);
                renderStatus();
            });
        }
        highlightDistances(distanceButtons, meters, watchPrefs.getInt(WatchService.KEY_DISTANCE, 700));
        Switch voice = new Switch(this);
        voice.setText("日本語の音声案内");
        voice.setTextColor(INK);
        voice.setMinHeight(dp(48));
        voice.setChecked(watchPrefs.getBoolean(WatchService.KEY_VOICE, false));
        voice.setOnCheckedChangeListener((button, checked) ->
                watchPrefs.edit().putBoolean(WatchService.KEY_VOICE, checked).apply());
        form.addView(voice, new LinearLayout.LayoutParams(-1, -2));
        TextView voiceHelp = label("端末にオフラインの日本語音声がある場合に読み上げます。", 13, MUTED, false);
        form.addView(voiceHelp);
        TextView test = action("通知音をテスト", 14, ACCENT, PALE, 13);
        test.setOnClickListener(v -> requestTest());
        addDialogRow(form, test);
        TextView sources = action("データの出典と更新日", 14, ACCENT, PALE, 13);
        sources.setOnClickListener(v -> showSources());
        addDialogRow(form, sources);
        new AlertDialog.Builder(this).setTitle("オービス通知設定")
                .setView(scroll(form)).setPositiveButton("閉じる", null).show();
    }

    private void highlightDistances(List<TextView> buttons, int[] meters, int selected) {
        for (int i = 0; i < meters.length; i++) {
            boolean active = meters[i] == selected;
            buttons.get(i).setSelected(active);
            buttons.get(i).setTextColor(active ? WHITE : ACCENT);
            buttons.get(i).setBackground(ripple(active ? ACCENT : PALE, 12));
            buttons.get(i).setContentDescription(meters[i] + "メートル");
        }
    }

    private void showSources() {
        int own = 0;
        for (CameraPoint point : points) if (point.custom) own++;
        new AlertDialog.Builder(this).setTitle("地図とオービスのデータ")
                .setMessage("地図とオービスの登録地点\n© OpenStreetMap contributors\n"
                        + "登録地点 " + (points.size() - own) + "件 / 自分の追加 " + own + "件\n"
                        + "OpenStreetMapで2026年11月22日照合\n\n"
                        + "オービス地点はアプリに同梱しています。固定式と一部の半固定式を収録しています。移動式や新設・撤去の情報は、登録データの範囲に限られます。"
                        + "\n\n未取得の地図と地名検索には通信が必要です。"
                        + "\nオービスの接近判定は端末内で行います。")
                .setNeutralButton("データ出典", (dialog, which) ->
                        openLink("https://www.openstreetmap.org/copyright"))
                .setPositiveButton("閉じる", null).show();
    }

    private void showCameraList() {
        List<CameraPoint> ordered = new ArrayList<>(points);
        Location fix = displayedFix;
        double lat = fix == null ? INITIAL_LAT : fix.getLatitude();
        double lon = fix == null ? INITIAL_LON : fix.getLongitude();
        ordered.sort(Comparator.comparingDouble(point ->
                CameraRepository.distanceMeters(lat, lon, point.lat, point.lon)));
        LinearLayout form = dialogBody();
        TextView hint = label(fix == null ? "徳島市を基準に近い順" : "現在地から近い順", 14, MUTED, false);
        form.addView(hint);
        if (ordered.isEmpty()) form.addView(label("登録地点がありません", 15, INK, true));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("オービス一覧")
                .setView(scroll(form)).setPositiveButton("閉じる", null).create();
        for (CameraPoint point : ordered) {
            String detail = distance(Math.round(CameraRepository.distanceMeters(lat, lon, point.lat, point.lon)))
                    + (point.custom ? "  ·  自分の登録" : "");
            TextView line = action(cameraName(point) + "\n" + detail, 14, INK, PALE, 12);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(12), dp(7), dp(12), dp(7));
            line.setOnClickListener(v -> {
                dialog.dismiss();
                map.centerOn(point.lat, point.lon, 15.5);
                showCamera(point);
            });
            addDialogRow(form, line);
        }
        dialog.show();
    }

    private void showCamera(CameraPoint point) {
        StringBuilder info = new StringBuilder(point.custom ? "自分で登録したオービス" : "OpenStreetMapの登録地点");
        if (point.speed != null) info.append("\n登録速度 ").append(point.speed).append(" km/h");
        if (point.direction != null) info.append("\n登録方向 ").append(cardinal(point.direction));
        info.append("\n").append(String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon));
        if (displayedFix != null) {
            info.append("\n現在地から ").append(distance(Math.round(CameraRepository.distanceMeters(
                    displayedFix.getLatitude(), displayedFix.getLongitude(), point.lat, point.lon))));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(cameraName(point))
                .setMessage(info.toString()).setPositiveButton("閉じる", null);
        if (point.custom) builder.setNegativeButton("削除", (dialog, which) ->
                new AlertDialog.Builder(this).setTitle("登録地点を削除")
                        .setMessage(cameraName(point)).setNegativeButton("キャンセル", null)
                        .setPositiveButton("削除", (confirmation, response) -> {
                            CameraRepository.removeCustom(this, point.id);
                            reloadPoints();
                        }).show());
        builder.show();
    }

    private void showMapPoint(double lat, double lon) {
        new AlertDialog.Builder(this).setTitle("地図上の地点")
                .setMessage(String.format(Locale.ROOT, "%.5f, %.5f", lat, lon))
                .setNeutralButton("閉じる", null)
                .setNegativeButton("Google マップで経路", (dialog, which) -> openDirections(lat, lon))
                .setPositiveButton("オービスを登録", (dialog, which) -> addCamera(lat, lon)).show();
    }

    private void addCamera(double lat, double lon) {
        LinearLayout form = dialogBody();
        EditText name = input("地点名（任意）");
        form.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("オービスを登録").setView(form)
                .setNegativeButton("キャンセル", null).setPositiveButton("登録", (dialog, which) -> {
                    CameraRepository.addCustom(this, lat, lon, name.getText().toString().trim(), null, null);
                    reloadPoints();
                    toast("地点を登録しました");
                }).show();
    }

    private void reloadPoints() {
        points = CameraRepository.load(this);
        map.setPoints(points);
    }

    private void showSearch() {
        LinearLayout form = dialogBody();
        EditText query = input("例　徳島駅");
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        form.addView(query, new LinearLayout.LayoutParams(-1, -2));
        TextView results = label("", 14, MUTED, false);
        form.addView(results, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout choices = column();
        form.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("地名・住所を検索")
                .setView(scroll(form)).setNegativeButton("閉じる", null)
                .setNeutralButton("Google マップ", null).setPositiveButton("検索", null).create();
        dialog.setOnShowListener(ignored -> {
            Runnable submit = () -> search(dialog, query, results, choices);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String value = query.getText().toString().trim();
                if (value.isEmpty()) {
                    query.setError("地名や住所を入力してください");
                    return;
                }
                openLink("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(value));
            });
            query.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                submit.run();
                return true;
            });
        });
        dialog.show();
        query.requestFocus();
        Window window = dialog.getWindow();
        if (window != null) window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void search(AlertDialog dialog, EditText query, TextView status, LinearLayout choices) {
        String text = query.getText().toString().trim();
        if (text.isEmpty()) {
            query.setError("地名や住所を入力してください");
            return;
        }
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (!button.isEnabled()) return;
        choices.removeAllViews();
        if (!Geocoder.isPresent()) {
            status.setText("この端末では地名検索を利用できません。Google マップで検索できます。");
            return;
        }
        button.setEnabled(false);
        status.setText("検索中…");
        Geocoder geocoder = new Geocoder(this, Locale.JAPAN);
        int[] request = {0};
        query.setTag(request);
        main.postDelayed(() -> {
            if (dialog.isShowing() && query.getTag() == request && request[0] == 0) {
                request[0] = 1;
                button.setEnabled(true);
                status.setText("応答がありません。もう一度検索できます。");
            }
        }, 12000);
        java.util.function.Consumer<List<Address>> complete = addresses -> runOnUiThread(() -> {
            if (!dialog.isShowing() || query.getTag() != request || request[0] != 0) return;
            request[0] = 1;
            button.setEnabled(true);
            showSearchResults(dialog, text, status, choices, addresses);
        });
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                geocoder.getFromLocationName(text, 5, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> addresses) { complete.accept(addresses); }
                    @Override public void onError(String errorMessage) { complete.accept(null); }
                });
            } catch (RuntimeException error) {
                complete.accept(null);
            }
        } else {
            searchWorker.execute(() -> {
                try {
                    complete.accept(geocoder.getFromLocationName(text, 5));
                } catch (IOException | RuntimeException error) {
                    complete.accept(null);
                }
            });
        }
    }

    private void showSearchResults(AlertDialog dialog, String query, TextView status, LinearLayout choices,
                                   List<Address> addresses) {
        if (addresses == null) {
            status.setText("検索できませんでした。通信を確認するかGoogle マップを開いてください。");
            return;
        }
        for (Address address : addresses) {
            if (!address.hasLatitude() || !address.hasLongitude()) continue;
            String name = address.getFeatureName();
            if (name == null || name.isEmpty()) name = query;
            String detail = address.getMaxAddressLineIndex() >= 0 ? address.getAddressLine(0) : null;
            String title = name;
            TextView result = action(detail == null || detail.isEmpty() ? title : title + "\n" + detail, 14, INK, PALE, 12);
            result.setGravity(Gravity.CENTER_VERTICAL);
            result.setPadding(dp(12), dp(7), dp(12), dp(7));
            result.setOnClickListener(v -> {
                dialog.dismiss();
                setDestination(address.getLatitude(), address.getLongitude(), title);
            });
            addDialogRow(choices, result);
        }
        status.setText(choices.getChildCount() == 0 ? "該当する地点が見つかりませんでした。" : "");
    }

    private void setDestination(double lat, double lon, String title) {
        destinationLat = lat;
        destinationLon = lon;
        destinationName.setText(title);
        destination.setVisibility(View.VISIBLE);
        map.setDestination(lat, lon);
        map.centerOn(lat, lon, 15.5);
    }

    private void clearDestination() {
        destinationLat = null;
        destinationLon = null;
        destination.setVisibility(View.GONE);
        map.setDestination(null, null);
    }

    private void openDirections(double lat, double lon) {
        String coordinates = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
        openLink("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination="
                + Uri.encode(coordinates));
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            toast("このリンクを開けるアプリがありません");
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private String cameraName(CameraPoint point) {
        return point.name == null || point.name.trim().isEmpty()
                ? (point.custom ? "自分の登録地点" : "固定式オービス") : point.name;
    }

    private static String cardinal(float direction) {
        String[] names = {"北", "北東", "東", "南東", "南", "南西", "西", "北西"};
        return names[Math.floorMod(Math.round(direction / 45f), names.length)] + "向き";
    }

    private static String distance(long meters) { return distance(meters, true); }

    private static String distance(long meters, boolean spaced) {
        String space = spaced ? " " : "";
        if (meters < 1000) return meters + space + "m";
        double km = meters / 1000.0;
        return String.format(Locale.JAPAN, km >= 10 || km == Math.rint(km) ? "%.0f" : "%.1f", km)
                + space + "km";
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout dialogBody() {
        LinearLayout body = column();
        body.setPadding(dp(21), dp(10), dp(21), dp(12));
        return body;
    }

    private ScrollView scroll(View view) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void addDialogRow(LinearLayout form, TextView line) {
        line.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(7);
        form.addView(line, params);
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private TextView mapButton(String text, String description) {
        TextView button = action(text, 27, INK, WHITE, 16);
        button.setElevation(dp(4));
        button.setContentDescription(description);
        return button;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(49), dp(49));
        params.bottomMargin = dp(6);
        return params;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView action(String text, float size, int color, int fill, int radius) {
        TextView view = label(text, size, color, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(ripple(fill, radius));
        view.setFocusable(true);
        return view;
    }

    private GradientDrawable surface(int fill, int radius, Integer stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != null) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private RippleDrawable ripple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22126654), surface(fill, radius, null), null);
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
