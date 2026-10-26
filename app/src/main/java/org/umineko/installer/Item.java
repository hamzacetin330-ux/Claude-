package org.umineko.installer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One file to download: a resource part, the scripts, the engine APK, etc. */
public class Item {
    public final String label;
    /** Mirrors in the order they should be tried. */
    public final List<String> urls;
    /** Expected md5 from the downloads page, or null. */
    public final String md5;
    /** False when only unsupported mirrors (e.g. Mega) were found for it. */
    public final boolean supported;
    public boolean selected;

    public Item(String label, List<String> urls, String md5, boolean supported, boolean selected) {
        this.label = label;
        this.urls = urls;
        this.md5 = md5;
        this.supported = supported;
        this.selected = selected && supported;
    }

    public Item(String label, String url) {
        this(label, Collections.singletonList(url), null, true, true);
    }

    /** Identifies the item across restarts, whichever mirror ends up being used. */
    public String key() {
        return urls.get(0);
    }

    public static String toJson(List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("label", it.label);
                o.put("urls", new JSONArray(it.urls));
                if (it.md5 != null) o.put("md5", it.md5);
                arr.put(o);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return arr.toString();
    }

    public static List<Item> fromJson(String json) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray u = o.getJSONArray("urls");
                List<String> urls = new ArrayList<>();
                for (int j = 0; j < u.length(); j++) urls.add(u.getString(j));
                items.add(new Item(o.getString("label"), urls, o.optString("md5", null), true, true));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return items;
    }
}
