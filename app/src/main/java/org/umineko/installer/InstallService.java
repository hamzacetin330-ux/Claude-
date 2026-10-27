package org.umineko.installer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZLayout;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads every selected file in page order, extracts each archive into the game folder
 * (later archives overwrite earlier ones, which is how the update/script packs are meant to
 * be applied), and remembers finished steps so a restart continues where it stopped.
 */
public class InstallService extends Service {

    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_DELETE_ARCHIVES = "delete_archives";
    public static final String EXTRA_PASSWORD = "password";

    static final String PREFS = "install";
    private static final String CHANNEL = "install";
    private static final int NOTIFICATION_ID = 1;
    private static final int BUFFER = 256 * 1024;

    private PowerManager.WakeLock wakeLock;
    private NotificationManager notifications;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Big speed-ups for the encrypted, many-file resource archive (see CachingAes256Decoder).
        org.apache.commons.compress.archivers.sevenz.CachingAes256Decoder.install();
        if (intent == null || Progress.running) return START_NOT_STICKY;
        notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Installing", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = buildNotification("Starting...", -1);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        List<Item> items = Item.fromJson(intent.getStringExtra(EXTRA_ITEMS));
        File target = new File(intent.getStringExtra(EXTRA_TARGET));
        boolean deleteArchives = intent.getBooleanExtra(EXTRA_DELETE_ARCHIVES, true);
        String password = intent.getStringExtra(EXTRA_PASSWORD);

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UminekoInstaller:install");
        wakeLock.acquire();

