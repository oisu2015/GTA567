package com.dushetv.box;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * v1.6 播放器。相对 v1.5 修掉三个直接导致「黑屏且无任何提示」的问题：
 * <p>
 * 1. 监听器时序：v1.5 在 prepare() 之后才 addListener，ExoPlayer 早期派发的
 * BUFFERING / ERROR 事件全部丢失 —— 播放失败时界面上什么都不会变，就是黑屏。
 * 2. TLS：ExoPlayer 的 DefaultHttpDataSource 走系统 HttpURLConnection，
 * 拿不到 Net 里的宽松 SSL 策略，老盒子对现代 HTTPS 源直接握手失败。
 * 3. 跨协议重定向：大量 m3u8 会在 http/https 之间 302，ExoPlayer 默认拒绝跟随。
 * <p>
 * 另外内置诊断 HUD、双引擎自动回退、测试片自检，真机出问题时能直接在屏幕上看到卡在哪一环。
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final int ENGINE_EXO = 0;
    private static final int ENGINE_MP = 1;

    // 宽高比：0 原始(留黑边不变形) 1 拉伸全屏 2 16:9 3 4:3
    private static final int ASPECT_ORIGINAL = 0;
    private static final int ASPECT_STRETCH = 1;
    private static final int ASPECT_16_9 = 2;
    private static final int ASPECT_4_3 = 3;

    // 清晰度优先：0 自适应 1 4K优先 2 1080优先 3 720优先(流畅)
    private static final int CLARITY_AUTO = 0;
    private static final int CLARITY_4K = 1;
    private static final int CLARITY_1080 = 2;
    private static final int CLARITY_720 = 3;

    /** 公开可用的标准 HLS 测试流，用于区分「播放器坏」还是「源坏」 */
    private static final String TEST_URL =
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8";

    private SurfaceView sv;
    private TextView hud;
    private TextView tip;
    private FrameLayout root;

    private SimpleExoPlayer exo;
    private MediaPlayer mp;
    private SurfaceHolder holder;

    private String url, name, referer;
    private int engine = ENGINE_EXO;
    private boolean surfaceReady = false;
    private boolean isPaused = false;
    private boolean hudVisible = false;   // 诊断 HUD 当前是否可见
    private boolean hudManual = false;    // v3.0.2：MENU 手动查看完整日志（不被瞬态提示覆盖）
    private boolean autoFellBack = false;
    private boolean gotPicture = false;

    /** 跨会话的磁盘缓存（分片落盘，切集/网络抖动后不再反复拉流） */
    private static SimpleCache sCache;
    private static long sCacheBytes = -1;   // 已构建缓存大小（-1 未构建；0 已按「关闭」处理）

    // ---------- 控制条（遥控可操作的按钮组）----------
    private static final int CTRL_REWIND = 0;     // 快退
    private static final int CTRL_PAUSE = 1;      // 播放/暂停
    private static final int CTRL_FF = 2;         // 快进
    private static final int CTRL_PREV = 3;       // 上一集
    private static final int CTRL_NEXT = 4;       // 下一集
    private static final int CTRL_EXIT = 5;       // 退出
    private static final int CTRL_AR = 6;         // 宽高比
    private static final int CTRL_CLARITY = 7;    // 清晰度优先
    private static final int CTRL_CACHE = 8;      // 缓存大小
    private TextView[] ctrlBtns = new TextView[9];
    private int ctrlFocus = CTRL_PAUSE;
    private LinearLayout ctrlWrap;
    private boolean ctrlVisible = false;

    // ---------- 选集（用于上一集/下一集）----------
    private ArrayList<String> epUrls = new ArrayList<String>();
    private ArrayList<String> epNames = new ArrayList<String>();
    private int curEp = 0;
    private String vodKey = "";     // 用于记录选集观看进度（当前集=金、看过=灰）
    private int curSrc = 0;

    // ---------- 影片信息（用于自动写入「观看记录」）----------
    private String fVodId = "", fVodName = "", fPic = "", fApi = "", fSiteName = "";
    private int fType = 1;

    // 断点续播
    private String resumeKey = "";  // 续播定位键：选集用 vodKey#src#ep，单片用 url#url
    private long resumePos = 0;     // 本次应续播到的毫秒位置

    // 宽高比 / 清晰度（来自全局设置，运行期可变）
    private int aspectMode = ASPECT_ORIGINAL;
    private int clarityMode = CLARITY_4K;
    private int videoW = 0, videoH = 0;   // 视频原始宽高，用于按原始比例留黑边
    private DefaultTrackSelector ts;      // 提升为字段，便于运行期切换清晰度

    private final StringBuilder logBuf = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long t0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 让 ExoPlayer 内部的 HttpURLConnection 也用上放宽后的 TLS，否则老盒子 https 源必挂
        Net.installGlobalTls();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        t0 = SystemClock.elapsedRealtime();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);

        sv = new SurfaceView(this);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hud = new TextView(this);
        hud.setTextColor(0xffe8f4ff);
        hud.setBackgroundColor(0xb0000000);
        hud.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hud.setPadding(18, 14, 18, 14);
        hud.setLineSpacing(0f, 1.15f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;          // v3.0.2：诊断/提示移到视频最底下单行显示
        hp.leftMargin = 20;
        hp.rightMargin = 20;
        hp.bottomMargin = 20;
        root.addView(hud, hp);
        hud.setVisibility(android.view.View.GONE);   // 默认隐藏，仅缓存中/出错时显示最底一行

        tip = new TextView(this);
        tip.setTextColor(0xffffd479);
        tip.setBackgroundColor(0xb0000000);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(18, 10, 18, 10);
        tip.setText("←→↑↓=移动选择  确定=执行  右/左边缘=快进·下一集/快退·上一集  下=设置(宽高比·清晰度·缓存)  0=测试片  MENU=信息  返回=退出");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tp.bottomMargin = 90;
        root.addView(tip, tp);

        // 控制条：两行三列的可遥控按钮组，方向键在其中移动焦点、确定执行
        buildCtrlBar(root);

        setContentView(root);

        Intent in = getIntent();
        url = Net.normalize(in.getStringExtra("url"));
        name = in.getStringExtra("name");
        referer = in.getStringExtra("referer");

        // 接收选集列表，支持「上一集 / 下一集」
        try {
            String ej = in.getStringExtra("epsJson");
            if (ej != null && ej.length() > 2) {
                org.json.JSONArray a = new org.json.JSONArray(ej);
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    epUrls.add(o.optString("url", ""));
                    epNames.add(o.optString("name", ""));
                }
            }
            curEp = in.getIntExtra("epIndex", 0);
            vodKey = in.getStringExtra("vodKey");
            curSrc = in.getIntExtra("curSrc", 0);
        } catch (Throwable t) {
            epUrls.clear();
            epNames.clear();
        }

        // 影片信息：播放时自动写入「观看记录」（收藏页上半区，显示看到第几集）
        fVodId = in.getStringExtra("vodId");
        fVodName = in.getStringExtra("vodName");
        fPic = in.getStringExtra("pic");
        fApi = in.getStringExtra("api");
        fType = in.getIntExtra("type", 1);
        fSiteName = in.getStringExtra("siteName");

        log("设备 " + Build.MODEL + "  Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        log("片名 " + (name == null ? "-" : name));
        log("地址 " + shorten(url, 96));
        if (url == null || url.length() == 0) {
            log("地址为空，无法播放。请返回重选线路。");
        }

        holder = sv.getHolder();
        holder.addCallback(this);

        // 载入全局播放设置（宽高比 / 清晰度优先），运行期可经控制条按钮切换
        aspectMode = PlayerPrefs.getAspect(this);
        clarityMode = PlayerPrefs.getClarity(this);
    }

    // ---------------- Surface ----------------

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        if (!surfaceReady) {
            surfaceReady = true;
            start(engine);
            preflight();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
        holder = h;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
    }

    // ---------------- 链路预检 ----------------

    /**
     * 与播放并行地拉一小段内容，把 HTTP 状态码、耗时、首行特征打到 HUD。
     * 这样即使播放器完全没反应，也能立刻分清是「网络到不了」还是「拿得到但解不了」。
     */
    private void preflight() {
        if (url == null || url.length() == 0) return;
        final String target = url;
        new AsyncTask<Void, Void, Net.Probe>() {
            @Override
            protected Net.Probe doInBackground(Void... v) {
                return Net.probe(target, 8000, 8000, 1024);
            }

            @Override
            protected void onPostExecute(Net.Probe p) {
                if (p == null) {
                    log("预检 无结果");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("预检 HTTP ").append(p.code).append("  ").append(p.ms).append("ms");
                if (!p.ok) sb.append("  失败:").append(p.note);
                String head = p.head == null ? "" : p.head.trim();
                if (head.length() > 0) {
                    String first = head.split("\\r?\\n")[0];
                    if (first.length() > 60) first = first.substring(0, 60);
                    sb.append("\n     首行 ").append(first);
                    if (head.startsWith("#EXTM3U")) {
                        int seg = countOf(head, "#EXTINF");
                        int var = countOf(head, "#EXT-X-STREAM-INF");
                        sb.append("\n     M3U8 正常，分片 ").append(seg)
                                .append(" 条，多码率 ").append(var).append(" 组");
                    } else if (head.toLowerCase().contains("<html")) {
                        sb.append("\n     返回的是网页而非视频，该线路多半已失效或需要解析");
                    }
                } else if (p.ok) {
                    sb.append("  (二进制流)");
                }
                log(sb.toString());
            }
        }.execute();
    }

    private static int countOf(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    // ---------------- 引擎调度 ----------------

    private void start(int eng) {
        releaseAll();
        engine = eng;
        isPaused = false;
        gotPicture = false;
        if (url == null || url.length() == 0) return;

        // 续播定位键：选集用 vodKey#src#ep，单片(测试片/无选集)用 url#url
        resumeKey = (vodKey != null && vodKey.length() > 0)
                ? (vodKey + "#" + curSrc + "#" + curEp)
                : ("url#" + url);
        resumePos = WatchStore.getPosition(this, resumeKey);

        // 记录观看进度：当前集置金、本集标记已看（详情页选集着色用）
        if (vodKey != null && vodKey.length() > 0) {
            WatchStore.setCurrent(this, vodKey, curSrc, curEp);
            WatchStore.markWatched(this, vodKey, curSrc, curEp);
            // 自动加入「观看记录」（收藏页上半区）；已手动收藏的保持不动
            try {
                FavStore.Fav f = new FavStore.Fav();
                f.key = vodKey;
                f.id = fVodId;
                f.name = fVodName;
                f.pic = fPic;
                f.api = fApi;
                f.type = fType;
                f.siteName = fSiteName;
                FavStore.addAuto(this, f);
            } catch (Throwable ignored) {
            }
        }

        // 按设置构建磁盘缓存（关闭/256M/512M/1G）
        ensureCache();

        // 应用宽高比（拉伸/16:9/4:3 立即可见；原始比例待出画后按视频尺寸计算）
        applyAspect();

        log("启动引擎 " + engineName(eng)
                + "  | 续播 " + (resumePos > 0 ? fmt(resumePos) : "从头"));
        if (eng == ENGINE_EXO) {
            startExo();
        } else {
            startMp();
        }

        // 启动位置周期保存（每 5s），支撑断点续播
        ui.removeCallbacks(posSaver);
        ui.postDelayed(posSaver, 5000);
    }

    private static String engineName(int e) {
        return e == ENGINE_EXO ? "ExoPlayer(内置软/硬解)" : "系统 MediaPlayer";
    }

    private void startExo() {
        try {
            // allowCrossProtocolRedirects=true：很多 m3u8 会在 http/https 之间 302，
            // 默认值 false 会让 ExoPlayer 直接放弃并报 source error。
            DefaultHttpDataSourceFactory dsf =
                    new DefaultHttpDataSourceFactory(Net.DEFAULT_UA, 20000, 20000, true);
            if (referer != null && referer.length() > 0) {
                dsf.getDefaultRequestProperties().set("Referer", referer);
            }

            // 磁盘缓存：已下载的分片落盘（最多 256MB，LRU 淘汰），
            // 切集 / 网络抖动后不再反复向源站拉流，从根本上缓解「反复缓存」。
            DataSource.Factory upstream = dsf;
            if (sCache != null) {
                upstream = new CacheDataSourceFactory(sCache, dsf,
                        CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            }

            // 清晰度优先：按全局设置（自适应 / 4K优先 / 1080优先 / 720优先）选择码率档，
            // 地板不低于 480p，避免电视盒子首段带宽估算低而选到 240p/360p 的惨剧。
            DefaultTrackSelector ts = new DefaultTrackSelector(this);
            ts.setParameters(buildClarityParameters());
            this.ts = ts;

            // 更大缓冲：起播前缓存 20s、健康维持 50s、卡顿后需回填 40s 再续播；
            // 进一步抑制「反复缓存」，电视上尽量一次缓冲到位。
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50000, 120000, 20000, 40000)
                    .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
                    .build();

            Uri uri = Uri.parse(url);
            MediaSource src;
            String low = url.toLowerCase();
            if (low.contains(".m3u8") || low.contains("m3u8")) {
                src = new HlsMediaSource.Factory(upstream).createMediaSource(uri);
                log("容器 HLS(m3u8)");
            } else {
                src = new ProgressiveMediaSource.Factory(upstream).createMediaSource(uri);
                log("容器 直链(mp4/flv 等)");
            }

            exo = new SimpleExoPlayer.Builder(this)
                    .setTrackSelector(ts)
                    .setLoadControl(lc)
                    .build();
            // 关键：先注册监听再 prepare，否则早期的错误/状态回调收不到，界面就只剩黑屏
            exo.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int state) {
                    if (state == Player.STATE_BUFFERING) {
                        log("缓冲中…");
                        showTransient("缓存中…");   // 视频最底下单行提示加载
                    } else if (state == Player.STATE_READY) {
                        onExoReady();
                    } else if (state == Player.STATE_ENDED) {
                        log("播放结束");
                        hideTransient();
                    }
                }

                @Override
                public void onPlayerError(ExoPlaybackException e) {
                    log("ExoPlayer 报错 → " + describe(e));
                    showHud();
                    autoFallback();
                }
            });
            exo.setVideoSurfaceHolder(holder);
            exo.setMediaSource(src);
            exo.prepare();
            exo.setPlayWhenReady(true);

            // 卡死看门狗：20s 内还没出画，说明加载异常，自动亮出诊断面板
            ui.removeCallbacks(diagWatch);
            ui.postDelayed(diagWatch, 20000);
        } catch (Throwable t) {
            log("ExoPlayer 初始化失败 → " + t.getClass().getSimpleName() + ": " + t.getMessage());
            showHud();
            autoFallback();
        }
    }

    private void onExoReady() {
        try {
            Format vf = exo.getVideoFormat();
            Format af = exo.getAudioFormat();
            StringBuilder sb = new StringBuilder("已就绪  ");
            if (vf != null) {
                gotPicture = true;
                videoW = vf.width;
                videoH = vf.height;
                sb.append("视频 ").append(vf.sampleMimeType)
                        .append(" ").append(vf.width).append("x").append(vf.height);
            } else {
                sb.append("视频 无轨道");
            }
            sb.append("   音频 ").append(af == null ? "无轨道" : af.sampleMimeType);
            log(sb.toString());
            if (vf == null) {
                log("拿到流但没有视频轨 —— 黑屏正常，多半是该线路只有音频或编码不被识别");
            } else {
                applyAspect();   // 按视频原始比例居中并留黑边
                hideTransient();   // 出画即收起「缓存中…」
                scheduleHudFade();
                // 断点续播：跳到上次中断处（排除片头片尾 10s，避免「差一点看完又从头」）
                if (resumePos > 5000) {
                    long dur = exo.getDuration();
                    if (dur > 0 && resumePos < dur - 10000) {
                        exo.seekTo(resumePos);
                        log("→ 续播 从 " + fmt(resumePos));
                    } else {
                        WatchStore.setPosition(this, resumeKey, 0); // 已看完，清续播点
                    }
                    resumePos = 0;
                }
            }
        } catch (Throwable ignored) {
            log("已就绪");
            hideTransient();
            scheduleHudFade();
        }
    }

    /** 把 ExoPlaybackException 拆成人话，特别是区分「下载不到」和「解码不了」 */
    private String describe(ExoPlaybackException e) {
        try {
            if (e.type == ExoPlaybackException.TYPE_SOURCE) {
                Throwable c = e.getSourceException();
                return "网络/源错误：" + brief(c) + "\n     地址取不到内容，通常是线路失效、需要 Referer 或被墙";
            } else if (e.type == ExoPlaybackException.TYPE_RENDERER) {
                Throwable c = e.getRendererException();
                return "解码错误：" + brief(c) + "\n     盒子硬解不支持该编码，正在尝试系统播放器";
            } else if (e.type == ExoPlaybackException.TYPE_UNEXPECTED) {
                return "内部错误：" + brief(e.getUnexpectedException());
            }
        } catch (Throwable ignored) {
        }
        return brief(e);
    }

    private static String brief(Throwable t) {
        if (t == null) return "未知";
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null ? "" : " " + m);
        return s.length() > 110 ? s.substring(0, 110) : s;
    }

    private void startMp() {
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDisplay(holder);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    log("系统播放器就绪，时长 " + (m.getDuration() / 1000) + "s");
                    try {
                        m.start();
                        // 断点续播
                        if (resumePos > 5000) {
                            long dur = m.getDuration();
                            if (dur > 0 && resumePos < dur - 10000) {
                                m.seekTo((int) resumePos);
                                log("→ 续播 从 " + fmt(resumePos));
                            } else {
                                WatchStore.setPosition(PlayerActivity.this, resumeKey, 0);
                            }
                            resumePos = 0;
                        }
                    } catch (Throwable t) {
                        log("start 失败 " + brief(t));
                    }
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    if (w > 0 && h > 0) {
                        gotPicture = true;
                        videoW = w;
                        videoH = h;
                        applyAspect();   // 按视频原始比例居中并留黑边
                        log("出画 " + w + "x" + h);
                        scheduleHudFade();
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    log("系统播放器报错 what=" + what + " extra=" + extra + mpErrHint(what, extra));
                    showHud();
                    return true;
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer m, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        log("缓冲中…");
                        showTransient("缓存中…");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        log("缓冲完成");
                        hideTransient();
                    }
                    return false;
                }
            });
            Map<String, String> h = new HashMap<String, String>();
            h.put("User-Agent", Net.DEFAULT_UA);
            if (referer != null && referer.length() > 0) h.put("Referer", referer);
            mp.setDataSource(this, Uri.parse(url), h);
            mp.prepareAsync();
            log("系统播放器准备中…");
        } catch (Throwable t) {
            log("系统播放器初始化失败 → " + brief(t));
            showHud();
        }
    }

    private static String mpErrHint(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == -2147483648L) {
            return "\n     系统解码器不认识这个格式";
        }
        if (extra == -1004) return "\n     网络读取失败";
        if (extra == -110) return "\n     连接超时";
        if (what == 100) return "\n     播放器被系统回收";
        return "\n     该盒子系统播放器多半不支持 m3u8";
    }

    private void autoFallback() {
        if (autoFellBack || engine != ENGINE_EXO) return;
        autoFellBack = true;
        log("→ 自动切换到系统 MediaPlayer 重试");
        showHud();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                start(ENGINE_MP);
            }
        }, 700);
    }

    // ---------------- HUD ----------------

    private void log(String s) {
        long dt = SystemClock.elapsedRealtime() - t0;
        String line = String.format("[%4.1fs] %s", dt / 1000f, s);
        if (logBuf.length() > 0) logBuf.append('\n');
        logBuf.append(line);
        trimLog();
        hud.setText(logBuf.toString());   // 仅更新内容；HUD 是否可见由 showHud()/MENU 控制
    }

    /** 仅出错 / 卡死时唤起：底部单行提示（默认隐藏，满足"不能播放才显示"）。完整日志按 MENU 查看 */
    private void showHud() {
        if (hudManual) return;
        // 只显示最后一行作为底部提示，避免整屏日志遮挡画面
        String full = logBuf.toString();
        int nl = full.lastIndexOf('\n');
        String last = nl >= 0 ? full.substring(nl + 1) : full;
        hud.setText(last);
        hudVisible = true;
        hud.setVisibility(android.view.View.VISIBLE);
    }

    /** 视频最底下一行瞬态提示：缓存中 / 加载失败。仅在非 MENU 手动模式时自动收起 */
    private void showTransient(String msg) {
        if (hudManual) return;
        hud.setText(msg);
        hudVisible = true;
        hud.setVisibility(android.view.View.VISIBLE);
    }

    /** 收起瞬态提示（仅当不是 MENU 手动模式、且确实在显示瞬态内容时） */
    private void hideTransient() {
        if (hudManual) return;
        hudVisible = false;
        hud.setVisibility(android.view.View.GONE);
    }

    /** 按设置构建磁盘缓存（关闭 / 256M / 512M / 1G）；大小变化才重建（start() 已先 releaseAll） */
    private synchronized void ensureCache() {
        int mode = PlayerPrefs.getCacheMode(this);
        long bytes = PlayerPrefs.cacheBytes(mode);
        if (bytes <= 0) {            // 关闭磁盘缓存
            sCache = null;
            sCacheBytes = 0;
            return;
        }
        if (sCache != null && sCacheBytes == bytes) return;   // 已构建且大小一致
        try {
            if (sCache != null) sCache.release();
        } catch (Throwable ignored) {
        }
        sCache = null;
        try {
            File dir = new File(getCacheDir(), "exo");
            dir.mkdirs();
            sCache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(bytes));
            sCacheBytes = bytes;
            log("磁盘缓存 " + (bytes / 1024 / 1024) + "MB 已启用");
        } catch (Throwable t) {
            sCache = null;
            sCacheBytes = -1;
        }
    }

    /** 加载看门狗：若 20s 仍无画面，说明卡死或极慢，自动亮出诊断面板 */
    private final Runnable diagWatch = new Runnable() {
        @Override
        public void run() {
            if (!gotPicture) {
                showHud();
                log("加载较慢或卡住，已显示诊断信息（按 MENU 可隐藏）");
            }
        }
    };

    /** 老盒子屏幕小，最多留 14 行 */
    private void trimLog() {
        int lines = 1;
        for (int i = 0; i < logBuf.length(); i++) {
            if (logBuf.charAt(i) == '\n') lines++;
        }
        while (lines > 14) {
            int nl = logBuf.indexOf("\n");
            if (nl < 0) break;
            logBuf.delete(0, nl + 1);
            lines--;
        }
    }

    /** 真正出画之后没必要一直挡着，8 秒后自动收起 */
    private void scheduleHudFade() {
        ui.removeCallbacks(fade);
        ui.postDelayed(fade, 8000);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                tip.setVisibility(android.view.View.GONE);
            }
        }, 4000);
    }

    private final Runnable fade = new Runnable() {
        @Override
        public void run() {
            if (gotPicture) hideTransient();
        }
    };

    private static String shorten(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---------------- 清晰度 / 宽高比 / 缓存 设置 ----------------

    /** 按当前清晰度设置构造轨道选择参数（地板不低于 480p，避免 240/360p 惨案） */
    private DefaultTrackSelector.Parameters buildClarityParameters() {
        DefaultTrackSelector.ParametersBuilder b =
                new DefaultTrackSelector.ParametersBuilder(this);
        switch (clarityMode) {
            case CLARITY_4K:    // 最高：4K 优先，地板 720p
                b.setMinVideoSize(1280, 720).setMaxVideoSize(3840, 2160);
                break;
            case CLARITY_1080:  // 高清：1080 优先，地板 720p
                b.setMinVideoSize(1280, 720).setMaxVideoSize(1920, 1080);
                break;
            case CLARITY_720:   // 流畅：720 优先，地板 480p
                b.setMinVideoSize(854, 480).setMaxVideoSize(1280, 720);
                break;
            default:            // 自适应：地板 480p，上限 4K，交给带宽估算
                b.setMinVideoSize(640, 480).setMaxVideoSize(3840, 2160);
                break;
        }
        return b.build();
    }

    /** 运行期切换清晰度（ExoPlayer 会即时重新选片） */
    private void applyClarity() {
        if (ts == null) return;
        ts.setParameters(buildClarityParameters());
    }

    /** 按宽高比设置重排 SurfaceView：保持影视原始比例，超宽允许上下黑边而非拉伸变形 */
    private void applyAspect() {
        int sw = root.getWidth();
        int sh = root.getHeight();
        if (sw <= 0 || sh <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            sw = dm.widthPixels;
            sh = dm.heightPixels;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sv.getLayoutParams();
        if (aspectMode == ASPECT_STRETCH) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
        } else {
            double ratio;
            if (aspectMode == ASPECT_ORIGINAL) {
                ratio = (videoW > 0 && videoH > 0) ? (double) videoW / videoH : (double) sw / sh;
            } else if (aspectMode == ASPECT_16_9) {
                ratio = 16.0 / 9.0;
            } else {
                ratio = 4.0 / 3.0;
            }
            int w, h;
            if ((double) sw / sh > ratio) {   // 屏幕更宽 -> 左右留黑边
                h = sh;
                w = (int) (sh * ratio);
            } else {                          // 影视更宽 -> 上下留黑边
                w = sw;
                h = (int) (sw / ratio);
            }
            lp.width = w;
            lp.height = h;
        }
        lp.gravity = Gravity.CENTER;
        sv.setLayoutParams(lp);
    }

    private static String aspectName(int m) {
        switch (m) {
            case ASPECT_STRETCH: return "拉伸";
            case ASPECT_16_9: return "16:9";
            case ASPECT_4_3: return "4:3";
            default: return "原始";
        }
    }

    private static String clarityName(int m) {
        switch (m) {
            case CLARITY_4K: return "4K优先";
            case CLARITY_1080: return "1080优先";
            case CLARITY_720: return "720优先";
            default: return "自适应";
        }
    }

    private static String cacheName(int m) {
        switch (m) {
            case 0: return "关闭";
            case 1: return "256M";
            case 3: return "1G";
            default: return "512M";
        }
    }

    private void cycleAspect() {
        aspectMode = (aspectMode + 1) % 4;
        PlayerPrefs.setAspect(this, aspectMode);
        applyAspect();
        refreshCtrlLabels();
        log("宽高比 → " + aspectName(aspectMode)
                + (aspectMode == ASPECT_ORIGINAL ? "（保持影视比例，超宽留黑边）" : ""));
    }

    private void cycleClarity() {
        clarityMode = (clarityMode + 1) % 4;
        PlayerPrefs.setClarity(this, clarityMode);
        applyClarity();
        refreshCtrlLabels();
        log("清晰度优先 → " + clarityName(clarityMode));
    }

    private void cycleCache() {
        int mode = PlayerPrefs.getCacheMode(this);
        mode = (mode + 1) % 4;
        PlayerPrefs.setCacheMode(this, mode);
        ensureCache();   // 下一集/下次播放生效；本集若已建缓存则保持
        refreshCtrlLabels();
        log("缓存大小 → " + cacheName(mode) + (mode == 0 ? "（已关闭）" : "（下一集生效）"));
    }

    /** 毫秒 → mm:ss */
    private static String fmt(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long ss = s % 60;
        return (m < 10 ? "0" : "") + m + ":" + (ss < 10 ? "0" : "") + ss;
    }

    /** 周期保存播放位置，支撑断点续播 */
    private final Runnable posSaver = new Runnable() {
        @Override
        public void run() {
            savePosition();
            ui.postDelayed(this, 5000);
        }
    };

    private void savePosition() {
        try {
            long p = -1;
            if (engine == ENGINE_EXO && exo != null
                    && exo.getPlaybackState() == Player.STATE_READY && exo.getPlayWhenReady()) {
                p = exo.getCurrentPosition();
            } else if (mp != null && mp.isPlaying()) {
                p = mp.getCurrentPosition();
            }
            if (p >= 0 && resumeKey != null && resumeKey.length() > 0) {
                WatchStore.setPosition(this, resumeKey, p);
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 控制条 ----------------

    /** 控制条按钮文字（宽高比/清晰度/缓存 显示当前设置值） */
    private String ctrlLabel(int i) {
        switch (i) {
            case CTRL_REWIND: return "⏪ 快退";
            case CTRL_PAUSE: return "⏯ 播放/暂停";
            case CTRL_FF: return "⏩ 快进";
            case CTRL_PREV: return "⏮ 上一集";
            case CTRL_NEXT: return "⏭ 下一集";
            case CTRL_EXIT: return "✕ 退出";
            case CTRL_AR: return "🔲 宽高比·" + aspectName(aspectMode);
            case CTRL_CLARITY: return "🎚 清晰度·" + clarityName(clarityMode);
            case CTRL_CACHE: return "💾 缓存·" + cacheName(PlayerPrefs.getCacheMode(this));
            default: return "";
        }
    }

    private void refreshCtrlLabels() {
        for (int i = 0; i < 9; i++) {
            if (ctrlBtns[i] != null) ctrlBtns[i].setText(ctrlLabel(i));
        }
    }

    /** 构建三行三列的控制条按钮组（含设置：宽高比 / 清晰度 / 缓存） */
    private void buildCtrlBar(FrameLayout root) {
        ctrlWrap = new LinearLayout(this);
        ctrlWrap.setOrientation(LinearLayout.VERTICAL);
        ctrlWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        wl.bottomMargin = 16;
        ctrlWrap.setLayoutParams(wl);

        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        LinearLayout row3 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row2.setGravity(Gravity.CENTER);
        row3.setGravity(Gravity.CENTER);
        for (int i = 0; i < 9; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(ctrlLabel(i));
            t.setTextSize(16);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setTextColor(0xffffffff);
            t.setBackgroundColor(0xff222222);
            t.setPadding(26, 14, 26, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(8, 8, 8, 0);
            t.setLayoutParams(lp);
            ctrlBtns[i] = t;
            if (i < 3) row1.addView(t);
            else if (i < 6) row2.addView(t);
            else row3.addView(t);
        }
        ctrlWrap.addView(row1);
        ctrlWrap.addView(row2);
        ctrlWrap.addView(row3);
        ctrlWrap.setVisibility(android.view.View.GONE);
        root.addView(ctrlWrap);
        updateCtrlFocus();
    }

    private void updateCtrlFocus() {
        for (int i = 0; i < 9; i++) {
            if (ctrlBtns[i] == null) continue;
            boolean f = (i == ctrlFocus);
            ctrlBtns[i].setBackgroundColor(f ? 0xff2a5a8a : 0xff222222);
            ctrlBtns[i].setScaleX(f ? 1.12f : 1f);
            ctrlBtns[i].setScaleY(f ? 1.12f : 1f);
        }
    }

    private void showCtrl() {
        ctrlVisible = true;
        ctrlWrap.setVisibility(android.view.View.VISIBLE);
        updateCtrlFocus();
        ui.removeCallbacks(hideCtrl);
        ui.postDelayed(hideCtrl, 6000);
    }

    private final Runnable hideCtrl = new Runnable() {
        @Override
        public void run() {
            ctrlVisible = false;
            ctrlWrap.setVisibility(android.view.View.GONE);
        }
    };

    /** 执行控制条上某个按钮的动作 */
    private void doAction(int idx) {
        switch (idx) {
            case CTRL_REWIND:
                seekBy(-15000);
                break;
            case CTRL_PAUSE:
                togglePause();
                break;
            case CTRL_FF:
                seekBy(15000);
                break;
            case CTRL_PREV:
                playEpisode(curEp - 1);
                break;
            case CTRL_NEXT:
                playEpisode(curEp + 1);
                break;
            case CTRL_EXIT:
                releaseAll();
                finish();
                break;
            case CTRL_AR:
                cycleAspect();
                break;
            case CTRL_CLARITY:
                cycleClarity();
                break;
            case CTRL_CACHE:
                cycleCache();
                break;
        }
    }

    /** 快进/快退：以当前位置为基准偏移 delta 毫秒 */
    private void seekBy(long delta) {
        try {
            if (engine == ENGINE_EXO && exo != null) {
                long pos = exo.getCurrentPosition() + delta;
                long dur = exo.getDuration();
                if (dur > 0 && pos > dur) pos = dur;
                if (pos < 0) pos = 0;
                exo.seekTo(pos);
                log((delta > 0 ? "快进 → " : "快退 → ") + (pos / 1000) + "s");
            } else if (mp != null) {
                long pos = mp.getCurrentPosition() + delta;
                long dur = mp.getDuration();
                if (dur > 0 && pos > dur) pos = dur;
                if (pos < 0) pos = 0;
                mp.seekTo((int) pos);
                log((delta > 0 ? "快进 → " : "快退 → ") + (pos / 1000) + "s");
            }
        } catch (Throwable t) {
            log("拖动失败：" + t.getMessage());
        }
    }

    /** 切到指定集（上一集/下一集） */
    private void playEpisode(int idx) {
        if (epUrls.isEmpty()) {
            log("该影片未提供选集列表，无法上下集切换");
            return;
        }
        if (idx < 0 || idx >= epUrls.size()) {
            log(idx < 0 ? "已经是第一集" : "已经是最后一集");
            return;
        }
        savePosition();   // 切集前先保存当前集进度，支撑断点续播
        curEp = idx;
        url = Net.normalize(epUrls.get(idx));
        name = (name != null ? name.replaceAll("\\s*第.+集$", "") : "") + " " + epNames.get(idx);
        autoFellBack = false;
        log("▶ 第 " + (idx + 1) + " 集 / 共 " + epUrls.size() + " 集");
        start(engine);
        preflight();
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                doAction(ctrlFocus);   // 默认焦点在「播放/暂停」，直接按确定即暂停/继续
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                showCtrl();
                if (ctrlFocus == CTRL_FF) {           // 右边缘·快进
                    doAction(CTRL_FF);
                } else if (ctrlFocus == CTRL_NEXT) {  // 右边缘·下一集
                    doAction(CTRL_NEXT);
                } else if (ctrlFocus == CTRL_EXIT) {  // 最右·退出
                    doAction(CTRL_EXIT);
                } else if (ctrlFocus % 3 != 2) {      // 非最右列：行内右移
                    ctrlFocus++;
                    updateCtrlFocus();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                showCtrl();
                if (ctrlFocus == CTRL_REWIND) {       // 左边缘·快退
                    doAction(CTRL_REWIND);
                } else if (ctrlFocus == CTRL_PREV) {  // 左边缘·上一集
                    doAction(CTRL_PREV);
                } else if (ctrlFocus % 3 != 0) {      // 非最左列：行内左移
                    ctrlFocus--;
                    updateCtrlFocus();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                showCtrl();
                if (ctrlFocus >= 3) {                // 上移到上一行
                    ctrlFocus -= 3;
                    updateCtrlFocus();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                showCtrl();
                if (ctrlFocus < 6) {                 // 下移到下一行（到第三行设置栏）
                    ctrlFocus += 3;
                    updateCtrlFocus();
                }
                return true;

            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                log("── 切换到内置测试片，用于判断播放器本身是否正常 ──");
                url = TEST_URL;
                name = "内置测试片";
                referer = null;
                epUrls.clear();
                epNames.clear();
                autoFellBack = false;
                start(ENGINE_EXO);
                preflight();
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                hudManual = !hudManual;
                if (hudManual) {
                    hud.setText(logBuf.toString());   // MENU：显示完整诊断日志
                    hud.setVisibility(android.view.View.VISIBLE);
                } else {
                    hud.setVisibility(android.view.View.GONE);
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                releaseAll();
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void togglePause() {
        try {
            if (engine == ENGINE_EXO && exo != null) {
                isPaused = !isPaused;
                exo.setPlayWhenReady(!isPaused);
            } else if (mp != null) {
                if (mp.isPlaying()) {
                    mp.pause();
                    isPaused = true;
                } else {
                    mp.start();
                    isPaused = false;
                }
            }
            if (isPaused) log("已暂停");
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 生命周期 ----------------

    private void releaseAll() {
        ui.removeCallbacks(diagWatch);
        ui.removeCallbacks(posSaver);
        savePosition();   // 退出/切换前保存当前播放位置
        try {
            if (exo != null) {
                exo.release();
                exo = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mp != null) {
                mp.reset();
                mp.release();
                mp = null;
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releaseAll();
        super.onDestroy();
    }
}
