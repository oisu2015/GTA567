package com.dushetv.box;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 选集观看进度：记录某部剧（vodKey）每集是否「看过」，以及当前停在哪集。
 * 用于详情页选集按钮着色：当前集=黄、看过=淡红、未看=绿。
 */
public class WatchStore {
    private static final String PREF = "watch_store";
    private static final String KEY_WATCHED = "watched_";   // watched_<vodKey> -> Set<"src:ep">
    private static final String KEY_CURRENT = "current_";   // current_<vodKey> -> "src:ep"

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isWatched(Context c, String vodKey, int src, int ep) {
        Set<String> s = sp(c).getStringSet(KEY_WATCHED + vodKey, new HashSet<String>());
        return s.contains(src + ":" + ep);
    }

    public static void markWatched(Context c, String vodKey, int src, int ep) {
        if (vodKey == null || vodKey.length() == 0) return;
        Set<String> s = new HashSet<String>(sp(c).getStringSet(KEY_WATCHED + vodKey, new HashSet<String>()));
        s.add(src + ":" + ep);
        sp(c).edit().putStringSet(KEY_WATCHED + vodKey, s).apply();
    }

    /** 返回该线路下「当前集」的集序号；无记录返回 -1 */
    public static int getCurrentEp(Context c, String vodKey, int src) {
        String v = sp(c).getString(KEY_CURRENT + vodKey, "");
        String prefix = src + ":";
        if (v != null && v.startsWith(prefix)) {
            try {
                return Integer.parseInt(v.substring(prefix.length()));
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    public static void setCurrent(Context c, String vodKey, int src, int ep) {
        if (vodKey == null || vodKey.length() == 0) return;
        sp(c).edit().putString(KEY_CURRENT + vodKey, src + ":" + ep).apply();
    }

    /** 不区分线路，直接返回该影片「当前停在第几集」（0 基）；无记录返回 -1。
     *  供收藏页「看到第X集」显示用。 */
    public static int getCurrentEpAny(Context c, String vodKey) {
        if (vodKey == null || vodKey.length() == 0) return -1;
        String v = sp(c).getString(KEY_CURRENT + vodKey, "");
        if (v == null || v.length() == 0) return -1;
        int i = v.lastIndexOf(':');
        if (i < 0 || i + 1 >= v.length()) return -1;
        try {
            return Integer.parseInt(v.substring(i + 1));
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------- 断点续播：每集/每片的播放位置 ----------

    private static final String KEY_POS = "pos_";   // pos_<key> -> 毫秒位置

    /** 取某集/某片的续播位置（毫秒）；无记录返回 0 */
    public static long getPosition(Context c, String key) {
        if (key == null || key.length() == 0) return 0;
        return sp(c).getLong(KEY_POS + key, 0);
    }

    public static void setPosition(Context c, String key, long ms) {
        if (key == null || key.length() == 0) return;
        if (ms < 0) ms = 0;
        sp(c).edit().putLong(KEY_POS + key, ms).apply();
    }
}
