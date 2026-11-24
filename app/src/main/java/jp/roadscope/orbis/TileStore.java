package jp.roadscope.orbis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads only currently visible OpenStreetMap tiles. All memory state lives on the UI thread. */
final class TileStore {
    private static final int MAX_TILE_BYTES = 512 * 1024;
    private static final int MEMORY_BYTES = 16 * 1024 * 1024;
    private static final long DISK_BYTES = 64L * 1024 * 1024;
    private static final long FALLBACK_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_MS = 45_000;
    private static final long REVALIDATE_BACKOFF_MS = 300_000;
    private static final int WORKERS = 3;
    private static final int CACHE_FORMAT = 2;
    private static final Object DISK_LOCK = new Object();
    private static final String USER_AGENT = "RoadScopeOrbis/1.0 (Android; jp.roadscope.orbis)";

    static final class Key {
        final int z;
        final int x;
        final int y;

        Key(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }

        String fileName() {
            return z + "-" + x + "-" + y + ".tile";
        }

        String url() {
            return "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return z == other.z && x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(z, x, y);
        }
    }

    private static final class Entry {
        final byte[] bytes;
        final long expires;
        final long staleWhileRevalidate;
        final long staleIfError;
        final long revalidateAt;
        final String etag;
        final String modified;

        Entry(byte[] bytes, long expires, long staleWhileRevalidate, long staleIfError,
                long revalidateAt, String etag, String modified) {
            this.bytes = bytes;
            this.expires = expires;
            this.staleWhileRevalidate = staleWhileRevalidate;
            this.staleIfError = staleIfError;
            this.revalidateAt = revalidateAt;
            this.etag = etag;
            this.modified = modified;
        }
    }

    private static final class Tile {
        final Bitmap bitmap;
        final long showUntil;
        final long retryAfter;
        final boolean transientResponse;

        Tile(Bitmap bitmap, long showUntil, long retryAfter, boolean transientResponse) {
            this.bitmap = bitmap;
            this.showUntil = showUntil;
            this.retryAfter = retryAfter;
            this.transientResponse = transientResponse;
        }
    }

    private final File directory;
    private final Runnable onTileChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    private final Runnable retry = this::schedule;
    private final Set<Key> loading = new HashSet<>();
    private final LruCache<Key, Tile> memory = new LruCache<Key, Tile>(MEMORY_BYTES) {
        @Override
        protected int sizeOf(Key key, Tile tile) {
            return tile.bitmap == null ? 1024 : tile.bitmap.getAllocationByteCount();
        }
    };

    private volatile Set<Key> wanted = Collections.emptySet();
    private volatile long pauseNetworkUntil;
    private List<Key> visible = Collections.emptyList();
    private int writesSinceTrim;
    private volatile boolean closed;

    TileStore(Context context, Runnable onTileChanged) {
        directory = new File(context.getCacheDir(), "osm-tiles-v2");
        this.onTileChanged = onTileChanged;
    }

    Bitmap get(Key key) {
        Tile tile = memory.get(key);
        return tile != null && tile.showUntil >= System.currentTimeMillis() ? tile.bitmap : null;
    }

    boolean visibleTilesUnavailable() {
        if (visible.isEmpty()) return false;
        for (Key key : visible) {
            Tile tile = memory.get(key);
            if (tile == null || tile.bitmap != null) return false;
        }
        return true;
    }

    void show(List<Key> keys) {
        if (closed) return;
        if (!visible.equals(keys)) {
            visible = new ArrayList<>(keys);
            wanted = new HashSet<>(keys);
            for (java.util.Map.Entry<Key, Tile> entry : memory.snapshot().entrySet()) {
                if (entry.getValue().transientResponse && !wanted.contains(entry.getKey())) {
                    memory.remove(entry.getKey());
                }
            }
        }
        schedule();
    }

    void close() {
        closed = true;
        wanted = Collections.emptySet();
        visible = Collections.emptyList();
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        memory.evictAll();
    }

