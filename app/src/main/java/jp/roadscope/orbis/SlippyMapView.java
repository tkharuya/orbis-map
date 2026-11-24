package jp.roadscope.orbis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A slippy map with native gestures, local camera markers and current location. */
final class SlippyMapView extends View {
    interface CoordinateListener {
        void onCoordinate(double lat, double lon);
    }

    private static final int EMPTY = Color.rgb(232, 239, 237);
    private static final int GRID = Color.rgb(221, 231, 227);
    private static final int INK = Color.rgb(18, 44, 48);
    private static final int CAMERA = Color.rgb(208, 75, 43);
    private static final int CUSTOM = Color.rgb(18, 102, 84);
    private static final int POSITION = Color.rgb(39, 104, 210);
    private static final int DESTINATION = Color.rgb(36, 75, 157);
    private static final double MIN_ZOOM = 3.0;
    private static final double MAX_ZOOM = 19.0;
    private static final double MERCATOR_LIMIT = 85.05112878;
    private static final double EARTH_CIRCUMFERENCE = 40_075_017.0;
    private static final int TILE_SIZE = 256;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rasterPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF tileRect = new RectF();
    private final RectF body = new RectF();
    private final Path path = new Path();
    private final TileStore tiles;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGesture;
    private final List<Marker> drawnMarkers = new ArrayList<>();

    private List<CameraPoint> points;
    private Consumer<CameraPoint> pointClick;
    private CoordinateListener longPress;
    private Location location;
    private Double destinationX;
    private Double destinationY;
    private double centerX = mercatorX(134.5548);
    private double centerY = mercatorY(34.0703);
    private double zoom = 12.0;
    private boolean following = true;
    private boolean multiTouch;
    private boolean closed;

