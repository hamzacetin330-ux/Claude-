package org.umineko.installer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    /** Password of the split resource archive (resources .001 - .012). */
    private static final String DEFAULT_PASSWORD = "035646750436634546568555050";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();

    private SharedPreferences prefs;
    private EditText pageUrl;
    private EditText targetDir;
    private EditText manualLinks;
    private EditText password;
    private CheckBox deleteArchives;
    private RadioGroup coresGroup;
    /** Choices for "Unpacking speed": label and number of cores (0 = auto). */
    private static final String[] CORE_LABELS = {"Auto", "1", "2", "4"};
    private static final int[] CORE_VALUES = {0, 1, 2, 4};
    private LinearLayout itemList;
    private TextView itemHeader;
    private Button permissionButton;
    private Button startButton;
    private Button stopButton;
    private Button apkButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView spaceText;
    private TextView stepText;
    private ProgressBar overallBar;
    private Button findButton;
    private LinearLayout selectRow;
    private LinearLayout advanced;
    private TextView advancedToggle;

    // Colours (match res/values/styles.xml)
    private static final int BG = 0xFF120D18;
    private static final int CARD = 0xFF1E1626;
    private static final int CARD_BORDER = 0xFF3A2A47;
    private static final int GOLD = 0xFFE9C46A;
    private static final int CRIMSON = 0xFF9E1B32;
    private static final int TEXT = 0xFFF2EAF5;
    private static final int MUTED = 0xFFA898B4;
    private static final int OK = 0xFF7BD389;

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
        offerShortcutOnce();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        header.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView title = text("Umineko Installer", 24, GOLD);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titles.addView(title);
        titles.addView(text("Umineko Project for Android, all files in one go", 13, MUTED));
        header.addView(titles);
        root.addView(header);
        root.addView(space(16));

        // 1. Permission
        LinearLayout c1 = card(root, "1", "Allow file access",
                "Needed to put the game files in your storage.");
        permissionButton = primaryButton("Allow access", v -> askStoragePermission());
        c1.addView(permissionButton);

        // 2. Choose files
        LinearLayout c2 = card(root, "2", "Choose the files",
                "Reads the official downloads page and lists every file, in install order.");
        c2.addView(label("Game folder"));
        targetDir = edit(prefs.getString("target",
                new File(Environment.getExternalStorageDirectory(), "ONScripter-RU").getAbsolutePath()));
        c2.addView(targetDir);
        spaceText = text("", 12, MUTED);
        c2.addView(spaceText);
        c2.addView(space(8));
        findButton = primaryButton("Find the files", v -> findFiles());
        c2.addView(findButton);
        itemHeader = text("", 13, MUTED);
        itemHeader.setPadding(0, dp(8), 0, 0);
        c2.addView(itemHeader);
        selectRow = new LinearLayout(this);
        selectRow.addView(linkButton("Select all", v -> selectAll(true)));
        selectRow.addView(linkButton("Select none", v -> selectAll(false)));
        selectRow.setVisibility(View.GONE);
        c2.addView(selectRow);
        itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        c2.addView(itemList);

        // 3. Install
        LinearLayout c3 = card(root, "3", "Download & install",
                "Big download (10+ GB). Use Wi-Fi and keep the device charging. "
                        + "You can leave the app; progress shows in the notification.");
        c3.addView(label("Archive password"));
        password = edit(prefs.getString("password", DEFAULT_PASSWORD));
        c3.addView(password);
        deleteArchives = new CheckBox(this);
        deleteArchives.setText("Delete archives after extracting (saves space)");
        deleteArchives.setTextColor(TEXT);
        deleteArchives.setChecked(prefs.getBoolean("delete", true));
        c3.addView(deleteArchives);

        c3.addView(label("Unpacking speed (CPU cores)"));
        coresGroup = new RadioGroup(this);
        coresGroup.setOrientation(RadioGroup.HORIZONTAL);
        int savedCores = prefs.getInt("threads", 0);
        for (int i = 0; i < CORE_LABELS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(CORE_LABELS[i]);
            rb.setTextColor(TEXT);
            rb.setTextSize(14);
            rb.setTag(CORE_VALUES[i]);
            rb.setPadding(0, 0, dp(10), 0);
            coresGroup.addView(rb);
            if (CORE_VALUES[i] == savedCores) rb.setChecked(true);
        }
        c3.addView(coresGroup);
        int auto = InstallService.autoThreads(this);
        c3.addView(text("More cores unpack faster but use more power and memory. Auto uses "
                + auto + (auto == 1 ? " core" : " cores") + " on this device. Pick 1 core if your phone "
                + "gets hot or the app closes while unpacking.", 12, MUTED));
        c3.addView(space(8));
        startButton = primaryButton("Download & install everything", v -> start());
        c3.addView(startButton);

        stepText = text("", 13, GOLD);
        stepText.setTypeface(Typeface.DEFAULT_BOLD);
        stepText.setPadding(0, dp(12), 0, dp(4));
        c3.addView(stepText);
        overallBar = bar();
        c3.addView(overallBar);
        progressBar = bar();
        c3.addView(progressBar);
        statusText = text("", 13, TEXT);
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, dp(6), 0, 0);
        c3.addView(statusText);
        stopButton = secondaryButton("Stop", v -> {
            InstallService.requestStop();
            stopButton.setText("Stopping...");
            stopButton.setEnabled(false);
        });
        c3.addView(stopButton);

        // 4. Engine
        LinearLayout c4 = card(root, "4", "Install the game engine",
                "When the download is finished, install ONScripter-RU and start the game from it.");
        apkButton = primaryButton("Install ONScripter-RU", v -> installApk());
        c4.addView(apkButton);

        // Advanced (collapsed)
        advancedToggle = text("\u25B8  Advanced options", 14, MUTED);
        advancedToggle.setPadding(dp(4), dp(16), dp(4), dp(8));
        advancedToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText((show ? "\u25BE" : "\u25B8") + "  Advanced options");
        });
        root.addView(advancedToggle);
        advanced = card(root, null, null, null);
        advanced.addView(label("Downloads page"));
        pageUrl = edit(prefs.getString("page", DEFAULT_PAGE));
        advanced.addView(pageUrl);
        advanced.addView(label("Extra links (one per line, installed after the files above)"));
        manualLinks = edit(prefs.getString("manual", ""));
        manualLinks.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        manualLinks.setMinLines(2);
        manualLinks.setSingleLine(false);
        advanced.addView(manualLinks);
        advanced.addView(space(8));
        advanced.addView(secondaryButton("Add to home screen", v -> addShortcut()));
        advanced.addView(secondaryButton("Start over (forget finished downloads)", v -> resetProgress()));
        advanced.setVisibility(View.GONE);

        // Keep it readable on wide screens (tablets, Odin in landscape).
        FrameLayout frame = new FrameLayout(this);
        content = root;
        frame.addView(root, new FrameLayout.LayoutParams(contentWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(frame);
        return scroll;
    }

    private View content;

    private int contentWidth() {
        return Math.min(getResources().getDisplayMetrics().widthPixels, dp(720));
    }

    /** Rotation is handled here (see manifest) so the file list isn't lost. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        lp.width = contentWidth();
        content.setLayoutParams(lp);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean running = Progress.running;
            startButton.setEnabled(!running);
            startButton.setAlpha(running ? 0.5f : 1f);
            stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
            if (!running) {
                stopButton.setText("Stop");
                stopButton.setEnabled(true);
            }
            statusText.setText(Progress.status);
            statusText.setVisibility(TextUtils.isEmpty(Progress.status) ? View.GONE : View.VISIBLE);

            int n = Progress.itemCount;
            boolean showSteps = running && n > 0;
            stepText.setVisibility(showSteps ? View.VISIBLE : View.GONE);
            overallBar.setVisibility(showSteps ? View.VISIBLE : View.GONE);
            if (showSteps) {
                stepText.setText("File " + (Progress.itemIndex + 1) + " of " + n);
                overallBar.setProgress(Progress.itemIndex * 1000 / n);
            }
            int p = Progress.stepPermille;
            progressBar.setIndeterminate(running && p < 0);
            if (p >= 0) progressBar.setProgress(p);
            progressBar.setVisibility(running || p >= 0 ? View.VISIBLE : View.GONE);

            if (running) {
                apkScanned = false;
            } else if (Progress.apkPath == null && !apkScanned) {
                apkScanned = true;
                File found = findApk(new File(targetDir.getText().toString().trim()));
                if (found != null) Progress.apkPath = found.getAbsolutePath();
            }
            boolean apk = Progress.apkPath != null && new File(Progress.apkPath).exists();
            apkButton.setEnabled(!running);
            apkButton.setAlpha(running ? 0.4f : 1f);
            apkButton.setText(running ? "Install ONScripter-RU (after download)"
                    : apk ? "Install ONScripter-RU" : "Choose the ONScripter-RU APK file...");
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
                    "Free space: %.1f GB  \u2022  about 20 GB is needed while unpacking", free / 1e9));
            spaceText.setTextColor(free < 20e9 ? 0xFFE08A8A : MUTED);
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
        permissionButton.setText(ok ? "File access allowed  \u2713" : "Allow access");
        permissionButton.setEnabled(!ok);
        styleFilled(permissionButton, ok ? 0xFF2E4A36 : GOLD, ok ? OK : BG);
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
        itemHeader.setText("Looking for files...");
        itemList.removeAllViews();
        selectRow.setVisibility(View.GONE);
        findButton.setEnabled(false);
        new Thread(() -> {
            try {
                List<Item> found = LinkScraper.scrape(url);
                runOnUiThread(() -> showItems(found));
            } catch (Exception e) {
                runOnUiThread(() -> itemHeader.setText("Could not read the page: " + e.getMessage()
                        + "\nCheck your internet, or paste links under Advanced options."));
            } finally {
                runOnUiThread(() -> findButton.setEnabled(true));
            }
        }).start();
    }

    private final List<CheckBox> boxes = new ArrayList<>();

    private void showItems(List<Item> found) {
        items.clear();
        items.addAll(found);
        itemList.removeAllViews();
        boxes.clear();
        if (found.isEmpty()) {
            itemHeader.setText("No download links found on that page. "
                    + "Paste the links under Advanced options instead.");
            return;
        }
        itemHeader.setText("Found " + found.size() + " files. They are installed top to bottom; "
                + "untick anything you don't need.");
        selectRow.setVisibility(View.VISIBLE);
        String section = null;
        for (Item it : found) {
            int dash = it.label.indexOf(" - ");
            String sec = dash > 0 ? it.label.substring(0, dash) : "";
            String name = dash > 0 ? it.label.substring(dash + 3) : it.label;
            if (!sec.equals(section)) {
                section = sec;
                if (!sec.isEmpty()) {
                    TextView h = text(sec.toUpperCase(Locale.ROOT), 12, GOLD);
                    h.setTypeface(Typeface.DEFAULT_BOLD);
                    h.setLetterSpacing(0.08f);
                    h.setPadding(0, dp(14), 0, dp(2));
                    itemList.addView(h);
                }
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(name);
            cb.setTextColor(it.supported ? TEXT : MUTED);
            cb.setTextSize(14);
            cb.setPadding(dp(4), dp(6), 0, dp(6));
            cb.setChecked(it.selected);
            cb.setEnabled(it.supported);
            cb.setOnCheckedChangeListener((b, checked) -> it.selected = checked);
            if (it.supported) boxes.add(cb);
            itemList.addView(cb);
        }
    }

    private int chosenCores() {
        View checked = coresGroup.findViewById(coresGroup.getCheckedRadioButtonId());
        return checked != null && checked.getTag() instanceof Integer ? (Integer) checked.getTag() : 0;
    }

    private void selectAll(boolean on) {
        for (CheckBox cb : boxes) cb.setChecked(on);
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
            if (line.startsWith("http")) chosen.add(new Item("extra link " + n++, line));
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
                .putString("password", password.getText().toString().trim())
                .putInt("threads", chosenCores())
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
                            .putExtra(InstallService.EXTRA_DELETE_ARCHIVES, deleteArchives.isChecked())
                            .putExtra(InstallService.EXTRA_PASSWORD, password.getText().toString().trim())
                            .putExtra(InstallService.EXTRA_THREADS, chosenCores());
                    startForegroundService(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean apkScanned;
    private static final int PICK_APK = 7;

    /**
     * Looks for an engine APK in the game folder (and two levels below), e.g. one that came
     * inside a zip and was extracted with the game files. Newest wins.
     */
    private static File findApk(File dir) {
        File best = null;
        java.util.ArrayDeque<File[]> todo = new java.util.ArrayDeque<>();
        todo.add(new File[]{dir});
        for (int depth = 0; depth < 3 && !todo.isEmpty(); depth++) {
            java.util.ArrayDeque<File[]> next = new java.util.ArrayDeque<>();
            for (File[] level : todo) {
                for (File d : level) {
                    File[] files = d.listFiles();
                    if (files == null) continue;
                    List<File> subdirs = new ArrayList<>();
                    for (File f : files) {
                        if (f.isDirectory()) {
                            // The game's own folders hold tens of thousands of files; skip them.
                            String n = f.getName().toLowerCase(Locale.ROOT);
                            if (!n.equals("sound") && !n.equals("voice") && !n.equals("bgm") && !n.equals("se")
                                    && !n.equals("graphics") && !n.equals("video")) {
                                subdirs.add(f);
                            }
                        } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")
                                && (best == null || f.lastModified() > best.lastModified())) {
                            best = f;
                        }
                    }
                    next.add(subdirs.toArray(new File[0]));
                }
            }
            todo = next;
        }
        return best;
    }

    private void installApk() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow \"Install unknown apps\" for this app, then press the button again",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        File apk = Progress.apkPath != null ? new File(Progress.apkPath) : null;
        if (apk == null || !apk.exists()) {
            // No engine was downloaded (or it wasn't found): let the user pick the APK file.
            Toast.makeText(this, "Pick the ONScripter-RU .apk file", Toast.LENGTH_SHORT).show();
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/vnd.android.package-archive", "application/octet-stream"});
            startActivityForResult(pick, PICK_APK);
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(data.getData(), "application/vnd.android.package-archive")
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

    // ---------------------------------------------------------------- home screen shortcut

    /** Some launchers (e.g. on handhelds) don't show new apps on the home screen; offer it once. */
    private void offerShortcutOnce() {
        if (prefs.getBoolean("shortcut_offered", false)) return;
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return;
        prefs.edit().putBoolean("shortcut_offered", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Add to home screen?")
                .setMessage("Put an Umineko Installer icon on your home screen so it's easy to find later.")
                .setPositiveButton("Add", (d, w) -> addShortcut())
                .setNegativeButton("No thanks", null)
                .show();
    }

    private void addShortcut() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Your launcher doesn't allow adding shortcuts. "
                    + "Look for Umineko Installer in the app list.", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo info = new ShortcutInfo.Builder(this, "open")
                .setShortLabel(getString(R.string.app_name))
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(new Intent(Intent.ACTION_MAIN)
                        .setClass(this, MainActivity.class)
                        .addCategory(Intent.CATEGORY_LAUNCHER))
                .build();
        sm.requestPinShortcut(info, null);
    }

    // ---------------------------------------------------------------- view helpers

    /** A rounded card with an optional numbered title; returns the container to add content to. */
    private LinearLayout card(LinearLayout parent, String number, String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), CARD_BORDER);
        card.setBackground(bg);

        if (title != null) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView num = text(number, 14, BG);
            num.setTypeface(Typeface.DEFAULT_BOLD);
            num.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(GOLD);
            num.setBackground(circle);
            head.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView t = text(title, 18, TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(12), 0, 0, 0);
            head.addView(t);
            card.addView(head);
            if (subtitle != null) {
                TextView sub = text(subtitle, 13, MUTED);
                sub.setPadding(0, dp(6), 0, dp(10));
                card.addView(sub);
            }
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, MUTED);
        t.setPadding(dp(4), dp(8), 0, 0);
        return t;
    }

    private EditText edit(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setBackgroundTintList(ColorStateList.valueOf(CARD_BORDER));
        return e;
    }

    private ProgressBar bar() {
        ProgressBar b = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        b.setMax(1000);
        b.setProgressTintList(ColorStateList.valueOf(GOLD));
        b.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_BORDER));
        return b;
    }

    private Button primaryButton(String label, View.OnClickListener l) {
        Button b = baseButton(label, l);
        styleFilled(b, GOLD, BG);
        return b;
    }

    private Button secondaryButton(String label, View.OnClickListener l) {
        Button b = baseButton(label, l);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), CRIMSON);
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setTextColor(0xFFE6A0AE);
        return b;
    }

    private Button linkButton(String label, View.OnClickListener l) {
        Button b = baseButton(label, l);
        b.setBackground(null);
        b.setTextColor(GOLD);
        b.setTextSize(13);
        b.setMinHeight(0);
        b.setPadding(dp(4), dp(4), dp(16), dp(4));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private Button baseButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleFilled(Button b, int fill, int textColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(fill);
        b.setBackground(bg);
        b.setTextColor(textColor);
    }

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
