package com.dushetv.box;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏夹：用 SharedPreferences 持久化一个 JSON 数组。
 * key = api + ":" + id，保证同一部片子（同一源）只收藏一次。
 */
public class FavStore {

    public static class Fav {
        public String key;      // api + ":" + id（唯一标识）
        public String id;
        public String name;
        public String pic;
        public String api;
        public int type;
        public String siteName;
        public boolean auto;   // true=观看记录（播放时自动加入）；false=手动收藏
    }

    private static final String PREFS = "box";
    private static final String KEY = "favs";

    public static boolean isFav(Context c, String key) {
        if (c == null || key == null) return false;
        List<Fav> list = getFavs(c);
        for (Fav f : list) {
            // 只认手动收藏；观看记录（auto）不算已收藏
            if (key.equals(f.key) && !f.auto) return true;
        }
        return false;
    }

    public static synchronized void addFav(Context c, Fav f) {
        if (c == null || f == null || f.key == null) return;
        List<Fav> list = getFavs(c);
        for (Fav x : list) {
            if (f.key.equals(x.key)) return; // 已存在，不重复
        }
        list.add(0, f);
        save(c, list);
    }

    /** 播放时自动加入「观看记录」：已存在（无论手动收藏还是记录）都不动，
     *  新增的标记 auto=true，收藏页排在「观看记录」区显示「看到第X集」。 */
    public static synchronized void addAuto(Context c, Fav f) {
        if (c == null || f == null || f.key == null) return;
        List<Fav> list = getFavs(c);
        for (Fav x : list) {
            if (f.key.equals(x.key)) return; // 已存在（手动收藏或已有记录），保持原状
        }
        f.auto = true;
        list.add(0, f);
        save(c, list);
    }

    public static synchronized void removeFav(Context c, String key) {
        if (c == null || key == null) return;
        List<Fav> list = getFavs(c);
        List<Fav> out = new ArrayList<Fav>();
        for (Fav x : list) {
            if (!key.equals(x.key)) out.add(x);
        }
        save(c, out);
    }

    /** 已手动收藏则取消；仅有观看记录（auto）则升级为手动收藏；否则添加 */
    public static synchronized boolean toggle(Context c, Fav f) {
        if (c == null || f == null) return false;
        List<Fav> list = getFavs(c);
        for (int i = 0; i < list.size(); i++) {
            Fav x = list.get(i);
            if (f.key.equals(x.key)) {
                if (x.auto) {
                    // 原是观看记录：升级为手动收藏（收藏页移到下半区）
                    x.auto = false;
                    save(c, list);
                    return true;
                }
                // 已是手动收藏：取消
                list.remove(i);
                save(c, list);
                return false;
            }
        }
        f.auto = false;
        addFav(c, f);
        return true;
    }

    public static synchronized List<Fav> getFavs(Context c) {
        List<Fav> out = new ArrayList<Fav>();
        if (c == null) return out;
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY, "");
        if (s == null || s.length() == 0) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Fav f = new Fav();
                f.key = o.optString("key", "");
                f.id = o.optString("id", "");
                f.id = o.optString("id", f.id);
                f.name = o.optString("name", "");
                f.pic = o.optString("pic", "");
                f.api = o.optString("api", "");
            f.type = o.optInt("type", 1);
            f.siteName = o.optString("siteName", "");
            f.auto = o.optBoolean("auto", false);
            if (f.key.length() > 0) out.add(f);
            }
        } catch (Throwable t) {
            // 损坏数据：忽略
        }
        return out;
    }

    private static void save(Context c, List<Fav> list) {
        try {
            JSONArray a = new JSONArray();
            for (Fav f : list) {
                JSONObject o = new JSONObject();
                o.put("key", f.key);
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("pic", f.pic);
                o.put("api", f.api);
                o.put("type", f.type);
                o.put("siteName", f.siteName);
                o.put("auto", f.auto);
                a.put(o);
            }
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, a.toString()).apply();
        } catch (Throwable t) {
        }
    }
}
