package com.dushetv.box;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 海报异步加载：降采样 + 有限 LRU + 在途去重，针对 1G 内存盒子控制内存占用 */
public class ImageLoader {
    /** v4.6：40 → 56，翻页回来少一次重新解码（200px RGB_565 每张仅约 0.1MB） */
    private static final int MAX_CACHE = 56;
    private static final LinkedHashMap<String, Bitmap> cache =
            new LinkedHashMap<String, Bitmap>(MAX_CACHE + 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    /** v4.6：同一张图只排一次队。GridView 的 getView 会被反复调用，
     *  以前同一 URL 会重复入队下载，30 张图的队列被撑长，整屏出图明显变慢。 */
    private static final Set<String> inFlight =
            Collections.synchronizedSet(new HashSet<String>());

    /** v4.6：2 → 4 并发，慢图源不再把整个队列堵死 */
    private static final ExecutorService pool = Executors.newFixedThreadPool(6);
    private static final Handler ui = new Handler(Looper.getMainLooper());

    public static void load(final String url, final ImageView iv, final int maxDim) {
        if (url == null || url.length() == 0) return;
        iv.setTag(url);
        Bitmap cached;
        synchronized (cache) {
            cached = cache.get(url);
        }
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }
        // v4.7：先落占位图再判断在途，避免复用视图时残留上一张旧图（旧逻辑在途直接 return）
        iv.setImageResource(android.R.drawable.ic_menu_gallery);
        synchronized (inFlight) {
            if (inFlight.contains(url)) return;
            inFlight.add(url);
        }
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] data = Net.getBytes(url, null);
                        if (data == null || data.length == 0) return;
                        Bitmap bmp = decode(data, maxDim);
                        if (bmp == null) return;
                        synchronized (cache) {
                            cache.put(url, bmp);
                        }
                        final Bitmap fb = bmp;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (url.equals(iv.getTag())) iv.setImageBitmap(fb);
                            }
                        });
                    } catch (Throwable ignored) {
                    } finally {
                        inFlight.remove(url);   // 失败也移除，下次 getView 可重试
                    }
                }
            });
        } catch (Throwable t) {
            inFlight.remove(url);
        }
    }

    private static Bitmap decode(byte[] data, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            int longer = Math.max(w, h);
            while (longer / (sample * 2) > maxDim && sample * 2 <= 16) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565; // 省一半内存
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }
}
