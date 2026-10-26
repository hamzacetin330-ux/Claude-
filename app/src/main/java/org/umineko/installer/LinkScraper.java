package org.umineko.installer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Umineko Project downloads page and pulls out every file, in the order the page
 * lists them (resource parts .001-.012, resource update, scripts, engines, language packs).
 *
 * One row on the page can hold several files, e.g. all twelve resource parts or the engines
 * for every system. Each file's entry ends with "(md5: ..., size: N bytes)", so that is
 * where a row is split. Each file usually has several mirrors; all usable ones are kept so
 * the next can be tried when one fails.
 */
public class LinkScraper {

    private static final Pattern OTHER_PLATFORMS = Pattern.compile(
            "\\b(windows|win32|win64|macos|mac os|osx|linux|ios|iphone|ipad|ps3|vita)\\b");
    private static final Pattern NOT_BY_DEFAULT = Pattern.compile("\\blegacy\\b");
    private static final Pattern END_OF_FILE = Pattern.compile("size:\\s*[\\d,. ]+\\s*bytes\\)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MD5 = Pattern.compile("md5:\\s*([0-9a-fA-F]{32})");
    private static final Pattern SIZE = Pattern.compile("size:\\s*([\\d,. ]+)\\s*bytes", Pattern.CASE_INSENSITIVE);

    public static List<Item> scrape(String pageUrl) throws IOException {
        Document doc = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 14) UminekoInstaller/1.0")
                .timeout(30000)
                .followRedirects(true)
                .get();
        return parse(doc);
    }

    static List<Item> parse(Document doc) {
        // Rows that contain download links, in page order.
        Set<Element> rows = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            if (isDownloadLink(a)) rows.add(rowOf(a));
        }

