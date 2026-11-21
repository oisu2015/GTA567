package com.dushetv.box;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 播放器全局设置（跨影片持久化）：宽高比、清晰度优先、缓存大小。
 * 与选集进度 WatchStore 分开，避免两类数据混在一起。
 */
public class PlayerPrefs {
    private static final String PREF = "player_prefs";

    // 宽高比：0 原始(留黑边) 1 拉伸全屏 2 16:9 3 4:3
    private static final String K_ASPECT = "aspect";
    // 清晰度：0 自适应 1 4K优先 2 1080优先 3 720优先(流畅)
    private static final String K_CLARITY = "clarity";
    // 缓存：0 关闭 1 256M 2 512M 3 1G
    private static final String K_CACHE = "cache";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int getAspect(Context c) {
        return sp(c).getInt(K_ASPECT, 0); // 默认原始比例（保留黑边，不变形）
    }

    public static void setAspect(Context c, int v) {
        sp(c).edit().putInt(K_ASPECT, v).apply();
    }

    public static int getClarity(Context c) {
        return sp(c).getInt(K_CLARITY, 1); // 默认 4K 优先（清晰度优先）
    }

    public static void setClarity(Context c, int v) {
        sp(c).edit().putInt(K_CLARITY, v).apply();
    }

    public static int getCacheMode(Context c) {
        return sp(c).getInt(K_CACHE, 2); // 默认 512M
    }

    public static void setCacheMode(Context c, int v) {
        sp(c).edit().putInt(K_CACHE, v).apply();
    }

    /** 缓存字节数；mode=0 返回 0 表示关闭 */
    public static long cacheBytes(int mode) {
        switch (mode) {
            case 1: return 256L * 1024 * 1024;
            case 2: return 512L * 1024 * 1024;
            case 3: return 1024L * 1024 * 1024;
            default: return 0;
        }
    }
}
