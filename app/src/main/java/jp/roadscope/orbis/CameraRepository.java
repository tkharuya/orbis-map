package jp.roadscope.orbis;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

final class CameraRepository {
    private static final String TAG = "CameraRepository";
    private static final String CUSTOM_PREFS = "custom_cameras";
    private static final String CUSTOM_PREFIX = "custom:";
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private static List<CameraPoint> bundled;
    private static long revision;

    private CameraRepository() {
    }

    static synchronized List<CameraPoint> load(Context context) {
        if (bundled == null) {
            bundled = readBundled(context);
        }
        List<CameraPoint> custom = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(CUSTOM_PREFIX) && entry.getValue() instanceof String) {
                CameraPoint point = readCustom(entry.getKey(), (String) entry.getValue());
                if (point != null) {
                    custom.add(point);
                }
            }
        }
        custom.sort(Comparator.comparing((CameraPoint point) -> point.name).thenComparing(point -> point.id));
        List<CameraPoint> result = new ArrayList<>(bundled.size() + custom.size());
        result.addAll(bundled);
        result.addAll(custom);
        return Collections.unmodifiableList(result);
    }

    static synchronized long revision() {
        return revision;
    }

    static synchronized CameraPoint addCustom(Context context, double lat, double lon, String name,
            Float direction, Integer speed) {
        if (!validCoordinate(lat, lon)) {
            throw new IllegalArgumentException("Invalid camera coordinates");
        }
        Float normalizedDirection = direction == null ? null : validDirection(direction);
        if (direction != null && normalizedDirection == null || speed != null && validSpeed(speed) == null) {
            throw new IllegalArgumentException("Invalid camera direction or speed");
        }
        String id = CUSTOM_PREFIX + UUID.randomUUID();
        CameraPoint point = new CameraPoint(id, lat, lon, normalizedDirection, speed, name, true);
        JSONObject record = new JSONObject();
        try {
            record.put("lat", point.lat);
            record.put("lon", point.lon);
            record.put("name", point.name);
            record.put("direction", point.direction);
            record.put("speed", point.speed);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid camera record", e);
        }
        preferences(context).edit().putString(id, record.toString()).apply();
        revision++;
        return point;
    }

    static synchronized void removeCustom(Context context, String id) {
        if (id == null || !id.startsWith(CUSTOM_PREFIX)) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        if (preferences.contains(id)) {
            preferences.edit().remove(id).apply();
            revision++;
        }
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double latDelta = Math.toRadians(lat2 - lat1);
        double lonDelta = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(latDelta / 2);
        double sinLon = Math.sin(lonDelta / 2);
        double a = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * sinLon * sinLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double firstLat = Math.toRadians(lat1);
        double secondLat = Math.toRadians(lat2);
        double lonDelta = Math.toRadians(lon2 - lon1);
        double y = Math.sin(lonDelta) * Math.cos(secondLat);
        double x = Math.cos(firstLat) * Math.sin(secondLat)
                - Math.sin(firstLat) * Math.cos(secondLat) * Math.cos(lonDelta);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE);
    }

    private static List<CameraPoint> readBundled(Context context) {
        List<CameraPoint> points = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                context.getAssets().open("cameras.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                CameraPoint point = readBundledRow(line);
                if (point != null && ids.add(point.id)) {
                    points.add(point);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to read bundled camera data", e);
        }
        return points;
    }

    private static CameraPoint readBundledRow(String line) {
        String[] fields = line.split("\t", 6);
        if (fields.length < 3 || fields[0].isEmpty() || fields[0].startsWith(CUSTOM_PREFIX)) {
            return null;
        }
        try {
            double lat = Double.parseDouble(fields[1]);
            double lon = Double.parseDouble(fields[2]);
            if (!validCoordinate(lat, lon)) {
                return null;
            }
            Float direction = fields.length > 3 ? parseDirection(fields[3]) : null;
            Integer speed = fields.length > 4 ? parseSpeed(fields[4]) : null;
            String name = fields.length > 5 ? fields[5] : null;
            return new CameraPoint(fields[0], lat, lon, direction, speed, name, false);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static CameraPoint readCustom(String id, String value) {
        try {
            JSONObject record = new JSONObject(value);
            double lat = record.getDouble("lat");
            double lon = record.getDouble("lon");
            if (!validCoordinate(lat, lon)) {
                return null;
            }
            Float direction = validDirection((float) record.optDouble("direction", Double.NaN));
            Integer speed = validSpeed(record.optInt("speed", -1));
            return new CameraPoint(id, lat, lon, direction, speed, record.optString("name", ""), true);
        } catch (JSONException e) {
            Log.w(TAG, "Skipping unreadable custom camera", e);
            return null;
        }
    }

    private static boolean validCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static Float parseDirection(String value) {
        try {
            return validDirection(Float.parseFloat(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Float validDirection(float value) {
        if (!(value >= 0 && value <= 360)) {
            return null;
        }
        return value % 360;
    }

    private static Integer parseSpeed(String value) {
        try {
            return validSpeed(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer validSpeed(int value) {
        return value > 0 && value <= 300 ? value : null;
    }
}
