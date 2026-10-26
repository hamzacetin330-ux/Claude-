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

        // Parts of a split archive (name.7z.001, name.7z.002, ...) are only extracted once
        // the whole set is downloaded, before anything that comes after them on the page.
        Volumes pending = null;

        for (int i = 0; i < items.size(); i++) {
            if (Progress.stopRequested) return;
            Item item = items.get(i);
            Progress.itemIndex = i;
            String key = target.getAbsolutePath() + "|" + item.key();
            if (done.contains(key)) continue;

            String prefix = "(" + (i + 1) + "/" + items.size() + ") ";
            File file = download(item, downloads, i, prefix);
            if (file == null) return; // stopped

            String base = volumeBase(file);
            if (pending != null && !pending.base.equals(base)) {
                if (!finishVolumes(pending, target, deleteArchives, password, done, prefs)) return;
                pending = null;
            }
            if (base != null) {
                if (pending == null) pending = new Volumes(base);
                pending.parts.add(file);
                pending.keys.add(key);
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
        if (pending != null && !finishVolumes(pending, target, deleteArchives, password, done, prefs)) return;
        if (deleteArchives) {
            File[] left = downloads.listFiles();
            if (left == null || left.length == 0) downloads.delete();
        }
    }

    private static final class Volumes {
        final String base;
        final List<File> parts = new ArrayList<>();
        final List<String> keys = new ArrayList<>();

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

    /** Extracts a complete split archive; returns false if the user pressed Stop. */
    private boolean finishVolumes(Volumes v, File target, boolean deleteArchives, String password,
                                  Set<String> done, SharedPreferences prefs) throws IOException {
        v.parts.sort((a, b) -> {
            Matcher ma = VOLUME.matcher(a.getName());
            Matcher mb = VOLUME.matcher(b.getName());
            ma.matches();
            mb.matches();
            return Integer.compare(Integer.parseInt(ma.group(2)), Integer.parseInt(mb.group(2)));
        });
        for (int n = 0; n < v.parts.size(); n++) {
            Matcher m = VOLUME.matcher(v.parts.get(n).getName());
            m.matches();
            if (Integer.parseInt(m.group(2)) != n + 1) {
                throw new IOException("Part ." + String.format(Locale.ROOT, "%03d", n + 1) + " of " + v.base
                        + " is missing. Make sure every resource file is ticked.");
            }
        }
        String status = "Extracting " + v.base + " (" + v.parts.size() + " parts)";
        setStatus(status, -1);
        extract(v.parts, target, password, status);
        if (Progress.stopRequested) return false;
        if (deleteArchives) for (File f : v.parts) f.delete();
        done.addAll(v.keys);
        prefs.edit().putStringSet("done", new HashSet<>(done)).apply();
        return true;
    }

    // ---------------------------------------------------------------- downloading

    /** A mirror that will not work no matter how often we retry (web page, 404, bad md5...). */
    private static final class MirrorFailed extends IOException {
        MirrorFailed(String message) {
            super(message);
        }
    }

    /** Returns the finished (and md5-checked) file, or null if the user pressed Stop. */
    private File download(Item item, File dir, int index, String prefix) throws Exception {
        String status = prefix + "Downloading " + item.label;
        File okFile = new File(dir, String.format(Locale.ROOT, "%02d.ok", index));
        File nameFile = new File(dir, String.format(Locale.ROOT, "%02d.name", index));
        if (okFile.exists() && nameFile.exists()) {
            File finished = new File(dir, readSmall(nameFile));
            if (finished.exists()) return finished;
        }

        List<String> problems = new ArrayList<>();
        for (int m = 0; m < item.urls.size(); m++) {
            String mirror = item.urls.get(m);
            String mirrorStatus = item.urls.size() > 1
                    ? status + " (mirror " + (m + 1) + "/" + item.urls.size() + ")" : status;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (Progress.stopRequested) return null;
                try {
                    File f = downloadOnce(mirror, dir, index, mirrorStatus);
                    if (f == null) return null;
                    if (item.md5 != null) {
                        setStatus(mirrorStatus + "\nChecking the file...", -1);
                        String got = md5(f);
                        if (got == null) return null;
                        if (!got.equalsIgnoreCase(item.md5)) {
                            f.delete();
                            nameFile.delete();
                            throw new MirrorFailed("the downloaded file was damaged (md5 did not match)");
                        }
                    }
                    writeSmall(okFile, "ok");
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
                    setStatus(mirrorStatus + "\nConnection problem, retrying (" + (attempt + 1) + "/4)...", -1);
                    Thread.sleep(Math.min(60000, 3000L << attempt));
                }
            }
        }
        throw new IOException("Could not download " + item.label + "\n" + String.join("\n", problems));
    }

    private File downloadOnce(String mirror, File dir, int index, String status) throws Exception {
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

    private void extract(List<File> parts, File target, String password, String status) throws IOException {
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

    private void extract7z(List<File> parts, File target, String password, String status) throws IOException {
        SevenZFile.Builder builder = SevenZFile.builder().setSeekableByteChannel(channel(parts));
        if (password != null && !password.isEmpty()) builder.setPassword(password.toCharArray());
        SevenZFile sz;
        try {
            sz = builder.get();
        } catch (PasswordRequiredException e) {
            throw new IOException("The archive needs a password. Type it into the password box.", e);
        } catch (IOException e) {
            if (password == null || password.isEmpty()) throw e;
            throw new IOException("Could not open the archive - the password is probably wrong, "
                    + "or a part is damaged. (" + e.getMessage() + ")", e);
        }
        try (SevenZFile ignored = sz) {
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
        } catch (PasswordRequiredException e) {
            throw new IOException("The archive needs a password. Type it into the password box.", e);
        } catch (IOException e) {
            if (password != null && !password.isEmpty() && String.valueOf(e.getMessage()).contains("Checksum")) {
                throw new IOException("Could not unpack the archive - the password may be wrong, "
                        + "or a part is damaged. (" + e.getMessage() + ")", e);
            }
            throw e;
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
