package com.dushetv.box;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接口 / 视频源测速。
 * 老盒子内存只有 ~1G，所以：
 * 1) 并发线程数很低（默认 4）
 * 2) 每个探针只读前 24KB 就断开，绝不整包下载
 * 3) 全程不解析大 JSON，只做特征字符串计数
 */
public final class SpeedTest {

    /** 单条待测目标 */
    public static class Item {
        public String name;
        public String url;
        public long ms = -1;        // 响应耗时，-1 表示未测/失败
        public boolean ok = false;  // 是否连通且像有效配置
        public String note = "未测"; // 展示用的备注
        public int sites = -1;      // 粗略估算的站点数
        public Object tag;          // 调用方回查用（例如对应的 Site 对象）

        public Item(String name, String url) {
            this.name = name;
            this.url = url;
        }

        /** 列表展示文本 */
        public String label() {
            if (ms < 0 && !ok) return name + "   [" + note + "]";
            if (!ok) return name + "   [" + note + " " + ms + "ms]";
            String extra = (sites > 0) ? ("  " + sites + "源") : "";
            return name + "   " + ms + "ms" + extra;
        }
    }

    public interface Listener {
        void onProgress(int done, int total, Item justDone);

        void onFinish(List<Item> sortedResult);
    }

    private SpeedTest() {
    }

    // v4.7：5s → 3s。测速是「找最快源」，不通的源应尽快判负，别让用户干等
    private static final int CONN_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 3000;
    private static final int MAX_BYTES = 24 * 1024;

    /**
     * 异步测速。回调都在主线程。
     */
    public static void run(final List<Item> items, int threads, final Listener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final int total = items.size();
        if (total == 0) {
            listener.onFinish(new ArrayList<Item>());
            return;
        }
        final int nThreads = Math.max(1, Math.min(threads, 6));
        final ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        final AtomicInteger done = new AtomicInteger(0);

        for (int i = 0; i < total; i++) {
            final Item it = items.get(i);
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        measure(it);
                    } catch (Throwable t) {
                        it.ok = false;
                        it.note = "异常";
                    }
                    final int d = done.incrementAndGet();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onProgress(d, total, it);
                        }
                    });
                    if (d >= total) {
                        final List<Item> sorted = new ArrayList<Item>(items);
                        sort(sorted);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onFinish(sorted);
                            }
                        });
                        pool.shutdown();
                    }
                }
            });
        }
    }

    /** 单条测量：连通性 + 耗时 + 是否像 TVBox 配置 */
    private static void measure(Item it) {
        Net.Probe p = Net.probe(it.url, CONN_TIMEOUT, READ_TIMEOUT, MAX_BYTES);
        it.ms = p.ms;
        if (!p.ok) {
            it.ok = false;
            it.note = (p.note == null || p.note.length() == 0) ? "失败" : p.note;
            return;
        }
        String h = p.head == null ? "" : p.head;
        String low = h.toLowerCase();
        // 明显是网页而不是配置
        if (low.indexOf("<!doctype html") >= 0 || low.indexOf("<html") >= 0) {
            it.ok = false;
            it.note = "非配置(网页)";
            return;
        }
        if (h.trim().length() == 0) {
            it.ok = false;
            it.note = "空响应";
            return;
        }
        int apiCount = count(h, "\"api\"");
        int siteKey = h.indexOf("\"sites\"");
        if (siteKey >= 0 || apiCount > 0) {
            it.ok = true;
            it.sites = apiCount;
            it.note = "可用";
        } else if (h.startsWith("{") || h.startsWith("[")) {
            // 可能是加密/压缩配置，仍算连通
            it.ok = true;
            it.note = "连通";
        } else {
            it.ok = true;
            it.note = "连通(格式待确认)";
        }
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }

    /** 可用的按耗时升序在前，失败的按名字排在后面 */
    public static void sort(List<Item> list) {
        Collections.sort(list, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.ok != b.ok) return a.ok ? -1 : 1;
                if (!a.ok) return 0;
                long d = a.ms - b.ms;
                return d < 0 ? -1 : (d > 0 ? 1 : 0);
            }
        });
    }

    /** 取第一个可用项，没有则返回 null */
    public static Item best(List<Item> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).ok) return sorted.get(i);
        }
        return null;
    }
}