        List<Item> items = new ArrayList<>();
        for (Element row : rows) {
            // A row's own title (e.g. a first table cell "Resources:") beats the page heading.
            String title = rowTitle(row);
            String heading = title != null ? title : nearestHeading(row);
            for (Segment seg : split(row)) {
                if (title != null) {
                    String t = seg.text.toString().trim();
                    if (t.startsWith(title)) {
                        seg.text.setLength(0);
                        seg.text.append(t.substring(title.length()));
                    }
                }
                Item item = toItem(heading, seg);
                if (item != null) items.add(item);
            }
        }
        return items;
    }

    /** The text and links of one file entry inside a row. */
    private static final class Segment {
        final StringBuilder text = new StringBuilder();
        final List<String> links = new ArrayList<>();
    }

    private static List<Segment> split(Element row) {
        List<Segment> segments = new ArrayList<>();
        Segment[] cur = {new Segment()};
        row.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element && ((Element) node).tagName().equals("a")) {
                    if (isDownloadLink((Element) node)) cur[0].links.add(node.absUrl("href"));
                } else if (node instanceof Element && ((Element) node).tagName().equals("br")) {
                    cur[0].text.append(' ');
                } else if (node instanceof TextNode) {
                    String t = ((TextNode) node).text();
                    Matcher m = END_OF_FILE.matcher(t);
                    int from = 0;
                    while (m.find()) {
                        cur[0].text.append(t, from, m.end());
                        from = m.end();
                        if (!cur[0].links.isEmpty()) {
                            segments.add(cur[0]);
                            cur[0] = new Segment();
                        }
                    }
                    cur[0].text.append(t.substring(from));
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element && ((Element) node).isBlock()) cur[0].text.append(' ');
            }
        });
        if (!cur[0].links.isEmpty()) {
            segments.add(cur[0]);
        } else if (!segments.isEmpty()) {
            // Trailing text without links (e.g. a note) belongs to the last file.
            segments.get(segments.size() - 1).text.append(' ').append(cur[0].text);
        }
        return segments;
    }

    private static Item toItem(String heading, Segment seg) {
        String raw = seg.text.toString().replaceAll("\\s+", " ").trim();
        Matcher md5m = MD5.matcher(raw);
        String md5 = md5m.find() ? md5m.group(1).toLowerCase(Locale.ROOT) : null;

        String label = raw.replaceAll("(?i)\\(?\\s*md5:\\s*[0-9a-f]{32}\\s*,?", "(")
                .replaceAll("(?i)\\bmirror\\s*\\d+\\b", "")
                .replaceAll("\\(\\s*\\(", "(")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+", " ").trim();
        Matcher sm = SIZE.matcher(label);
        if (sm.find()) {
            try {
                long bytes = Long.parseLong(sm.group(1).replaceAll("[^0-9]", ""));
                label = label.substring(0, sm.start()) + String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                        + label.substring(sm.end());
            } catch (NumberFormatException ignored) {
                // keep the original text
            }
        }
        if (label.length() > 160) label = label.substring(0, 160) + "...";
        if (heading != null && !label.toLowerCase(Locale.ROOT).startsWith(heading.toLowerCase(Locale.ROOT))) {
            label = heading.replaceAll(":\\s*$", "") + " - " + label;
        }
        if (label.isEmpty()) label = seg.links.get(0);

        // Best mirrors first; drop unsupported ones and duplicates.
        List<String> urls = new ArrayList<>();
        for (int rank = 0; rank < 3; rank++) {
            for (String u : seg.links) {
                if (rank(u) == rank && !urls.contains(u)) urls.add(u);
            }
        }
        if (urls.isEmpty()) {
            List<String> shown = new ArrayList<>(seg.links);
            return new Item(label + "  (only Mega mirror - not supported, add a link manually)",
                    shown, md5, false, false);
        }
        return new Item(label, urls, md5, true, defaultSelected(label));
    }

    /** 0 = direct / Yandex, 1 = unknown host (tried), 2 = Google Drive, 3 = unsupported. */
    private static int rank(String href) {
        String h = href.toLowerCase(Locale.ROOT);
        if (isYandex(h) || isDirectFile(href)) return 0;
        if (isMega(h) || h.contains("mediafire.com")) return 3;
        if (isGoogleDrive(h)) return 2;
        return 1;
    }

    private static final Pattern MIRROR_TEXT = Pattern.compile("(?i)^\\s*mirror\\s*\\d*\\s*$");

    /** A link to a file, or one labelled "mirror N" (whatever host it is on). */
    static boolean isDownloadLink(Element a) {
        String href = a.absUrl("href");
        if (!href.startsWith("http")) return false;
        return isDownload(href) || MIRROR_TEXT.matcher(a.text()).matches();
    }

    /** Any link that looks like it points at a file to download (any mirror). */
    static boolean isDownload(String href) {
        String h = href.toLowerCase(Locale.ROOT);
        if (isYandex(h) || isDirectFile(href) || isMega(h) || isGoogleDrive(h)
                || h.contains("mediafire.com") || h.contains("pixeldrain.com")) {
            return true;
        }
        return false;
    }

    static boolean isDirectFile(String href) {
        String path;
        try {
            path = URI.create(href).getPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (path == null) return false;
        path = path.toLowerCase(Locale.ROOT);
        return path.endsWith(".zip") || path.endsWith(".7z") || path.endsWith(".apk")
                || path.matches(".*\\.\\d{3}$");
    }

    static boolean isYandex(String href) {
        String h = href.toLowerCase(Locale.ROOT);
        return h.contains("disk.yandex.") || h.contains("yadi.sk") || h.contains("disk.360.yandex.");
    }

    static boolean isMega(String href) {
        return href.contains("mega.nz") || href.contains("mega.co.nz");
    }

    static boolean isGoogleDrive(String href) {
        return href.contains("drive.google.com") || href.contains("docs.google.com/uc")
                || href.contains("drive.usercontent.google.com");
    }

    private static Element rowOf(Element a) {
        Element e = a.parent();
        while (e != null) {
            String tag = e.tagName();
            if (tag.equals("tr") || tag.equals("li") || tag.equals("p") || tag.equals("dd")) return e;
            if (tag.equals("body")) break;
            e = e.parent();
        }
        return a.parent() != null ? a.parent() : a;
    }

    /** Text of the row's first cell / term when it holds no links, without a trailing colon. */
    private static String rowTitle(Element row) {
        Element first = row.children().first();
        if (first == null || !first.select("a[href]").isEmpty()) return null;
        if (!first.tagName().matches("td|th|dt|strong|b|span|label")) return null;
        String t = first.text().trim();
        if (t.isEmpty() || t.length() > 60) return null;
        return t;
    }

    private static String nearestHeading(Element e) {
        Element cur = e;
        while (cur != null) {
            Element prev = cur.previousElementSibling();
            while (prev != null) {
                if (prev.tagName().matches("h[1-5]")) return prev.text().trim();
                Elements inner = prev.select("h1, h2, h3, h4, h5");
                if (!inner.isEmpty()) return inner.last().text().trim();
                prev = prev.previousElementSibling();
            }
            cur = cur.parent();
        }
        return null;
    }

    private static boolean defaultSelected(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (NOT_BY_DEFAULT.matcher(l).find()) return false;
        // Only judge the platform by the file's own text, not the section heading.
        int dash = l.indexOf(" - ");
        String own = dash >= 0 ? l.substring(dash + 3) : l;
        if (own.contains("android")) return true;
        return !OTHER_PLATFORMS.matcher(own).find();
    }
}