    SlippyMapView(Context context, List<CameraPoint> points) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        this.points = new ArrayList<>(points);
        tiles = new TileStore(context, this::postInvalidateOnAnimation);
        setClickable(true);
        setFocusable(true);
        setContentDescription("地図。ドラッグで移動、ピンチで拡大縮小、長押しで地点を選択");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
                following = false;
                centerX = wrap(centerX + dx / world());
                centerY = constrainY(centerY + dy / world());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                following = false;
                zoomAt(zoom + 1.0, event.getX(), event.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                performClick();
                selectMarker(event.getX(), event.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent event) {
                if (longPress == null || multiTouch) return;
                double x = wrap(centerX + (event.getX() - getWidth() / 2.0) / world());
                double y = constrainY(centerY + (event.getY() - getHeight() / 2.0) / world());
                longPress.onCoordinate(latitude(y), longitude(x));
            }
        });
        scaleGesture = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float previousFocusX;
            private float previousFocusY;

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                following = false;
                previousFocusX = detector.getFocusX();
                previousFocusY = detector.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (!Float.isFinite(factor) || factor <= 0) return false;
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                double oldWorld = world();
                double anchorX = centerX + (previousFocusX - getWidth() / 2.0) / oldWorld;
                double anchorY = centerY + (previousFocusY - getHeight() / 2.0) / oldWorld;
                zoom = constrainedZoom(zoom + Math.log(factor) / Math.log(2));
                centerX = wrap(anchorX - (focusX - getWidth() / 2.0) / world());
                centerY = constrainY(anchorY - (focusY - getHeight() / 2.0) / world());
                previousFocusX = focusX;
                previousFocusY = focusY;
                postInvalidateOnAnimation();
                return true;
            }
        });
        scaleGesture.setQuickScaleEnabled(false);
        scaleGesture.setStylusScaleEnabled(false);
    }

    void setPoints(List<CameraPoint> points) {
        this.points = new ArrayList<>(points);
        postInvalidateOnAnimation();
    }

    void setOnPointClick(Consumer<CameraPoint> listener) {
        pointClick = listener;
    }

    void setOnLongPress(CoordinateListener listener) {
        longPress = listener;
    }

    void setLocation(Location update) {
        if (update != null && (!Double.isFinite(update.getLatitude()) || !Double.isFinite(update.getLongitude()))) {
            return;
        }
        location = update == null ? null : new Location(update);
        if (following && location != null) followLocation();
        postInvalidateOnAnimation();
    }

    void setDestination(Double lat, Double lon) {
        if (lat == null || lon == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            destinationX = null;
            destinationY = null;
        } else {
            destinationX = wrap(mercatorX(lon));
            destinationY = mercatorY(lat);
        }
        postInvalidateOnAnimation();
    }

    void centerOn(double lat, double lon, double zoom) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Double.isFinite(zoom)) return;
        following = false;
        setCenter(lat, lon);
        this.zoom = constrainedZoom(zoom);
        postInvalidateOnAnimation();
    }

    boolean recenter() {
        setFollowing(true);
        return location != null;
    }

    boolean isFollowing() {
        return following;
    }

    void setFollowing(boolean following) {
        this.following = following;
        if (following && location != null) {
            followLocation();
            postInvalidateOnAnimation();
        }
    }

    void zoomBy(double delta) {
        if (Double.isFinite(delta)) zoomAt(zoom + delta, getWidth() / 2f, getHeight() / 2f);
    }

    void close() {
        if (closed) return;
        closed = true;
        tiles.close();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putParcelable("view", super.onSaveInstanceState());
        state.putDouble("centerX", centerX);
        state.putDouble("centerY", centerY);
        state.putDouble("zoom", zoom);
        state.putBoolean("following", following);
        if (destinationX != null && destinationY != null) {
            state.putDouble("destinationX", destinationX);
            state.putDouble("destinationY", destinationY);
        }
        return state;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onRestoreInstanceState(Parcelable parcelable) {
        if (!(parcelable instanceof Bundle)) {
            super.onRestoreInstanceState(parcelable);
            return;
        }
        Bundle state = (Bundle) parcelable;
        super.onRestoreInstanceState(state.getParcelable("view"));
        centerX = wrap(state.getDouble("centerX", centerX));
        centerY = constrainY(state.getDouble("centerY", centerY));
        zoom = constrainedZoom(state.getDouble("zoom", zoom));
        following = state.getBoolean("following", following);
        if (state.containsKey("destinationX") && state.containsKey("destinationY")) {
            destinationX = wrap(state.getDouble("destinationX"));
            destinationY = constrainY(state.getDouble("destinationY"));
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (!closed) tiles.show(Collections.emptyList());
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (tiles == null || closed) return;
        if (visibility == VISIBLE) {
            postInvalidateOnAnimation();
        } else {
            tiles.show(Collections.emptyList());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (closed) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) multiTouch = false;
        if (action == MotionEvent.ACTION_POINTER_DOWN && !multiTouch) {
            multiTouch = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        scaleGesture.onTouchEvent(event);
        if (!multiTouch) gestures.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(EMPTY);
        if (closed || getWidth() == 0 || getHeight() == 0) return;

        drawTiles(canvas);
        drawAccuracy(canvas);
        drawCameraMarkers(canvas);
        drawDestination(canvas);
        drawLocation(canvas);
    }

    private void drawTiles(Canvas canvas) {
        int level = (int) Math.floor(zoom);
        int count = 1 << level;
        double size = world() / count;
        double left = centerX * count - getWidth() / (2.0 * size);
        double top = centerY * count - getHeight() / (2.0 * size);
        int minX = (int) Math.floor(left);
        int minY = Math.max(0, (int) Math.floor(top));
        int maxX = (int) Math.ceil(left + getWidth() / size) - 1;
        int maxY = Math.min(count - 1, (int) Math.ceil(top + getHeight() / size) - 1);
        List<TileStore.Key> visible = new ArrayList<>();
        boolean hasRaster = false;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                TileStore.Key key = new TileStore.Key(level, Math.floorMod(x, count), y);
                if (!visible.contains(key)) visible.add(key);
                tileRect.set((float) ((x - left) * size), (float) ((y - top) * size),
                        (float) ((x + 1 - left) * size), (float) ((y + 1 - top) * size));
                Bitmap bitmap = tiles.get(key);
                if (bitmap == null) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1);
                    paint.setColor(GRID);
                    canvas.drawRect(tileRect, paint);
                    paint.setStyle(Paint.Style.FILL);
                } else {
                    canvas.drawBitmap(bitmap, null, tileRect, rasterPaint);
                    hasRaster = true;
                }
            }
        }
        double xCenter = centerX * count;
        double yCenter = centerY * count;
        visible.sort(Comparator.comparingDouble(key -> {
            double xOffset = (key.x + 0.5) / count - xCenter / count;
            double dx = shortestX(xOffset) * count;
            double dy = key.y + 0.5 - yCenter;
            return dx * dx + dy * dy;
        }));
        if (getWindowVisibility() == VISIBLE) tiles.show(visible);
        if (!hasRaster) drawTileStatus(canvas);
    }

    private void drawTileStatus(Canvas canvas) {
        String message = tiles.visibleTilesUnavailable() ? "地図を取得できません。通信を確認" : "地図を取得中";
        paint.setTextSize(dp(12));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(0xBFFFFFFF);
        float width = paint.measureText(message) + dp(24);
        float x = getWidth() / 2f;
        float y = getHeight() * 0.41f;
        body.set(x - width / 2, y - dp(14), x + width / 2, y + dp(14));
        canvas.drawRoundRect(body, dp(14), dp(14), paint);
        paint.setColor(INK);
        canvas.drawText(message, x, y + dp(4), paint);
    }

    private void drawCameraMarkers(Canvas canvas) {
        drawnMarkers.clear();
        int width = getWidth();
        int height = getHeight();
        boolean cluster = zoom < 12.0;
        Map<Long, Marker> groups = cluster ? new LinkedHashMap<>() : null;
        double cell = dp(52.0f);
        for (CameraPoint point : points) {
            double projectedX = mercatorX(point.lon);
            double projectedY = mercatorY(point.lat);
            float x = screenX(projectedX);
            float y = screenY(projectedY);
            float pad = dp(26);
            if (x < -pad || x > width + pad || y < -pad || y > height + pad) continue;
            if (groups == null) {
                drawnMarkers.add(new Marker(point, x, y));
                continue;
            }
            long col = (long) Math.floor((x - width / 2.0 + centerX * world()) / cell);
            long row = (long) Math.floor(projectedY * world() / cell);
            long key = col << 32 | row & 0xffffffffL;
            Marker group = groups.get(key);
            if (group == null) {
                groups.put(key, new Marker(point, x, y));
            } else {
                group.add(x, y);
            }
        }
        if (groups != null) drawnMarkers.addAll(groups.values());
        for (Marker marker : drawnMarkers) {
            if (marker.count == 1) {
                drawCamera(canvas, marker.x, marker.y, marker.point.custom ? CUSTOM : CAMERA);
            } else {
                drawCluster(canvas, marker);
            }
        }
    }

    private void drawCamera(Canvas canvas, float x, float y, int color) {
        float cy = y - dp(10);
        paint.setColor(0x35000000);
        canvas.drawCircle(x + dp(0.5f), cy + dp(1.7f), dp(14), paint);
        path.reset();
        path.moveTo(x - dp(7), y - dp(1));
        path.lineTo(x, y + dp(5));
        path.lineTo(x + dp(7), y - dp(1));
        path.close();
        paint.setColor(Color.WHITE);
        canvas.drawPath(path, paint);
        canvas.drawCircle(x, cy, dp(14), paint);
        paint.setColor(color);
        canvas.drawCircle(x, cy, dp(12), paint);

        paint.setColor(Color.WHITE);
        body.set(x - dp(6.6f), cy - dp(3.8f), x + dp(6.6f), cy + dp(4.9f));
        canvas.drawRoundRect(body, dp(1.5f), dp(1.5f), paint);
        body.set(x - dp(2.8f), cy - dp(5.5f), x + dp(2.8f), cy - dp(2.7f));
        canvas.drawRoundRect(body, dp(0.8f), dp(0.8f), paint);
        paint.setColor(color);
        canvas.drawCircle(x, cy + dp(0.5f), dp(2.8f), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, cy + dp(0.5f), dp(1.4f), paint);
    }

    private void drawCluster(Canvas canvas, Marker marker) {
        paint.setColor(Color.WHITE);
        canvas.drawCircle(marker.x, marker.y, dp(15), paint);
        paint.setColor(CAMERA);
        canvas.drawCircle(marker.x, marker.y, dp(13), paint);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(dp(marker.count < 100 ? 12 : 10));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Integer.toString(marker.count), marker.x, marker.y + dp(4), paint);
    }

    private void drawAccuracy(Canvas canvas) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() <= 0) return;
        float x = screenX(mercatorX(location.getLongitude()));
        float y = screenY(mercatorY(location.getLatitude()));
        double scale = Math.cos(Math.toRadians(location.getLatitude())) * EARTH_CIRCUMFERENCE / world();
        float radius = (float) Math.min(location.getAccuracy() / scale, Math.hypot(getWidth(), getHeight()));
        if (radius < dp(11) || !Float.isFinite(radius)) return;
        paint.setColor(0x1F2768D2);
        canvas.drawCircle(x, y, radius, paint);
        paint.setColor(0x602768D2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLocation(Canvas canvas) {
        if (location == null) return;
        float x = screenX(mercatorX(location.getLongitude()));
        float y = screenY(mercatorY(location.getLatitude()));
        if (location.hasBearing() && (!location.hasSpeed() || location.getSpeed() >= 1.5f)) {
            int save = canvas.save();
            canvas.rotate(location.getBearing(), x, y);
            path.reset();
            path.moveTo(x, y - dp(18));
            path.lineTo(x - dp(6), y - dp(6));
            path.lineTo(x + dp(6), y - dp(6));
            path.close();
            paint.setColor(POSITION);
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }
        paint.setColor(0x35000000);
        canvas.drawCircle(x, y + dp(1), dp(10.8f), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(9.7f), paint);
        paint.setColor(POSITION);
        canvas.drawCircle(x, y, dp(7.1f), paint);
    }

    private void drawDestination(Canvas canvas) {
        if (destinationX == null || destinationY == null) return;
        float x = screenX(destinationX);
        float y = screenY(destinationY);
        paint.setColor(0x35000000);
        canvas.drawCircle(x, y + dp(1), dp(12), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(11), paint);
        paint.setColor(DESTINATION);
        canvas.drawCircle(x, y, dp(9), paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(3.5f), paint);
    }

    private void selectMarker(float x, float y) {
        Marker nearest = null;
        double best = dp(23);
        for (Marker marker : drawnMarkers) {
            float tapY = marker.count == 1 ? marker.y - dp(8) : marker.y;
            double distance = Math.hypot(marker.x - x, tapY - y);
            if (distance < best) {
                best = distance;
                nearest = marker;
            }
        }
        if (nearest == null) return;
        if (nearest.count == 1) {
            if (pointClick != null) pointClick.accept(nearest.point);
        } else {
            double xTarget = wrap(centerX + (nearest.x - getWidth() / 2.0) / world());
            double yTarget = constrainY(centerY + (nearest.y - getHeight() / 2.0) / world());
            centerOn(latitude(yTarget), longitude(xTarget), Math.min(MAX_ZOOM, zoom + 2));
        }
    }

    private void zoomAt(double next, float x, float y) {
        double oldWorld = world();
        double offsetX = x - getWidth() / 2.0;
        double offsetY = y - getHeight() / 2.0;
        double anchorX = centerX + offsetX / oldWorld;
        double anchorY = centerY + offsetY / oldWorld;
        zoom = constrainedZoom(next);
        centerX = wrap(anchorX - offsetX / world());
        centerY = constrainY(anchorY - offsetY / world());
        postInvalidateOnAnimation();
    }

    private void setCenter(double lat, double lon) {
        centerX = wrap(mercatorX(lon));
        centerY = mercatorY(lat);
    }

    private void followLocation() {
        setCenter(location.getLatitude(), location.getLongitude());
    }

    private double world() {
        return TILE_SIZE * density * Math.pow(2, zoom);
    }

    private float screenX(double x) {
        return (float) (getWidth() / 2.0 + shortestX(x - centerX) * world());
    }

    private float screenY(double y) {
        return (float) (getHeight() / 2.0 + (y - centerY) * world());
    }

    private float dp(float value) {
        return value * density;
    }

    private static double shortestX(double offset) {
        return offset - Math.floor(offset + 0.5);
    }

    private static double wrap(double x) {
        return x - Math.floor(x);
    }

    private static double constrainY(double y) {
        return Math.max(0, Math.min(1, y));
    }

    private static double constrainedZoom(double zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    private static double mercatorX(double longitude) {
        return (longitude + 180.0) / 360.0;
    }

    private static double mercatorY(double latitude) {
        double angle = Math.toRadians(Math.max(-MERCATOR_LIMIT, Math.min(MERCATOR_LIMIT, latitude)));
        return constrainY((1.0 - Math.log(Math.tan(angle) + 1.0 / Math.cos(angle)) / Math.PI) / 2.0);
    }

    private static double longitude(double x) {
        return x * 360.0 - 180.0;
    }

    private static double latitude(double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y))));
    }

    private static final class Marker {
        final CameraPoint point;
        float x;
        float y;
        int count = 1;

        Marker(CameraPoint point, float x, float y) {
            this.point = point;
            this.x = x;
            this.y = y;
        }

        void add(float x, float y) {
            count++;
            this.x += (x - this.x) / count;
            this.y += (y - this.y) / count;
        }
    }
}
