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

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
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
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
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

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UminekoInstaller:install");
        wakeLock.acquire();

        Progress.running = true;
        Progress.stopRequested = false;
        Progress.error = null;
        Progress.itemCount = items.size();
        new Thread(() -> {
            try {
                runAll(items, target, deleteArchives);
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

    private void runAll(List<Item> items, File target, boolean deleteArchives) throws Exception {
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

        for (int i = 0; i < items.size(); i++) {
            if (Progress.stopRequested) return;
            Item item = items.get(i);
            Progress.itemIndex = i;
            String key = target.getAbsolutePath() + "|" + item.url;
            if (done.contains(key)) continue;

            String prefix = "(" + (i + 1) + "/" + items.size() + ") ";
            File file = download(item, downloads, i, prefix);
            if (file == null) return; // stopped

            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".apk")) {
                File apk = new File(target, file.getName());
                if (!file.renameTo(apk)) apk = file;
                Progress.apkPath = apk.getAbsolutePath();
                prefs.edit().putString("apk", Progress.apkPath).apply();
            } else {
                setStatus(prefix + "Extracting " + item.label, -1);
                extract(file, target, prefix + "Extracting " + item.label);
                if (Progress.stopRequested) return;
                if (deleteArchives) file.delete();
            }
            done.add(key);
            prefs.edit().putStringSet("done", new HashSet<>(done)).apply();
        }
        if (deleteArchives) {
            File[] left = downloads.listFiles();
            if (left == null || left.length == 0) downloads.delete();
        }
    }

    // ---------------------------------------------------------------- downloading

    /** Returns the finished file, or null if the user pressed Stop. */
    private File download(Item item, File dir, int index, String prefix) throws Exception {
        String prefix2 = prefix + "Downloading " + item.label;
        IOException last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            if (Progress.stopRequested) return null;
            try {
                return downloadOnce(item, dir, index, prefix2);
            } catch (IOException e) {
                last = e;
                setStatus(prefix2 + "\nConnection problem, retrying (" + (attempt + 1) + "/8)...", -1);
                Thread.sleep(Math.min(60000, 3000L << attempt));
            }
        }
        throw last;
    }

    private File downloadOnce(Item item, File dir, int index, String status) throws Exception {
        String url = LinkScraper.isYandex(item.url) ? resolveYandex(item.url) : item.url;

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
        if (code != 200 && code != 206) {
            throw new IOException("Server answered HTTP " + code + " for " + item.label);
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
            long lastUpdate = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (Progress.stopRequested) return null;
                out.write(buf, 0, r);
                got += r;
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 500) {
                    lastUpdate = now;
                    int permille = total > 0 ? (int) (got * 1000 / total) : -1;
                    setStatus(status + "\n" + mb(got) + (total > 0 ? " / " + mb(total) : ""), permille);
                }
            }
            if (total > 0 && got < total) throw new IOException("Connection closed early");
        } finally {
            c.disconnect();
        }

        File finished = new File(dir, name);
        finished.delete();
        if (!part.renameTo(finished)) throw new IOException("Cannot rename " + part);
        return finished;
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

    private void extract(File archive, File target, String status) throws IOException {
        byte[] magic = new byte[6];
        try (InputStream in = new java.io.FileInputStream(archive)) {
            if (in.read(magic) < 4) throw new IOException(archive.getName() + " is too small");
        }
        if (magic[0] == 'P' && magic[1] == 'K') {
            extractZip(archive, target, status);
        } else if ((magic[0] & 0xff) == 0x37 && (magic[1] & 0xff) == 0x7A && (magic[2] & 0xff) == 0xBC) {
            extract7z(archive, target, status);
        } else if (magic[0] == 'R' && magic[1] == 'a' && magic[2] == 'r') {
            throw new IOException(archive.getName() + " is a RAR archive, which this app cannot open yet");
        } else {
            // Not an archive: just place the file in the game folder.
            String name = archive.getName().replaceFirst("^\\d\\d-", "");
            File dest = new File(target, name);
            dest.delete();
            if (!archive.renameTo(dest)) throw new IOException("Cannot move " + archive.getName());
        }
    }

    private void extractZip(File archive, File target, String status) throws IOException {
        try (ZipFile zip = ZipFile.builder().setFile(archive).get()) {
            long total = 0;
            for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
                total += Math.max(0, e.nextElement().getSize());
            }
            long done = 0;
            for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
                if (Progress.stopRequested) return;
                ZipArchiveEntry entry = e.nextElement();
                File out = safeFile(target, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    done = writeEntry(in, out, done, total, status);
                }
            }
        }
    }

    private void extract7z(File archive, File target, String status) throws IOException {
        try (SevenZFile sz = SevenZFile.builder().setFile(archive).get()) {
            long total = 0;
            for (SevenZArchiveEntry e : sz.getEntries()) total += Math.max(0, e.getSize());
            long done = 0;
            SevenZArchiveEntry entry;
            while ((entry = sz.getNextEntry()) != null) {
                if (Progress.stopRequested) return;
                File out = safeFile(target, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                if (!entry.hasStream()) {
                    writeEntry(new java.io.ByteArrayInputStream(new byte[0]), out, done, total, status);
                    continue;
                }
                done = writeEntry(sz.getInputStream(entry), out, done, total, status);
            }
        }
    }

    private long writeEntry(InputStream in, File out, long done, long total, String status) throws IOException {
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
                    setStatus(status + "\n" + out.getName(), total > 0 ? (int) (done * 1000 / total) : -1);
                }
            }
        }
        return done;
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
