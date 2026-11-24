package jp.roadscope.orbis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AlertEngine {
    static final float MIN_MOVING_SPEED = 2.25f;
    private static final long MAX_BASELINE_MS = 18_000;
    private static final long MIN_REPEAT_MS = 120_000;
    private static final long MIN_ALERT_GAP_MS = 12_000;
    private static final int REARM_MARGIN_METERS = 300;
    private static final int LOOK_AHEAD_METERS = 3_000;

    private final Map<String, AlertHistory> alertHistory = new HashMap<>();
    private Fix baseline;
    private Fix previous;
    private long lastAlertMs = Long.MIN_VALUE;

    static final class Fix {
        final double lat;
        final double lon;
        final float accuracyMeters;
        final float speedMps;
        final float headingDegrees;
        final long timeMs;

        Fix(double lat, double lon, float accuracyMeters, float speedMps,
                float headingDegrees, long timeMs) {
            this.lat = lat;
            this.lon = lon;
            this.accuracyMeters = accuracyMeters;
            this.speedMps = speedMps;
            this.headingDegrees = headingDegrees;
            this.timeMs = timeMs;
        }
    }

    static final class Candidate {
        final CameraPoint point;
        final double meters;

        Candidate(CameraPoint point, double meters) {
            this.point = point;
            this.meters = meters;
        }
    }

    static final class Result {
        final Candidate nearby;
        final Candidate alert;
        final float speedMps;
        final boolean hasHeading;

        Result(Candidate nearby, Candidate alert, float speedMps, boolean hasHeading) {
            this.nearby = nearby;
            this.alert = alert;
            this.speedMps = speedMps;
            this.hasHeading = hasHeading;
        }
    }

    private static final class AlertHistory {
        final long issuedMs;
        boolean leftArea;

        AlertHistory(long issuedMs) {
            this.issuedMs = issuedMs;
        }
    }

    void resetMotion() {
        baseline = null;
        previous = null;
    }

    Result update(Fix fix, List<CameraPoint> cameras, int alertDistance) {
        if (previous != null && fix.timeMs <= previous.timeMs) {
            return new Result(null, null, fix.speedMps, Float.isFinite(fix.headingDegrees));
        }

        float speedMps = fix.speedMps;
        double heading = fix.headingDegrees;
        if (baseline == null || fix.timeMs - baseline.timeMs > MAX_BASELINE_MS) {
            baseline = fix;
        }
        double movement = distance(baseline, fix.lat, fix.lon);
        double movementFloor = Math.max(18, Math.hypot(baseline.accuracyMeters, fix.accuracyMeters));
        if (movement >= movementFloor) {
            long elapsedMs = Math.max(1, fix.timeMs - baseline.timeMs);
            if (!Float.isFinite(speedMps)) speedMps = (float) (movement * 1000 / elapsedMs);
            // For a slowly accumulated displacement, the latest vehicle heading is unknown.
            if (!Double.isFinite(heading) && elapsedMs <= 12_000) {
                heading = CameraRepository.bearingDegrees(baseline.lat, baseline.lon, fix.lat, fix.lon);
            }
            baseline = fix;
        }

        boolean headingKnown = Double.isFinite(heading);
        boolean moving = Float.isFinite(speedMps) && speedMps >= MIN_MOVING_SPEED;
        double lastMovement = previous != null && fix.timeMs - previous.timeMs <= 8_000
                ? distance(previous, fix.lat, fix.lon) : 0;
        Candidate nearby = null;
        Candidate alert = null;
        for (CameraPoint camera : cameras) {
            double meters = distance(fix, camera.lat, camera.lon);
            AlertHistory history = alertHistory.get(camera.id);
            if (history != null) {
                if (meters >= alertDistance + REARM_MARGIN_METERS) history.leftArea = true;
                if (history.leftArea && fix.timeMs - history.issuedMs >= MIN_REPEAT_MS) {
                    alertHistory.remove(camera.id);
                    history = null;
                }
            }
            if (!moving || !headingKnown || meters < 12 || meters > LOOK_AHEAD_METERS) continue;
            if (!aheadOfVehicle(fix, camera, meters, heading)) continue;
            if (lastMovement > 10 && distance(previous, camera.lat, camera.lon) + 10 < meters) continue;
            if (nearby == null || meters < nearby.meters) nearby = new Candidate(camera, meters);
            if (history == null && meters <= alertDistance && (alert == null || meters < alert.meters)) {
                alert = new Candidate(camera, meters);
            }
        }
        previous = fix;
        if (alert != null && lastAlertMs != Long.MIN_VALUE && fix.timeMs - lastAlertMs < MIN_ALERT_GAP_MS) {
            alert = null;
        }
        if (alert != null) {
            alertHistory.put(alert.point.id, new AlertHistory(fix.timeMs));
            lastAlertMs = fix.timeMs;
        }
        return new Result(nearby, alert, speedMps, headingKnown);
    }

    private static boolean aheadOfVehicle(Fix fix, CameraPoint camera, double meters, double heading) {
        double toCamera = CameraRepository.bearingDegrees(fix.lat, fix.lon, camera.lat, camera.lon);
        double headingOffset = headingDifference(heading, toCamera);
        // A heading cone alone also covers nearby parallel roads. Bound the lateral distance.
        double lateralMeters = meters * Math.sin(Math.toRadians(headingOffset));
        double corridorMeters = Math.min(135, 45 + fix.accuracyMeters + meters * 0.05);
        if (headingOffset > 43 || lateralMeters > corridorMeters) return false;
        return camera.direction == null || headingDifference(heading, camera.direction) <= 60;
    }

    private static double distance(Fix from, double lat, double lon) {
        return CameraRepository.distanceMeters(from.lat, from.lon, lat, lon);
    }

    private static double headingDifference(double first, double second) {
        return Math.abs((first - second + 540) % 360 - 180);
    }
}
