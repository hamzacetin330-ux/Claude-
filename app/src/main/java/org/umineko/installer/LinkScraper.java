package org.umineko.installer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the Umineko Project downloads page and pulls out every download link, in the
 * order the page lists them (resources 1..12, update, scripts, engine, language packs).
 * Each row on the page usually has several mirrors; we keep one supported mirror per row.
 */
public class LinkScraper {

    private static final Pattern OTHER_PLATFORMS = Pattern.compile(
            "\\b(windows|win32|win64|macos|mac os|osx|linux|ios|iphone|ipad|ps3|vita)\\b");

    public static List<Item> scrape(String pageUrl) throws IOException {
        Document doc = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 14) UminekoInstaller/1.0")
                .timeout(30000)
                .followRedirects(true)
                .get();

        // Group links by the row/list item/paragraph they sit in, keeping page order.
        Map<Element, List<Element>> groups = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (!isDownload(href)) continue;
            Element row = rowOf(a);
            groups.computeIfAbsent(row, k -> new ArrayList<>()).add(a);
        }

        List<Item> items = new ArrayList<>();
        for (Map.Entry<Element, List<Element>> g : groups.entrySet()) {
            String label = labelFor(g.getKey(), g.getValue().get(0));
            Element best = null;
            for (Element a : g.getValue()) {
                if (isSupported(a.absUrl("href"))) {
                    best = a;
                    break;
                }
            }
            if (best != null) {
                items.add(new Item(label, best.absUrl("href"), true, defaultSelected(label, best.absUrl("href"))));
            } else {
                items.add(new Item(label + "  (only Mega/Drive mirror - not supported, add a link manually)",
                        g.getValue().get(0).absUrl("href"), false, false));
            }
        }
        return items;
    }

    /** Any link that looks like it points at a file to download (any mirror). */
    static boolean isDownload(String href) {
        String h = href.toLowerCase(Locale.ROOT);
        return isSupported(href) || h.contains("mega.nz") || h.contains("mega.co.nz")
                || h.contains("drive.google.com") || h.contains("mediafire.com");
    }

    /** Links this app can actually download: Yandex Disk public links and direct file links. */
    static boolean isSupported(String href) {
        String h = href.toLowerCase(Locale.ROOT);
        if (isYandex(h)) return true;
        String path;
        try {
            path = URI.create(href).getPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (path == null) return false;
        path = path.toLowerCase(Locale.ROOT);
        return path.endsWith(".zip") || path.endsWith(".7z") || path.endsWith(".apk");
    }

    static boolean isYandex(String href) {
        String h = href.toLowerCase(Locale.ROOT);
        return h.contains("disk.yandex.") || h.contains("yadi.sk") || h.contains("disk.360.yandex.");
    }

    private static Element rowOf(Element a) {
        Element e = a.parent();
        while (e != null) {
            String tag = e.tagName();
            if (tag.equals("tr") || tag.equals("li") || tag.equals("p") || tag.equals("dd")) return e;
            if (tag.equals("body")) break;
            e = e.parent();
        }
        return a;
    }

    private static String labelFor(Element row, Element firstLink) {
        String heading = nearestHeading(row);
        String text = row == firstLink ? firstLink.text() : row.text();
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > 140) text = text.substring(0, 140) + "...";
        if (heading != null && !text.toLowerCase(Locale.ROOT).contains(heading.toLowerCase(Locale.ROOT))) {
            return heading + " - " + text;
        }
        return text.isEmpty() ? firstLink.absUrl("href") : text;
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

    private static boolean defaultSelected(String label, String href) {
        String l = (label + " " + href).toLowerCase(Locale.ROOT);
        if (l.contains("android")) return true;
        return !OTHER_PLATFORMS.matcher(l).find();
    }
}
