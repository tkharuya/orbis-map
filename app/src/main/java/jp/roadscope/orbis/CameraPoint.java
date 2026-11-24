package jp.roadscope.orbis;

final class CameraPoint {
    final String id;
    final String name;
    final double lat;
    final double lon;
    // Compass heading of traffic monitored by the camera, clockwise from north.
    final Float direction;
    final Integer speed;
    final boolean custom;

    CameraPoint(String id, double lat, double lon, Float direction, Integer speed, String name,
            boolean custom) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? "オービス" : name.trim();
        this.lat = lat;
        this.lon = lon;
        this.direction = direction;
        this.speed = speed;
        this.custom = custom;
    }
}
