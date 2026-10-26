package org.umineko.installer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String DEFAULT_PAGE = "https://umineko-project.org/en/downloads/";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();

    private SharedPreferences prefs;
    private EditText pageUrl;
    private EditText targetDir;
    private EditText manualLinks;
    private CheckBox deleteArchives;
    private LinearLayout itemList;
    private TextView itemHeader;
    private Button permissionButton;
    private Button startButton;
    private Button stopButton;
    private Button apkButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView spaceText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(InstallService.PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        String apk = prefs.getString("apk", null);
        if (apk != null && new File(apk).exists()) Progress.apkPath = apk;
        handler.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermission();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("This app downloads every Umineko Project file for Android, extracts them "
                + "and puts them together in one folder, so you only have to press one button.", 15, false));

        permissionButton = button("1. Allow access to files", v -> askStoragePermission());
        root.addView(permissionButton);

        root.addView(text("\nGame folder (where the files go):", 14, true));
        targetDir = edit(prefs.getString("target",
                new File(Environment.getExternalStorageDirectory(), "ONScripter-RU").getAbsolutePath()));
        root.addView(targetDir);
        spaceText = text("", 13, false);
        root.addView(spaceText);

        root.addView(text("\nDownloads page:", 14, true));
        pageUrl = edit(prefs.getString("page", DEFAULT_PAGE));
        root.addView(pageUrl);
        root.addView(button("2. Find the files on the page", v -> findFiles()));

        itemHeader = text("", 14, true);
        root.addView(itemHeader);
        itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        root.addView(itemList);

        root.addView(text("\nExtra links (optional, one per line, installed after the ones above in "
                + "this order). Use this if a file was not found or only has a Mega mirror:", 13, false));
        manualLinks = edit(prefs.getString("manual", ""));
        manualLinks.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        manualLinks.setMinLines(2);
        manualLinks.setSingleLine(false);
        root.addView(manualLinks);

        deleteArchives = new CheckBox(this);
        deleteArchives.setText("Delete each archive after extracting it (saves a lot of space)");
        deleteArchives.setChecked(prefs.getBoolean("delete", true));
        root.addView(deleteArchives);

        startButton = button("3. Download & install everything", v -> start());
        root.addView(startButton);
        stopButton = button("Stop", v -> Progress.stopRequested = true);
        root.addView(stopButton);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        root.addView(progressBar);
        statusText = text("", 14, false);
        statusText.setTextIsSelectable(true);
        root.addView(statusText);

        apkButton = button("4. Install the game engine (ONScripter-RU)", v -> installApk());
        root.addView(apkButton);

        root.addView(button("Start over (forget finished downloads)", v -> resetProgress()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean running = Progress.running;
            startButton.setEnabled(!running);
            stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
            statusText.setText(Progress.status);
            int p = Progress.stepPermille;
            progressBar.setIndeterminate(running && p < 0);
            if (p >= 0) progressBar.setProgress(p);
            progressBar.setVisibility(running || p >= 0 ? View.VISIBLE : View.GONE);
            apkButton.setVisibility(Progress.apkPath != null && !running ? View.VISIBLE : View.GONE);
            updateSpace();
            handler.postDelayed(this, 500);
        }
    };

    private void updateSpace() {
        try {
            File dir = new File(targetDir.getText().toString().trim());
            while (dir != null && !dir.exists()) dir = dir.getParentFile();
            if (dir == null) return;
            long free = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
            spaceText.setText(String.format(Locale.ROOT,
                    "Free space: %.1f GB (the full game needs well over 10 GB while installing)", free / 1e9));
        } catch (IllegalArgumentException ignored) {
            spaceText.setText("");
        }
    }

    // ---------------------------------------------------------------- actions

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermission() {
        boolean ok = hasStorage();
        permissionButton.setText(ok ? "1. File access allowed ✓" : "1. Allow access to files");
        permissionButton.setEnabled(!ok);
    }

    private void askStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        refreshPermission();
    }

    private void findFiles() {
        String url = pageUrl.getText().toString().trim();
        itemHeader.setText("\nLooking for files...");
        itemList.removeAllViews();
        new Thread(() -> {
            try {
                List<Item> found = LinkScraper.scrape(url);
                runOnUiThread(() -> showItems(found));
            } catch (Exception e) {
                runOnUiThread(() -> itemHeader.setText("\nCould not read the page: " + e.getMessage()
                        + "\nCheck your internet, or paste the links into \"Extra links\" below."));
            }
        }).start();
    }

    private void showItems(List<Item> found) {
        items.clear();
        items.addAll(found);
        itemList.removeAllViews();
        if (found.isEmpty()) {
            itemHeader.setText("\nNo download links found on that page. "
                    + "Paste the links into \"Extra links\" below instead.");
            return;
        }
        itemHeader.setText("\nFound " + found.size() + " files. They are installed top to bottom "
                + "(later ones overwrite earlier ones). Untick anything you don't want, e.g. other "
                + "language packs or engines for other systems:");
        for (Item it : found) {
            CheckBox cb = new CheckBox(this);
            cb.setText(it.label);
            cb.setChecked(it.selected);
            cb.setEnabled(it.supported);
            cb.setOnCheckedChangeListener((b, checked) -> it.selected = checked);
            itemList.addView(cb);
        }
    }

    private void start() {
        if (!hasStorage()) {
            Toast.makeText(this, "Please allow file access first (step 1)", Toast.LENGTH_LONG).show();
            return;
        }
        List<Item> chosen = new ArrayList<>();
        for (Item it : items) if (it.selected && it.supported) chosen.add(it);
        int n = 1;
        for (String line : manualLinks.getText().toString().split("\\s+")) {
            line = line.trim();
            if (line.startsWith("http")) chosen.add(new Item("extra link " + n++, line, true, true));
        }
        if (chosen.isEmpty()) {
            Toast.makeText(this, "Nothing to install. Press \"Find the files\" first.", Toast.LENGTH_LONG).show();
            return;
        }
        String target = targetDir.getText().toString().trim();
        prefs.edit()
                .putString("target", target)
                .putString("page", pageUrl.getText().toString().trim())
                .putString("manual", manualLinks.getText().toString())
                .putBoolean("delete", deleteArchives.isChecked())
                .apply();

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < chosen.size(); i++) list.append(i + 1).append(". ").append(chosen.get(i).label).append('\n');
        new AlertDialog.Builder(this)
                .setTitle("Install " + chosen.size() + " files into " + target + "?")
                .setMessage(list + "\nThis is a very big download. Use Wi-Fi and keep the phone charging. "
                        + "You can leave the app; progress shows in the notification.")
                .setPositiveButton("Start", (d, w) -> {
                    Progress.status = "Starting...";
                    Intent i = new Intent(this, InstallService.class)
                            .putExtra(InstallService.EXTRA_ITEMS, Item.toJson(chosen))
                            .putExtra(InstallService.EXTRA_TARGET, target)
                            .putExtra(InstallService.EXTRA_DELETE_ARCHIVES, deleteArchives.isChecked());
                    startForegroundService(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void installApk() {
        File apk = new File(Progress.apkPath);
        if (!apk.exists()) {
            Toast.makeText(this, "The engine APK is missing: " + apk, Toast.LENGTH_LONG).show();
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"Install unknown apps\" for this app, then press the button again",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void resetProgress() {
        if (Progress.running) return;
        new AlertDialog.Builder(this)
                .setMessage("Forget which files were already installed? Everything will be downloaded again "
                        + "next time. Files already in the game folder are not deleted.")
                .setPositiveButton("Forget", (d, w) -> {
                    prefs.edit().putStringSet("done", new HashSet<>()).remove("apk").apply();
                    Progress.apkPath = null;
                    Progress.status = "";
                    Progress.stepPermille = -1;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- view helpers

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText edit(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
