package org.umineko.installer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One thing to download: a resource pack, the scripts, the engine APK, etc. */
public class Item {
    public final String label;
    public final String url;
    /** False when only an unsupported mirror (e.g. Mega) was found for it. */
    public final boolean supported;
    public boolean selected;

    public Item(String label, String url, boolean supported, boolean selected) {
        this.label = label;
        this.url = url;
        this.supported = supported;
        this.selected = selected && supported;
    }

    public static String toJson(List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("label", it.label);
                o.put("url", it.url);
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
                items.add(new Item(o.getString("label"), o.getString("url"), true, true));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return items;
    }
}