    private void schedule() {
        main.removeCallbacks(retry);
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Key key : visible) {
            if (loading.size() >= WORKERS) return;
            Tile tile = memory.get(key);
            if (loading.contains(key)) continue;
            if (tile != null && now < tile.retryAfter) {
                next = Math.min(next, tile.retryAfter);
                continue;
            }
            loading.add(key);
            workers.execute(() -> {
                Tile loaded = load(key);
                main.post(() -> {
                    if (closed) return;
                    loading.remove(key);
                    if (loaded != null && (!loaded.transientResponse || wanted.contains(key))) {
                        memory.put(key, loaded);
                        if (wanted.contains(key)) onTileChanged.run();
                    }
                    schedule();
                });
            });
        }
        if (next != Long.MAX_VALUE) main.postDelayed(retry, Math.max(50, next - now));
    }

    private Tile load(Key key) {
        if (!wanted.contains(key)) return null;
        Entry cached = readDisk(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.revalidateAt > now) {
            Bitmap bitmap = decode(cached.bytes);
            if (bitmap != null) return toTile(cached, bitmap, now);
            removeDisk(key);
            cached = null;
        }
        if (!wanted.contains(key)) return null;
        if (now >= pauseNetworkUntil) {
            try {
                Entry fetched = request(key, cached);
                if (fetched != null && !closed) {
                    Bitmap bitmap = decode(fetched.bytes);
                    if (bitmap == null) throw new IOException("Unexpected raster tile image");
                    if (fetched.expires > 0) {
                        writeDisk(key, fetched);
                    } else {
                        removeDisk(key);
                    }
                    return toTile(fetched, bitmap, System.currentTimeMillis());
                }
            } catch (IOException ignored) {
                // The visible map remains interactive when a tile request fails.
            }
        }
        long retryAt = Math.max(pauseNetworkUntil, now + RETRY_MS);
        long staleUntil = cached == null ? 0 : Math.max(cached.staleIfError, cached.staleWhileRevalidate);
        Bitmap fallback = staleUntil > now ? decode(cached.bytes) : null;
        return new Tile(fallback, staleUntil, retryAt, false);
    }

    private static Tile toTile(Entry response, Bitmap bitmap, long now) {
        // A response with no permitted cache lifetime can still be rendered for its current view.
        boolean currentViewOnly = response.revalidateAt <= now;
        long retryAt = currentViewOnly ? now + RETRY_MS : response.revalidateAt;
        long showUntil = currentViewOnly ? retryAt : Math.max(response.expires, response.staleWhileRevalidate);
        return new Tile(bitmap, showUntil, retryAt, currentViewOnly);
    }

    private Entry request(Key key, Entry previous) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(key.url()).openConnection();
        try {
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "image/png");
            if (previous != null && !previous.etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", previous.etag);
            }
            if (previous != null && !previous.modified.isEmpty()) {
                connection.setRequestProperty("If-Modified-Since", previous.modified);
            }
            int status = connection.getResponseCode();
            if (status == 429 || status == 403 || status == 503) {
                pauseNetworkUntil = System.currentTimeMillis() + retryDelay(connection, status);
                return null;
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_NOT_MODIFIED) {
                return null;
            }
            boolean unchanged = status == HttpURLConnection.HTTP_NOT_MODIFIED;
            if (unchanged && previous == null) return null;
            byte[] bytes = unchanged ? previous.bytes : readResponse(connection);
            long now = System.currentTimeMillis();
            String cacheControl = connection.getHeaderField("Cache-Control");
            long expires = expiry(connection, cacheControl, now);
            boolean allowStale = expires != 0
                    && !hasDirective(cacheControl, "must-revalidate")
                    && !hasDirective(cacheControl, "no-cache")
                    && (cacheControl != null || !unchanged || previous.staleWhileRevalidate > previous.expires
                        || previous.staleIfError > previous.expires);
            long staleWhileRevalidate = expires;
            long staleIfError = expires;
            if (allowStale) {
                staleWhileRevalidate += staleAllowance(cacheControl, "stale-while-revalidate",
                        unchanged ? previous.staleWhileRevalidate - previous.expires : 0);
                staleIfError += staleAllowance(cacheControl, "stale-if-error",
                        unchanged ? previous.staleIfError - previous.expires : 0);
            }
            long revalidateAt = expires > now ? expires
                    : Math.min(staleWhileRevalidate, now + REVALIDATE_BACKOFF_MS);
            return new Entry(bytes, expires, staleWhileRevalidate, staleIfError, revalidateAt,
                    header(connection, "ETag", unchanged ? previous.etag : ""),
                    header(connection, "Last-Modified", unchanged ? previous.modified : ""));
        } finally {
            connection.disconnect();
        }
    }

    private static long expiry(HttpURLConnection connection, String cacheControl, long now) {
        if (hasDirective(cacheControl, "no-store")) return 0;
        if (hasDirective(cacheControl, "no-cache")) return now;
        long ageSeconds = Math.max(0, parseNumber(connection.getHeaderField("Age"), 0));
        String maxAge = directiveValue(cacheControl, "max-age");
        if (maxAge != null) {
            long seconds = parseNumber(maxAge, -1);
            if (seconds >= 0) {
                return now + (Math.min(315_360_000L, seconds) - Math.min(315_360_000L, ageSeconds)) * 1000;
            }
        }
        long expiration = connection.getHeaderFieldDate("Expires", -1);
        if (expiration >= 0) {
            long date = connection.getHeaderFieldDate("Date", now);
            long remaining = expiration - date - Math.min(315_360_000L, ageSeconds) * 1000;
            return now + remaining;
        }
        return now + FALLBACK_EXPIRY_MS;
    }

    private static long staleAllowance(String cacheControl, String directive, long previous) {
        if (cacheControl == null) return previous;
        long seconds = parseNumber(directiveValue(cacheControl, directive), 0);
        return Math.max(0, Math.min(seconds, 315_360_000L)) * 1000;
    }

    private static boolean hasDirective(String cacheControl, String name) {
        return directiveValue(cacheControl, name) != null;
    }

    private static String directiveValue(String cacheControl, String name) {
        if (cacheControl == null) return null;
        for (String part : cacheControl.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair[0].trim().toLowerCase(Locale.ROOT).equals(name)) {
                return pair.length == 2 ? pair[1].trim().replace("\"", "") : "";
            }
        }
        return null;
    }

    private static long retryDelay(HttpURLConnection connection, int status) {
        String value = connection.getHeaderField("Retry-After");
        long seconds = parseNumber(value, -1);
        if (seconds >= 0) return Math.max(RETRY_MS, Math.min(86_400, seconds) * 1000);
        long date = connection.getHeaderFieldDate("Retry-After", -1);
        if (date >= 0) return Math.max(RETRY_MS, Math.min(86_400_000, date - System.currentTimeMillis()));
        return status == 403 ? 300_000 : 90_000;
    }

    private static long parseNumber(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String header(HttpURLConnection connection, String name, String fallback) {
        String value = connection.getHeaderField(name);
        return value == null ? fallback : value;
    }

    private static byte[] readResponse(HttpURLConnection connection) throws IOException {
        long count = connection.getContentLengthLong();
        if (count > MAX_TILE_BYTES) throw new IOException("Raster tile exceeds size limit");
        try (InputStream stream = connection.getInputStream();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (bytes.size() + n > MAX_TILE_BYTES) throw new IOException("Raster tile exceeds size limit");
                bytes.write(buffer, 0, n);
            }
            return bytes.toByteArray();
        }
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options choices = new BitmapFactory.Options();
        choices.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, choices);
        if (choices.outWidth != 256 || choices.outHeight != 256) return null;
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    private Entry readDisk(Key key) {
        synchronized (DISK_LOCK) {
            File file = new File(directory, key.fileName());
            try (DataInputStream in = new DataInputStream(new AtomicFile(file).openRead())) {
                if (in.readInt() != CACHE_FORMAT) throw new IOException("Tile cache version");
                long expires = in.readLong();
                long staleWhileRevalidate = in.readLong();
                long staleIfError = in.readLong();
                long revalidateAt = in.readLong();
                String etag = in.readUTF();
                String modified = in.readUTF();
                int size = in.readInt();
                if (size <= 0 || size > MAX_TILE_BYTES) throw new IOException("Tile cache size");
                byte[] bytes = new byte[size];
                in.readFully(bytes);
                file.setLastModified(System.currentTimeMillis());
                return new Entry(bytes, expires, staleWhileRevalidate, staleIfError, revalidateAt, etag, modified);
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    private void writeDisk(Key key, Entry entry) {
        synchronized (DISK_LOCK) {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) return;
            AtomicFile file = new AtomicFile(new File(directory, key.fileName()));
            FileOutputStream out = null;
            try {
                out = file.startWrite();
                DataOutputStream data = new DataOutputStream(out);
                data.writeInt(CACHE_FORMAT);
                data.writeLong(entry.expires);
                data.writeLong(entry.staleWhileRevalidate);
                data.writeLong(entry.staleIfError);
                data.writeLong(entry.revalidateAt);
                data.writeUTF(entry.etag);
                data.writeUTF(entry.modified);
                data.writeInt(entry.bytes.length);
                data.write(entry.bytes);
                data.flush();
                file.finishWrite(out);
            } catch (IOException e) {
                if (out != null) file.failWrite(out);
                return;
            }
            trimDisk();
        }
    }

    private void removeDisk(Key key) {
        synchronized (DISK_LOCK) {
            new AtomicFile(new File(directory, key.fileName())).delete();
        }
    }

    private void trimDisk() {
        if (++writesSinceTrim < 32) return;
        writesSinceTrim = 0;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".tile"));
        if (files == null) return;
        long size = 0;
        for (File file : files) size += file.length();
        if (size < DISK_BYTES) return;
        java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            long bytes = file.length();
            if (file.delete()) size -= bytes;
            if (size <= DISK_BYTES) break;
        }
    }
}