        Progress.running = true;
        Progress.stopRequested = false;
        Progress.error = null;
        Progress.itemCount = items.size();
        new Thread(() -> {
            try {
                runAll(items, target, deleteArchives, password);
                if (Progress.stopRequested) {
                    setStatus("Stopped. Press the button again to continue where it left off.", -1);
                } else {
                    setStatus("All done! The game files are in " + target.getAbsolutePath(), 1000);
                }
            } catch (Exception e) {
                Progress.error = e.toString();
                setStatus("Failed: " + e.getMessage()
                        + "\nPress the button again to retry; finished files are kept.", -1);
            } finally {
                Progress.running = false;
                if (wakeLock.isHeld()) wakeLock.release();
                stopForeground(STOP_FOREGROUND_DETACH);
                notifications.notify(NOTIFICATION_ID, buildNotification(Progress.status, -2));
                stopSelf();
            }
        }, "installer").start();
        return START_NOT_STICKY;
    }

    private void runAll(List<Item> items, File target, boolean deleteArchives, String password)
            throws Exception {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Cannot create folder " + target
                    + ". Did you allow \"All files access\"?");
        }
        File downloads = new File(target, ".installer-downloads");
        if (!downloads.isDirectory() && !downloads.mkdirs()) {
            throw new IOException("Cannot create " + downloads);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> done = new HashSet<>(prefs.getStringSet("done", new HashSet<>()));

        // 1. Download every file that isn't installed yet, several at a time: the mirrors limit
        //    the speed of each connection, so parallel downloads use much more of the line.
        Map<Integer, File> files = downloadAll(items, target, downloads, done);
        if (files == null) return; // stopped

        // 2. Install in page order. Parts of a split archive (name.7z.001, .002, ...) are
        //    extracted together, before anything that comes after them on the page.
        Volumes pending = null;

        for (int i = 0; i < items.size(); i++) {
            if (Progress.stopRequested) return;
            Item item = items.get(i);
            Progress.itemIndex = i;
            String key = target.getAbsolutePath() + "|" + item.key();
            if (done.contains(key)) continue;

            String prefix = "(" + (i + 1) + "/" + items.size() + ") ";
            File file = files.get(i);

            String base = volumeBase(file);
            if (pending != null && !pending.base.equals(base)) {
                if (!finishVolumes(pending, items, downloads, target, deleteArchives, password, done, prefs)) return;
                pending = null;
            }
            if (base != null) {
                if (pending == null) pending = new Volumes(base);
                pending.parts.add(file);
                pending.keys.add(key);
                pending.items.put(file, i);
                continue;
            }

            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".apk")) {
                File apk = new File(target, file.getName());
                if (!file.renameTo(apk)) apk = file;
                Progress.apkPath = apk.getAbsolutePath();
                prefs.edit().putString("apk", Progress.apkPath).apply();
            } else {
                String status = prefix + "Extracting " + item.label;
                setStatus(status, -1);
                extract(Collections.singletonList(file), target, password, status);
                if (Progress.stopRequested) return;
                if (deleteArchives) file.delete();
            }
            done.add(key);
            prefs.edit().putStringSet("done", new HashSet<>(done)).apply();
        }
        if (pending != null
                && !finishVolumes(pending, items, downloads, target, deleteArchives, password, done, prefs)) {
            return;
        }
        if (deleteArchives) {
            File[] left = downloads.listFiles();
            if (left == null || left.length == 0) downloads.delete();
        }
    }

    private static final class Volumes {
        final String base;
        final List<File> parts = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        /** Part file -> index of the item it was downloaded for. */
        final Map<File, Integer> items = new java.util.HashMap<>();

        Volumes(String base) {
            this.base = base;
        }
    }

    private static final Pattern VOLUME = Pattern.compile("^(?:\\d\\d-)?(.+)\\.(\\d{3})$");

    /** "03-umineko.7z.002" -> "umineko.7z"; null if the file is not part of a split archive. */
    private static String volumeBase(File f) {
        Matcher m = VOLUME.matcher(f.getName());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Extracts a complete split archive; returns false if the user pressed Stop.
     *
     * If the data turns out to be corrupt, every part is checked against its md5: damaged parts
     * are downloaded again, and if all parts are fine the extraction is retried once with the
     * stock (slower) AES decoder in case the fast one is at fault.
     */
    private boolean finishVolumes(Volumes v, List<Item> items, File dir, File target, boolean deleteArchives,
                                  String password, Set<String> done, SharedPreferences prefs) throws Exception {
        boolean useStockDecoder = false;
        for (int attempt = 0; ; attempt++) {
            sortAndCheckParts(v);
            String status = "Extracting " + v.base + " (" + v.parts.size() + " parts)"
                    + (useStockDecoder ? " - retrying the slow, safe way" : "");
            setStatus(status, -1);
            if (useStockDecoder) {
                org.apache.commons.compress.archivers.sevenz.CachingAes256Decoder.uninstall();
            } else {
                org.apache.commons.compress.archivers.sevenz.CachingAes256Decoder.install();
            }
            try {
                extract(v.parts, target, password, status);
                break;
            } catch (IOException e) {
                if (!isCorruptData(e) || attempt >= 3) throw e;

                setStatus("The archive data looks damaged. Checking all " + v.parts.size() + " parts...", -1);
                List<File> bad = new ArrayList<>();
                for (int n = 0; n < v.parts.size(); n++) {
                    File part = v.parts.get(n);
                    Item item = items.get(v.items.get(part));
                    if (item.md5 == null) continue;
                    setStatus("The archive data looks damaged. Checking part " + (n + 1) + " of "
                            + v.parts.size() + "...", n * 1000 / v.parts.size());
                    String got = md5(part);
                    if (got == null) return false; // stopped
                    if (!got.equalsIgnoreCase(item.md5)) bad.add(part);
                }

                if (!bad.isEmpty()) {
                    for (File part : bad) {
                        int index = v.items.get(part);
                        setStatus("Part " + part.getName().replaceFirst("^\\d\\d-", "")
                                + " was damaged. Downloading it again...", -1);
                        part.delete();
                        new File(dir, String.format(Locale.ROOT, "%02d.ok", index)).delete();
                        new File(dir, String.format(Locale.ROOT, "%02d.name", index)).delete();
                        Slot slot = new Slot(part.getName());
                        File fresh = download(items.get(index), dir, index, slot);
                        if (fresh == null) return false; // stopped
                        int pos = v.parts.indexOf(part);
                        v.parts.set(pos, fresh);
                        v.items.remove(part);
                        v.items.put(fresh, index);
                    }
                } else if (!useStockDecoder) {
                    useStockDecoder = true;
                } else {
                    throw new IOException("All parts of " + v.base + " are complete (md5 is correct), "
                            + "but the archive still could not be unpacked: " + e.getMessage(), e);
                }
            }
        }
        org.apache.commons.compress.archivers.sevenz.CachingAes256Decoder.install();
        if (Progress.stopRequested) return false;
        if (deleteArchives) for (File f : v.parts) f.delete();
        done.addAll(v.keys);
        prefs.edit().putStringSet("done", new HashSet<>(done)).apply();
        return true;
    }

    private void sortAndCheckParts(Volumes v) throws IOException {
        v.parts.sort((a, b) -> Integer.compare(partNumber(a), partNumber(b)));
        for (int n = 0; n < v.parts.size(); n++) {
            if (partNumber(v.parts.get(n)) != n + 1) {
                throw new IOException("Part ." + String.format(Locale.ROOT, "%03d", n + 1) + " of " + v.base
                        + " is missing. Make sure every resource file is ticked.");
            }
        }
    }

    private static int partNumber(File f) {
        Matcher m = VOLUME.matcher(f.getName());
        return m.matches() ? Integer.parseInt(m.group(2)) : -1;
    }

    /** Errors meaning the archive's bytes are wrong (as opposed to e.g. a full disk). */
    private static boolean isCorruptData(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (n.contains("Corrupted") || n.contains("CRC") || m.contains("corrupt")
                    || m.contains("checksum") || m.contains("crc") || m.contains("no header")
                    || m.contains("decryption failed") || m.contains("truncated")) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- downloading

    /** A mirror that will not work no matter how often we retry (web page, 404, bad md5...). */
    private static final class MirrorFailed extends IOException {
        MirrorFailed(String message) {
            super(message);
        }
    }

    private static final int PARALLEL_DOWNLOADS = 4;

    /** Progress of one running download, shown in the combined status. */
    private static final class Slot {
        final String name;
        volatile long got;
        volatile long total = -1;
        volatile String note;

        Slot(String name) {
            this.name = name;
        }
    }

    private final Map<Integer, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong received = new AtomicLong();

    /**
     * Downloads all items that aren't installed yet, PARALLEL_DOWNLOADS at a time.
     * Returns index -> file, or null if the user pressed Stop.
     */
    private Map<Integer, File> downloadAll(List<Item> items, File target, File dir, Set<String> done)
            throws Exception {
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!done.contains(target.getAbsolutePath() + "|" + items.get(i).key())) todo.add(i);
        }
        Map<Integer, File> files = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger finished = new AtomicInteger();
        slots.clear();
        received.set(0);

        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS);
        for (int i : todo) {
            pool.execute(() -> {
                Item item = items.get(i);
                int dash = item.label.indexOf(" - ");
                Slot slot = new Slot(dash > 0 ? item.label.substring(dash + 3) : item.label);
                slots.put(i, slot);
                try {
                    File f = download(item, dir, i, slot);
                    if (f != null) files.put(i, f);
                } catch (Exception e) {
                    failures.add(e.getMessage());
                } finally {
                    slots.remove(i);
                    finished.incrementAndGet();
                }
            });
        }
        pool.shutdown();

        // Combined status while the downloads run.
        long windowStart = System.currentTimeMillis();
        long windowBytes = 0;
        double speed = 0;
        while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 2000) {
                long bytes = received.get();
                speed = (bytes - windowBytes) * 1000.0 / (now - windowStart);
                windowBytes = bytes;
                windowStart = now;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Downloading: ").append(finished.get()).append(" of ").append(todo.size())
                    .append(" files done");
            if (speed > 0) sb.append(String.format(Locale.ROOT, "  \u00b7  %.1f MB/s", speed / 1048576.0));
            long got = 0;
            long total = 0;
            for (Map.Entry<Integer, Slot> e : new TreeMap<>(slots).entrySet()) {
                Slot sl = e.getValue();
                sb.append("\n\u2022 ").append(sl.name).append("  ");
                if (sl.note != null) {
                    sb.append(sl.note);
                } else {
                    sb.append(mb(sl.got));
                    if (sl.total > 0) sb.append(" / ").append(mb(sl.total));
                }
                if (sl.total > 0) {
                    got += sl.got;
                    total += sl.total;
                }
            }
            if (!failures.isEmpty()) sb.append("\n(").append(failures.size()).append(" failed, will report at the end)");
            Progress.itemIndex = Math.min(finished.get(), Math.max(0, todo.size() - 1));
            Progress.itemCount = Math.max(1, todo.size());
            setStatus(sb.toString(), total > 0 ? (int) (got * 1000 / total) : -1);
        }
        Progress.itemCount = items.size();

        if (Progress.stopRequested) return null;
        if (!failures.isEmpty()) throw new IOException(String.join("\n\n", failures));
        return files;
    }

    /** Returns the finished (and md5-checked) file, or null if the user pressed Stop. */
    private File download(Item item, File dir, int index, Slot slot) throws Exception {
        File okFile = new File(dir, String.format(Locale.ROOT, "%02d.ok", index));
        File nameFile = new File(dir, String.format(Locale.ROOT, "%02d.name", index));
        if (okFile.exists() && nameFile.exists()) {
            String owner = readSmall(okFile);
            File finished = new File(dir, readSmall(nameFile));
            // "ok" = written by an older version, which didn't record the item.
            if (finished.exists() && (owner.equals(item.key()) || owner.equals("ok"))) return finished;
            okFile.delete();
            nameFile.delete();
            finished.delete();
        }

        List<String> problems = new ArrayList<>();
        for (int m = 0; m < item.urls.size(); m++) {
            String mirror = item.urls.get(m);
            for (int attempt = 0; attempt < 5; attempt++) {
                if (Progress.stopRequested) return null;
                try {
                    slot.note = m > 0 ? "trying mirror " + (m + 1) : null;
                    File f = downloadOnce(mirror, dir, index, slot);
                    if (f == null) return null;
                    if (item.md5 != null) {
                        slot.note = "checking the file...";
                        String got = md5(f);
                        if (got == null) return null;
                        if (!got.equalsIgnoreCase(item.md5)) {
                            f.delete();
                            nameFile.delete();
                            throw new MirrorFailed("the downloaded file was damaged (md5 did not match)");
                        }
                    }
                    writeSmall(okFile, item.key());
                    return f;
                } catch (MirrorFailed e) {
                    problems.add("mirror " + (m + 1) + ": " + e.getMessage());
                    new File(dir, String.format(Locale.ROOT, "%02d.part", index)).delete();
                    break;
                } catch (IOException e) {
                    if (attempt == 4) {
                        problems.add("mirror " + (m + 1) + ": " + e.getMessage());
                        break;
                    }
                    slot.note = "connection problem, retrying (" + (attempt + 1) + "/4)...";
                    Thread.sleep(Math.min(60000, 3000L << attempt));
                }
            }
        }
        throw new IOException("Could not download " + item.label + "\n" + String.join("\n", problems));
    }

    private File downloadOnce(String mirror, File dir, int index, Slot slot) throws Exception {
        String url = resolve(mirror);

        File part = new File(dir, String.format(Locale.ROOT, "%02d.part", index));
        File nameFile = new File(dir, String.format(Locale.ROOT, "%02d.name", index));
        String knownName = nameFile.exists() ? readSmall(nameFile) : null;
        if (knownName != null) {
            File finished = new File(dir, knownName);
            if (finished.exists() && !part.exists()) return finished;
        }

        long have = part.exists() ? part.length() : 0;
        HttpURLConnection c = open(url, have);
        int code = c.getResponseCode();
        if (code == 416) { // already have the whole thing
            have = 0;
            c.disconnect();
            part.delete();
            c = open(url, 0);
            code = c.getResponseCode();
        }
        if (code == 404 || code == 403 || code == 410 || code == 401) {
            c.disconnect();
            throw new MirrorFailed("server answered HTTP " + code);
        }
        if (code != 200 && code != 206) {
            c.disconnect();
            throw new IOException("server answered HTTP " + code);
        }
        String type = c.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            c.disconnect();
            throw new MirrorFailed("it opened a web page instead of the file");
        }
        if (code == 200) have = 0; // server ignored Range: start over

        String name = fileName(c, url, index);
        writeSmall(nameFile, name);

        long total = c.getContentLengthLong();
        if (total >= 0) total += have;

        try (InputStream in = c.getInputStream();
             RandomAccessFile out = new RandomAccessFile(part, "rw")) {
            out.setLength(have);
            out.seek(have);
            byte[] buf = new byte[BUFFER];
            long got = have;
            slot.total = total;
            slot.got = got;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (Progress.stopRequested) return null;
                out.write(buf, 0, r);
                got += r;
                slot.got = got;
                slot.note = null;
                received.addAndGet(r);
            }
            if (total > 0 && got < total) throw new IOException("connection closed early");
        } finally {
            c.disconnect();
        }

        File finished = new File(dir, name);
        finished.delete();
        if (!part.renameTo(finished)) throw new IOException("Cannot rename " + part);
        return finished;
    }

    /** Turns a mirror's share link into a link that downloads the file itself. */
    private static String resolve(String mirror) throws IOException {
        if (LinkScraper.isYandex(mirror)) return resolveYandex(mirror);
        if (LinkScraper.isGoogleDrive(mirror)) {
            Matcher m = Pattern.compile("(?:/d/|[?&]id=)([\\w-]{10,})").matcher(mirror);
            if (m.find()) {
                return "https://drive.usercontent.google.com/download?id=" + m.group(1)
                        + "&export=download&confirm=t";
            }
        }
        Matcher pd = Pattern.compile("pixeldrain\\.com/u/([\\w-]+)").matcher(mirror);
        if (pd.find()) return "https://pixeldrain.com/api/file/" + pd.group(1) + "?download";
        return mirror;
    }

    /** md5 of a file as hex, or null if the user pressed Stop. */
    private static String md5(File f) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[BUFFER];
            try (InputStream in = new java.io.FileInputStream(f)) {
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (Progress.stopRequested) return null;
                    md.update(buf, 0, r);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static HttpURLConnection open(String url, long from) throws IOException {
        for (int hops = 0; hops < 10; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(30000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) UminekoInstaller/1.0");
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    /** Turns a public Yandex Disk link into a direct download link (it expires, so ask each time). */
    private static String resolveYandex(String publicUrl) throws IOException {
        String api = "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key="
                + URLEncoder.encode(publicUrl, "UTF-8");
        HttpURLConnection c = open(api, 0);
        try {
            if (c.getResponseCode() == 404) throw new MirrorFailed("Yandex Disk says the file is gone");
            if (c.getResponseCode() != 200) {
                throw new IOException("Yandex Disk answered HTTP " + c.getResponseCode()
                        + " (the link may be over its download limit; try again later)");
            }
            try (InputStream in = c.getInputStream()) {
                String body = new String(readAll(in), StandardCharsets.UTF_8);
                return new JSONObject(body).getString("href");
            } catch (org.json.JSONException e) {
                throw new IOException("Unexpected Yandex Disk reply", e);
            }
        } finally {
            c.disconnect();
        }
    }

    private static final Pattern CD_NAME = Pattern.compile(
            "filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private static String fileName(HttpURLConnection c, String url, int index) {
        String name = null;
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            Matcher m = CD_NAME.matcher(cd);
            String found = null;
            while (m.find()) found = m.group(1); // prefer the last (filename*) form
            name = found;
        }
        if (name == null) {
            // Yandex direct links carry the name as a query parameter.
            Matcher m = Pattern.compile("[?&]filename=([^&]+)").matcher(c.getURL().toString());
            if (m.find()) name = m.group(1);
        }
        if (name == null) {
            String path = c.getURL().getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        try {
            name = URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
            // keep it as is
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty() || name.endsWith(".part") || name.endsWith(".name")) {
            name = String.format(Locale.ROOT, "file%02d", index);
        }
        return String.format(Locale.ROOT, "%02d-", index) + name;
    }

    // ---------------------------------------------------------------- extracting

    /** When the current extraction started, for the speed / time-left estimate. */
    private long extractStartMs;

    /** Entry currently being written, for error messages. */
    private volatile String currentEntry;

    private void extract(List<File> parts, File target, String password, String status) throws IOException {
        extractStartMs = System.currentTimeMillis();
        currentEntry = null;
        try {
            extractArchive(parts, target, password, status);
        } catch (IOException e) {
            String archive = parts.get(0).getName().replaceFirst("^\\d\\d-", "");
            String where = currentEntry != null ? " (while writing " + currentEntry + ")" : "";
            IOException named = new IOException(archive + where + ": " + e.getMessage(), e);
            throw named;
        }
    }

    private void extractArchive(List<File> parts, File target, String password, String status) throws IOException {
        File first = parts.get(0);
        byte[] magic = new byte[6];
        try (InputStream in = new java.io.FileInputStream(first)) {
            if (in.read(magic) < 4) throw new IOException(first.getName() + " is too small");
        }
        if (magic[0] == 'P' && magic[1] == 'K') {
            extractZip(parts, target, password, status);
        } else if ((magic[0] & 0xff) == 0x37 && (magic[1] & 0xff) == 0x7A && (magic[2] & 0xff) == 0xBC) {
            extract7z(parts, target, password, status);
        } else if (magic[0] == 'R' && magic[1] == 'a' && magic[2] == 'r') {
            throw new IOException(first.getName() + " is a RAR archive, which this app cannot open yet");
        } else if (parts.size() == 1) {
            // Not an archive: just place the file in the game folder.
            String name = first.getName().replaceFirst("^\\d\\d-", "");
            File dest = new File(target, name);
            dest.delete();
            if (!first.renameTo(dest)) throw new IOException("Cannot move " + first.getName());
        } else {
            throw new IOException(first.getName() + " is not a 7z or zip archive");
        }
    }

    /** All parts of a split archive read as one continuous file, without joining them on disk. */
    private static SeekableByteChannel channel(List<File> parts) throws IOException {
        return parts.size() == 1
                ? Files.newByteChannel(parts.get(0).toPath(), StandardOpenOption.READ)
                : MultiReadOnlySeekableByteChannel.forFiles(parts.toArray(new File[0]));
    }

    private void extractZip(List<File> parts, File target, String password, String status) throws IOException {
        if (parts.size() > 1) throw new IOException("Split zip archives are not supported");
        char[] pw = password == null || password.isEmpty() ? null : password.toCharArray();
        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(parts.get(0), pw)) {
            List<FileHeader> headers = zip.getFileHeaders();
            long total = 0;
            for (FileHeader h : headers) total += Math.max(0, h.getUncompressedSize());
            long done = 0;
            for (FileHeader h : headers) {
                if (Progress.stopRequested) return;
                File out = safeFile(target, h.getFileName());
                if (h.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                try (InputStream in = zip.getInputStream(h)) {
                    done = writeEntry(in, out, done, total, status);
                } catch (ZipException e) {
                    if (e.getType() == ZipException.Type.WRONG_PASSWORD) {
                        throw new IOException(parts.get(0).getName().replaceFirst("^\\d\\d-", "")
                                + ": the password is wrong (or missing). Check the password box.", e);
                    }
                    throw e;
                }
            }
        }
    }

    private static final int MAX_EXTRACT_THREADS = 4;

    private static SevenZFile open7z(List<File> parts, String password) throws IOException {
        SevenZFile.Builder builder = SevenZFile.builder().setSeekableByteChannel(channel(parts));
        if (password != null && !password.isEmpty()) builder.setPassword(password.toCharArray());
        try {
            return builder.get();
        } catch (PasswordRequiredException e) {
            throw new IOException("The archive needs a password. Type it into the password box.", e);
        } catch (IOException e) {
            if (password == null || password.isEmpty()) throw e;
            throw new IOException("Could not open the archive - the password is probably wrong, "
                    + "or a part is damaged. (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Extracts a 7z archive on several threads. Each thread opens the archive itself and
     * unpacks a run of whole compressed blocks, so no block is decoded twice. A solid archive
     * (one big block) simply ends up on one thread.
     */
    private void extract7z(List<File> parts, File target, String password, String status) throws IOException {
        List<SevenZArchiveEntry> entries = new ArrayList<>();
        SevenZLayout layout;
        try (SevenZFile probe = open7z(parts, password)) {
            for (SevenZArchiveEntry e : probe.getEntries()) entries.add(e);
            layout = SevenZLayout.of(probe);
        }
        long total = 0;
        for (SevenZArchiveEntry e : entries) total += Math.max(0, e.getSize());

        List<int[]> ranges = splitByBlocks(entries, layout, threadCount(layout));
        AtomicLong done = new AtomicLong();
        AtomicReference<IOException> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int[] range : ranges) {
            Thread t = new Thread(() -> {
                try {
                    extractRange(parts, password, target, range[0], range[1], done, failure);
                } catch (IOException e) {
                    failure.compareAndSet(null, e);
                } catch (OutOfMemoryError e) {
                    failure.compareAndSet(null, new IOException("Out of memory while unpacking", e));
                }
            }, "extract-" + range[0]);
            threads.add(t);
            t.start();
        }

        // Status: speed over the last few seconds, not since the start.
        final long totalBytes = total;
        long windowStart = System.currentTimeMillis();
        long windowDone = 0;
        double speed = 0;
        for (Thread t : threads) {
            while (t.isAlive()) {
                try {
                    t.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                long now = System.currentTimeMillis();
                long d = done.get();
                if (now - windowStart >= 3000) {
                    double recent = (d - windowDone) * 1000.0 / (now - windowStart);
                    speed = speed == 0 ? recent : speed * 0.6 + recent * 0.4;
                    windowDone = d;
                    windowStart = now;
                }
                setStatus(status + "\n" + eta(d, totalBytes, speed) + ranges.size() + " threads"
                                + (currentEntry != null ? "  \u00b7  " + currentEntry : ""),
                        totalBytes > 0 ? (int) (d * 1000 / totalBytes) : -1);
            }
        }
        IOException e = failure.get();
        if (e != null) {
            if (password != null && !password.isEmpty() && String.valueOf(e.getMessage()).contains("Checksum")) {
                throw new IOException("Could not unpack the archive - the password may be wrong, "
                        + "or a part is damaged. (" + e.getMessage() + ")", e);
            }
            throw e;
        }
    }

    /** How many threads to use: limited by cores and by memory for the LZMA dictionaries. */
    private static int threadCount(SevenZLayout layout) {
        int cores = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        long perThread = Math.min(layout.maxDictionary, 1L << 31) + (8L << 20);
        int byMemory = (int) Math.max(1, (heap / 2) / perThread);
        return Math.max(1, Math.min(MAX_EXTRACT_THREADS, Math.min(cores - 1, byMemory)));
    }

    /**
     * Splits entries into at most n contiguous runs of roughly equal size, cutting only between
     * compressed blocks. Returns [from, to) index pairs.
     */
    private static List<int[]> splitByBlocks(List<SevenZArchiveEntry> entries, SevenZLayout layout, int n) {
        List<int[]> ranges = new ArrayList<>();
        int count = entries.size();
        if (n <= 1 || layout.entryBlock == null || layout.entryBlock.length != count) {
            ranges.add(new int[]{0, count});
            return ranges;
        }
        long total = 0;
        for (SevenZArchiveEntry e : entries) total += Math.max(0, e.getSize());
        long target = Math.max(1, total / n);
        int from = 0;
        long acc = 0;
        int lastBlock = -1;
        for (int i = 0; i < count; i++) {
            int block = layout.entryBlock[i];
            boolean boundary = block >= 0 && block != lastBlock;
            if (boundary && acc >= target && ranges.size() < n - 1) {
                ranges.add(new int[]{from, i});
                from = i;
                acc = 0;
            }
            if (block >= 0) lastBlock = block;
            acc += Math.max(0, entries.get(i).getSize());
        }
        ranges.add(new int[]{from, count});
        return ranges;
    }

    private void extractRange(List<File> parts, String password, File target, int from, int to,
                              AtomicLong done, AtomicReference<IOException> failure) throws IOException {
        try (SevenZFile sz = open7z(parts, password)) {
            List<SevenZArchiveEntry> list = new ArrayList<>();
            for (SevenZArchiveEntry e : sz.getEntries()) list.add(e);
            byte[] buf = new byte[BUFFER];
            for (int i = from; i < to; i++) {
                if (Progress.stopRequested || failure.get() != null) return;
                SevenZArchiveEntry entry = list.get(i);
                File out = safeFile(target, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Cannot create " + parent);
                }
                currentEntry = out.getName();
                try (OutputStream os = new FileOutputStream(out)) {
                    // Empty files and directories never touch the decoder (that would make it
                    // restart the current block).
                    if (!entry.hasStream() || entry.getSize() == 0) continue;
                    try (InputStream in = sz.getInputStream(entry)) {
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            os.write(buf, 0, r);
                            done.addAndGet(r);
                            if (failure.get() != null) return;
                        }
                    }
                } catch (IOException e) {
                    throw new IOException(entry.getName() + ": " + e.getMessage(), e);
                }
            }
        }
    }

    /** "42% · 35.0 MB/s · about 6 min left · ", or "" while there isn't enough data yet. */
    private String eta(long done, long total, double perSec) {
        if (total <= 0 || perSec <= 0) return "";
        long left = (long) ((total - done) / perSec);
        String time = left >= 3600 ? String.format(Locale.ROOT, "%dh %02dmin", left / 3600, left / 60 % 60)
                : left >= 60 ? (left / 60) + " min" : left + " s";
        return String.format(Locale.ROOT, "%d%%  \u00b7  %.1f MB/s  \u00b7  about %s left  \u00b7  ",
                done * 100 / total, perSec / 1048576.0, time);
    }

    private long writeEntry(InputStream in, File out, long done, long total, String status) throws IOException {
        currentEntry = out.getName();
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        byte[] buf = new byte[BUFFER];
        long lastUpdate = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 500) {
                    lastUpdate = now;
                    setStatus(status + "\n" + extractEta(done, total, now) + out.getName(),
                            total > 0 ? (int) (done * 1000 / total) : -1);
                }
            }
        }
        return done;
    }

    /** "42% · 35.0 MB/s · about 6 min left\n", or "" while there isn't enough data yet. */
    private String extractEta(long done, long total, long now) {
        long ms = now - extractStartMs;
        if (total <= 0 || ms < 5000 || done <= 0) return "";
        double perSec = done * 1000.0 / ms;
        long left = (long) ((total - done) / perSec);
        String time = left >= 3600 ? String.format(Locale.ROOT, "%dh %02dmin", left / 3600, left / 60 % 60)
                : left >= 60 ? (left / 60) + " min" : left + " s";
        return String.format(Locale.ROOT, "%d%%  \u00b7  %.1f MB/s  \u00b7  about %s left\n",
                done * 100 / total, perSec / 1048576.0, time);
    }

    /** Refuses archive entries that try to escape the game folder ("../"). */
    private static File safeFile(File root, String entryName) throws IOException {
        File f = new File(root, entryName);
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("Bad path in archive: " + entryName);
        }
        return f;
    }

    // ---------------------------------------------------------------- helpers

    private void setStatus(String s, int permille) {
        Progress.status = s;
        Progress.stepPermille = permille;
        if (notifications != null && Progress.running) {
            notifications.notify(NOTIFICATION_ID, buildNotification(s, permille));
        }
    }

    private Notification buildNotification(String text, int permille) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Umineko Installer")
                .setContentText(text.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(permille != -2);
        if (permille >= 0) b.setProgress(1000, permille, false);
        else if (permille == -1) b.setProgress(0, 0, true);
        return b.build();
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static String readSmall(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        }
    }

    private static void writeSmall(File f, String s) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
