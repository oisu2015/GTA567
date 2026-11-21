package com.dushetv.box;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends android.app.Activity {
    public static final String PREFS = "box";
    public static final String KEY_API = "api";
    public static final String KEY_SRC_NAME = "src_name";   // 已选定的直连源名称（开机秒开用）
    public static final String KEY_SRC_API = "src_api";      // 已选定的直连源接口（开机秒开用）
    public static final String KEY_COLS = "cols";            // 设置：每行影片数（0=自动，5/6=固定）
    public static final String KEY_BOOT = "boot";            // 设置：开机自启动开关（默认开）
    public static final String KEY_WALL = "wall";            // 设置：自定义壁纸地址（空=系统默认渐变）
    public static final String KEY_WX_CITY = "wx_city";      // 设置：天气城市（空=用远程/默认北京）
    public static final String KEY_WX_LAT = "wx_lat";
    public static final String KEY_WX_LON = "wx_lon";

    // ===== 天气预设城市（主页与设置页共用一份，避免两处重复）=====
    public static final String[] WX_CITIES = {
            "北京", "上海", "广州", "深圳", "成都", "杭州", "武汉",
            "西安", "南京", "重庆", "沈阳", "哈尔滨", "昆明", "乌鲁木齐", "天津", "长沙"
    };
    public static final double[] WX_LATS = {
            39.9042, 31.2304, 23.1291, 22.5431, 30.5728, 30.2741, 30.5928,
            34.3416, 32.0603, 29.5630, 41.8057, 45.8038, 25.0389, 43.8256, 39.3434, 28.2282
    };
    public static final double[] WX_LONS = {
            116.4074, 121.4737, 113.2644, 114.0579, 104.0668, 120.1551, 114.3055,
            108.9398, 118.7969, 106.5516, 123.4315, 126.5349, 102.7183, 87.6168, 117.3616, 112.9388
    };

    // ===== 预留升级接口（隐藏式后台升级检查）=====
    // 升级信息 JSON 放在下面这个地址；客户端开机 60 秒后静默检查一次，仅在发现新版本时才弹提示。
    // 远程文件不存在 / 拉取失败 / 版本未更高 → 一律不提示、不打扰。
    // JSON 格式（versionCode 大于本机才提示，force:true 强制更新）：
    // { "versionCode": 62, "versionName": "4.6",
    //   "url": "https://7vga.cn/zqnew/dushetv-v4.6.apk",
    //   "note": "更新说明…", "force": false }
    // v4.5：升级服务迁到自有站点 7vga.cn/zqnew/（旧 workbuddy.com 域名已失效）。
    private static final String UPDATE_URL = "https://7vga.cn/zqnew/update.json";

    // v1.4 起 TVBox 配置接口预设（饭太硬/唐三等 15 个）已全部移除：
    // 实测缺 Rhino 引擎，配置里的站点绝大多数是 type3 爬虫源，加载了也不能播，
    // 且白白拉长测速时间。只保留下方「直连采集站」。

    // 直连采集站（苹果CMS type1）。不用下载任何配置文件，选中即可浏览播放，
    // 也是 v1 真正能播的类型——yckceo 那些 TVBox 配置里 90% 的站点是 type3(JS爬虫)，v1 用不了。
    // v3.0.15：实测逐一测连通性，移除 13 个连不上的失效源（天空/卧龙/海外看/快帆/豆瓣/可可/乐视/
    // 暴风影音/明星MV/奇虎/小胡/飞速/四九），并新增 3 个可用源（1080高清资源库/金鹰资源/红牛资源），
    // 当前共 16 个直连采集站。运行时一键测速仍会自动把连不上的过滤掉、把最快的排前面。
    public static final String[][] BUILTIN_DIRECT = {
            {"非凡资源", "http://cj.ffzyapi.com/api.php/provide/vod"},
            {"量子资源", "https://cj.lziapi.com/api.php/provide/vod"},
            {"索尼资源", "https://suoniapi.com/api.php/provide/vod"},
            {"暴风资源", "https://bfzyapi.com/api.php/provide/vod"},
            {"魔都资源", "https://www.mdzyapi.com/api.php/provide/vod"},
            {"U酷资源", "https://api.ukuapi.com/api.php/provide/vod"},
            {"IK酷资源", "https://ikunzyapi.com/api.php/provide/vod"},
            {"百度采集", "https://api.apibdzy.com/api.php/provide/vod/?ac=list"},
            {"虎牙采集", "https://www.huyaapi.com/api.php/provide/vod/from/hym3u8"},
            {"淘片采集", "https://taopianapi.com/cjapi/mc/vod/json.html"},
            {"量子资源2", "https://lzizy1.com/api.php/provide/vod/"},
            {"非凡资源2", "http://ffzy1.tv/api.php/provide/vod/"},
            {"火狐采集", "https://hhzyapi.com/api.php/provide/vod/"},
            {"1080高清资源库", "https://api.yyzy-tv.vip/inc/apijson.php"},
            {"金鹰资源", "https://jyzyapi.com/provide/vod/from/jinyingm3u8/at/json"},
            {"红牛资源", "https://www.hongniuzy2.com/api.php/provide/vod/at/josn/"},
    };

    // 直连源列表：运行时可通过「更新」按钮从远程拉取最新列表并持久化到本地。
    // v1.4：实测 TVBox 配置接口（饭太硬等）因缺 Rhino 引擎基本不能播，
    // 全部移除，只保留直连可直接播的苹果CMS站，测速也只测它们，省时间。
    public static String[][] DIRECT_SITES = BUILTIN_DIRECT;
    // 远程直连源列表（与升级同一目录）：JSON 数组 [{"name":"非凡资源","api":"http://…"}]
    public static final String SOURCES_URL = "https://7vga.cn/zqnew/sources.json";
    public static final String KEY_SITES_JSON = "sites_json";

    /** 用内置直连站点信息造一个 Site 对象 */
    public static Site directSite(String name, String api) {
        Site s = new Site();
        s.key = name;
        s.name = name;
        s.api = api;
        s.type = 1;
        s.searchable = 1;
        s.quickSearch = 1;
        s.filterable = 1;
        s.playerType = -1;
        s.playerUrl = "";
        s.ext = "";
        s.jar = "";
        return s;
    }

    private SharedPreferences sp;
    private GridView grid;
    private TextView title, status, weather;
    private TextView navBack;   // 顶部导航「返回」按钮：仅在非首页模式（分类/搜索/其他）显示
    private ImageView bg;
    private ProgressBar prog;
    private LinearLayout catRow, actRow;
    private View prevBtn, nextBtn, topBtn, bottomBtn;
    /** v4.6：加载中的滚动海报层 + 中央提示，替代空白网格 */
    private LoadingPosterView loadingPosters;
    private View loadingLabel;

    private ApiConfig cfg;
    private Site curSite;
    private SiteApi api;
    /** 搜索回退命中的可用源；为 null 表示用当前源 */
    private Site searchSite = null;
    /** 自动换源成功提示（首页/分类/搜索通用），onPostExecute 弹一次 */
    private String switchedMsg = null;

    // ---- 跨源页填充：源默认每页 20 条，填充到每屏 30 条（5/6 列都满行无零头）----
    private int browseFillPage = 1;                          // 下一页要读的源页（首页/分类/其他）
    private List<VodItem> browseFillLeft = new ArrayList<VodItem>();   // 上一页跨页残留
    private int searchFillPage = 1;                          // 搜索填充游标
    private List<VodItem> searchFillLeft = new ArrayList<VodItem>();
    private List<FillSnap> browseHist = new ArrayList<FillSnap>();     // 翻页快照，支持上一页
    private List<FillSnap> searchHist = new ArrayList<FillSnap>();
    private List<Category> cats = new ArrayList<Category>();
    private List<VodItem> items = new ArrayList<VodItem>();
    private GridAdapter adapter;

    private int mode = MODE_HOME;
    private static final int MODE_HOME = 0, MODE_CAT = 1, MODE_SEARCH = 2, MODE_OTHER = 3;
    /** 归为「其他」的所有原始分类 id（短剧/动漫等），点「其他」时合并展示 */
    private List<String> otherTids = new ArrayList<String>();
    private String curTid = "";
    private String curKw = "";
    private int curPage = 1;
    /** 接口返回的总页数（pagecount，0=未知） */
    private int curPageCount = 0;
    /** 接口返回的总条数（total，0=未知），分类页标题显示用 */
    private int curTotal = 0;
    private boolean hasMore = true;
    private boolean loading = false;
    private View prevSel = null;
    /** 后台静默测速是否进行中（避免与手动测速并发） */
    private boolean speedTesting = false;

    /** 页面缓存：避免重复请求。key=kind+tid/kw, value=缓存数据+时间戳 */
    private static final java.util.Map<String, CachedPage> pageCache = new java.util.HashMap<String, CachedPage>();
    private static final long PAGE_CACHE_MS = 15 * 60 * 1000; // 15分钟
    private static final int MAX_ITEMS_PER_PAGE = 30;          // 每页最多30条

    /** 上次接口测速结果（排好序），用于「不重测直接切换」 */
    private List<SpeedTest.Item> apiSpeed = null;

    /** 缓存条目 */
    private static class CachedPage {
        List<Category> cats;
        List<VodItem> items;
        boolean hasMore;
        long time;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // v1.6：进程一启动就装上宽松 TLS，图片加载与播放器内部的 HttpURLConnection 才能共用
        Net.installGlobalTls();
        // v4.7：启用磁盘 HTTP 缓存，翻页回来 / 重进分类 / 重启后图片与配置直接读磁盘，不再显得慢
        Net.installHttpCache(this);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        grid = (GridView) findViewById(R.id.grid);
        title = (TextView) findViewById(R.id.title);
        status = (TextView) findViewById(R.id.status);
        weather = (TextView) findViewById(R.id.weather);
        weather.setClickable(true);
        weather.setFocusable(true);
        weather.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showCityPicker(); }
        });
        bg = (ImageView) findViewById(R.id.bg);
        applyWallpaper();   // 默认无壁纸→渐变；远程到达后由 fetchAds 回调再次应用
        fetchWeather();     // 默认北京，远程可改城市；失败静默隐藏
        prog = (ProgressBar) findViewById(R.id.prog);
        catRow = (LinearLayout) findViewById(R.id.cats);
        actRow = (LinearLayout) findViewById(R.id.actions);
        loadingPosters = (LoadingPosterView) findViewById(R.id.loadingPosters);
        loadingLabel = findViewById(R.id.loadingLabel);

        // 列数：优先用设置页的「每行影片数」（0=自动按分辨率，5/6=固定），返回设置页后也会在 onResume 重新套用
        applyColumnCount();

        adapter = new GridAdapter();
        grid.setAdapter(adapter);

        // 四个边缘按钮（位于内容边距带，半透明，绝不遮挡卡片）
        prevBtn = findViewById(R.id.prevBtn);
        nextBtn = findViewById(R.id.nextBtn);
        topBtn = findViewById(R.id.topBtn);
        bottomBtn = findViewById(R.id.bottomBtn);
        setupEdgeButtons();
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                // 左右方向键：整页翻页（30 条/页）；上下：网格内移动
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (curPage > 1) { loadPrev(); return true; }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (hasMore) { loadMore(); return true; }
                }
                showEdgeHints();
                return false;
            }
        });

        // 固定分页：每页 MAX_ITEMS_PER_PAGE 条，左右方向键翻页，不做无限滚动

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                VodItem it = items.get(pos);
                Intent in = new Intent(MainActivity.this, DetailActivity.class);
                in.putExtra("api", curSite.api);
                in.putExtra("type", curSite.type);
                in.putExtra("siteName", curSite.name);
                in.putExtra("vodId", it.id);
                in.putExtra("vodName", it.name);
                in.putExtra("pic", it.pic);
                in.putExtra("vodJson", it.raw != null ? it.raw.toString() : "");
                startActivity(in);
            }
        });

        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                // 旧卡片复位（缩放/金边/名字/图片）
                if (prevSel != null) resetCard(prevSel);
                if (v != null) {
                    // Apple TV 风格选中：柔和放大 8% + 柔光描边（card_bg_focused_atv）+ 显示完整片名(2行) + 完整图片(不裁切)
                    v.setScaleX(1.08f);
                    v.setScaleY(1.08f);
                    v.setBackgroundResource(R.drawable.card_bg_focused_atv);
                    // 用 Z 轴 elevation 置顶绘制：仅改层叠顺序、不改 children 索引，
                    // 因此不会破坏 GridView 的两维焦点导航；光晕不再被相邻卡片遮挡
                    if (android.os.Build.VERSION.SDK_INT >= 21) v.setElevation(8f);
                    TextView nm = (TextView) v.findViewById(R.id.name);
                    if (nm != null) {
                        nm.setSingleLine(false);
                        nm.setMaxLines(2);
                        nm.setEllipsize(null);
                    }
                    ImageView pc = (ImageView) v.findViewById(R.id.pic);
                    if (pc != null) pc.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    prevSel = v;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // 焦点离开网格时，复位残留的选中卡片（否则最后一张会一直保持放大+金边）
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && prevSel != null) {
                    resetCard(prevSel);
                    prevSel = null;
                }
            }
        });

        // 注意：不再用滚动到底自动加载更多（那样会变成无限下拉）。
        // 翻页一律走左右边缘按钮 / 遥控器左右方向键，每页固定 30 条。

        // 先恢复上次「更新接口源」拉取的直连源列表（没有则用内置默认表）
        loadSavedDirectSites(sp);

        String directApi = sp.getString(KEY_SRC_API, "");
        String saved = sp.getString(KEY_API, "");
        if (directApi.length() > 0) {
            // 之前已选定过某个直连源：直接秒开，无需重新探测
            useSite(directSite(sp.getString(KEY_SRC_NAME, "直连源"), directApi));
            backgroundSpeedTest();
        } else if (saved.length() > 0) {
            initApi(saved, false);
        } else {
            // 首次启动：默认立即打开第一个直连源（非凡），首页秒开；
            // 测速放到后台静默进行，发现更快的再提示，绝不阻塞开机。
            useSite(directSite(DIRECT_SITES[0][0], DIRECT_SITES[0][1]));
            backgroundSpeedTest();
        }

        // 从搜索页跳转回来：可能是搜索关键字，也可能是点开某个「源分类」
        String catTid = getIntent().getStringExtra("catTid");
        String kw = getIntent().getStringExtra("searchKw");
        if (catTid != null && catTid.length() > 0) {
            loadCategory(catTid);
        } else if (kw != null && kw.length() > 0) {
            loadSearch(kw);
        }

        // 启动后 60 秒静默检查一次更新（隐藏式：后台执行，仅发现新版本才弹提示，不打扰用户）
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) checkUpdate(false);
            }
        }, 60000);

        // 静默拉取远程广告配置（公众号 / 壁纸 / 天气等）；远程覆盖过顶部按钮才重建操作栏，避免无谓抢焦点
        AdHelper.fetchAds(new Runnable() {
            @Override
            public void run() {
                final boolean top = AdHelper.topChanged();
                final boolean wx = AdHelper.weatherChanged();
                if (!isFinishing()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (top) buildActions();
                            // 远程若下发了壁纸 / 天气配置，立即应用（壁纸静默换、天气强制刷新）
                            applyWallpaper();
                            if (wx) fetchWeather(true);
                        }
                    });
                }
            }
        });
    }

    // ---------- 列数：设置页「每行影片数」 ----------

    /** 应用列数：设置页固定 5/6 时优先，否则按分辨率自适应（720→5，1080+→6）。
     *  放在独立方法里，onCreate 与 onResume 都要调用。 */
    private void applyColumnCount() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int pref = sp.getInt(KEY_COLS, 0);
        int cols = (pref > 0) ? pref : ((dm.widthPixels <= 1280) ? 5 : 6);
        grid.setNumColumns(cols);
    }

    // ---------- 返回前台：套用设置页改动的列数 / 源 ----------

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页可能改了列数，重新套用（改了源也会在下面重载，这里先保证布局正确）
        applyColumnCount();
        // 设置页改了源（选了直连源 / 填了自定义接口）则重载，返回即生效
        String newApi = sp.getString(KEY_SRC_API, "");
        String newCfg = sp.getString(KEY_API, "");
        boolean changed = false;
        if (newApi.length() > 0) {
            if (curSite == null || curSite.api == null || !newApi.equals(curSite.api)) changed = true;
        } else if (newCfg.length() > 0) {
            if (curSite == null || curSite.api == null || !newCfg.equals(curSite.api)) changed = true;
        }
        if (changed) {
            if (newApi.length() > 0) {
                useSite(directSite(sp.getString(KEY_SRC_NAME, "直连源"), newApi));
            } else {
                initApi(newCfg, false);
            }
        }
    }

    // ---------- 接口加载 ----------

    private void initApi(final String url, final boolean fromUser) {
        showStatus("正在加载接口…");
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    String json = Net.getText(url, null);
                    ApiConfig c = ApiConfig.parse(json);
                    cfg = c;
                    sp.edit().putString(KEY_API, url).apply();
                    return null;
                } catch (Throwable t) {
                    return t.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String err) {
                if (err != null) {
                    hideStatus();
                    Toast.makeText(MainActivity.this, "接口加载失败：" + err, Toast.LENGTH_LONG).show();
                    if (fromUser) openSettings();
                    return;
                }
                List<Site> picks = pickableSites();
                if (picks.isEmpty()) {
                    hideStatus();
                    Toast.makeText(MainActivity.this,
                            "该接口里的源都需要蜘蛛引擎(JS)，v1 暂不支持。菜单→设置→影视接口换一个",
                            Toast.LENGTH_LONG).show();
                    if (fromUser) openSettings();
                    return;
                }
                if (picks.size() > 1) {
                    pickSite(picks);
                } else {
                    useSite(picks.get(0));
                }
            }
        }.execute();
    }

    /** 返回 v1 能用的源（type 0/1，过滤掉需要蜘蛛引擎的 type 3） */
    private List<Site> pickableSites() {
        List<Site> out = new ArrayList<Site>();
        if (cfg == null) return out;
        for (Site s : cfg.sites) {
            if (s.type != 3) out.add(s);
        }
        return out;
    }

    private void pickSite() {
        pickSite(pickableSites());
    }

    private void pickSite(final List<Site> picks) {
        if (picks == null || picks.isEmpty()) {
            Toast.makeText(this, "没有可用的源", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[picks.size()];
        for (int i = 0; i < picks.size(); i++) {
            Site s = picks.get(i);
            names[i] = s.name + (s.type == 1 ? "" : "（type" + s.type + "）");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择视频源（共 " + picks.size() + " 个可用）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        useSite(picks.get(w));
                    }
                })
                .setPositiveButton("测速选最快", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        speedTestSites(picks);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void useSite(Site s) {
        curSite = s;
        api = new SiteApi(s);
        // 记住当前直连源，下次开机直接秒开（无需重新测速/选源）
        sp.edit().putString(KEY_SRC_NAME, s.name).putString(KEY_SRC_API, s.api).apply();
        title.setText("致强视频  ▸  " + s.name);
        buildActions();
        loadHome();
    }

    // ---------- 测速：接口（配置源）级 ----------

    /**
     * 对全部预设接口并发测速。
     *
     * @param autoUseBest true=测完直接加载最快的；false=测完弹结果列表由用户选
     */
    private void speedTestApis(final boolean autoUseBest) {
        final List<SpeedTest.Item> list = new ArrayList<SpeedTest.Item>();
        // 只测直连采集站（可直接播）；TVBox 配置接口实测不能播，已全部移除，节省测速时间
        for (int i = 0; i < DIRECT_SITES.length; i++) {
            Site s = directSite(DIRECT_SITES[i][0], DIRECT_SITES[i][1]);
            SpeedTest.Item it = new SpeedTest.Item("[直连] " + s.name, withAcList(s.api));
            it.tag = s;
            list.add(it);
        }

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("测速中（直连源）");
        pd.setMessage("0 / " + list.size());
        pd.setCancelable(true);
        pd.show();

        SpeedTest.run(list, 6, new SpeedTest.Listener() {
            @Override
            public void onProgress(int done, int total, SpeedTest.Item it) {
                try {
                    if (pd.isShowing()) pd.setMessage(done + " / " + total + "\n" + it.label());
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onFinish(List<SpeedTest.Item> sorted) {
                try {
                    pd.dismiss();
                } catch (Throwable ignored) {
                }
                try {
                    apiSpeed = sorted;
                    SpeedTest.Item best = bestPreferDirect(sorted);
                    if (best == null) {
                        Toast.makeText(MainActivity.this,
                                "全部连不通，请检查盒子网络 / DNS", Toast.LENGTH_LONG).show();
                        showApiSpeedResult(sorted);
                        return;
                    }
                    if (autoUseBest) {
                        Toast.makeText(MainActivity.this,
                                "最快：" + best.name + "  " + best.ms + "ms，正在加载…",
                                Toast.LENGTH_SHORT).show();
                        applySpeedItem(best);
                    } else {
                        showApiSpeedResult(sorted);
                    }
                } catch (Throwable t) {
                    // 测速后处理异常一律兜底，绝不直接闪退
                    android.util.Log.w("DuSheMain", "speedTestApis onFinish", t);
                    Toast.makeText(MainActivity.this, "测速完成但处理异常：" + t.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /**
     * 选「最快」时优先直连采集站：它们一定能播；
     * 配置接口里可能全是 type3 爬虫源，加载了也用不了。
     */
    private static SpeedTest.Item bestPreferDirect(List<SpeedTest.Item> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            SpeedTest.Item it = sorted.get(i);
            if (it.ok && it.tag instanceof Site) return it;
        }
        return SpeedTest.best(sorted);
    }

    /** 应用一条测速结果：直连源直接用，配置接口走加载流程 */
    private void applySpeedItem(SpeedTest.Item it) {
        if (it == null) return;
        try {
            if (it.tag instanceof Site) {
                useSite((Site) it.tag);
            } else {
                initApi(it.url, true);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "应用源失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 后台静默测速：只测直连源，不弹框、不阻塞。
     * 测完若发现比当前更快的源 → 提示切换；当前即最快（或不通时当前仍可用）→ 不打扰。
     */
    private void backgroundSpeedTest() {
        if (speedTesting) return;
        speedTesting = true;
        final Site current = curSite;
        final String curApi = (current != null && current.api != null) ? current.api : "";
        final List<SpeedTest.Item> list = new ArrayList<SpeedTest.Item>();
        for (int i = 0; i < DIRECT_SITES.length; i++) {
            Site s = directSite(DIRECT_SITES[i][0], DIRECT_SITES[i][1]);
            SpeedTest.Item it = new SpeedTest.Item("[直连] " + s.name, withAcList(s.api));
            it.tag = s;
            list.add(it);
        }
        SpeedTest.run(list, 6, new SpeedTest.Listener() {
            @Override
            public void onProgress(int done, int total, SpeedTest.Item it) {
            }

            @Override
            public void onFinish(final List<SpeedTest.Item> sorted) {
                speedTesting = false;
                try {
                    apiSpeed = sorted;
                    final SpeedTest.Item best = bestPreferDirect(sorted);
                    if (best == null) return;       // 全不通，保持当前
                    // 当前源就是最快的那个（按 api 匹配）→ 保持，绝不打扰
                    if (curApi.length() > 0 && best.tag instanceof Site
                            && ((Site) best.tag).api.equals(curApi)) {
                        return;
                    }
                    if (isFinishing()) return;       // 页面已关，别再弹窗
                    promptSwitch(best);
                } catch (Throwable t) {
                    android.util.Log.w("DuSheMain", "bg speedtest onFinish", t);
                }
            }
        });
    }

    /** 后台测速发现更快源时，弹「是否切换」提示（不强制，可保持当前） */
    private void promptSwitch(final SpeedTest.Item best) {
        if (!(best.tag instanceof Site) || isFinishing()) return;
        final Site bs = (Site) best.tag;
        try {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("发现更快的源")
                    .setMessage("当前：" + (curSite != null ? curSite.name : "?")
                            + "\n更快：" + best.name.replace("[直连] ", "") + "  " + best.ms + "ms"
                            + "\n是否切换？")
                    .setPositiveButton("切换", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                applySpeedItem(best);
                            } catch (Throwable t) {
                                Toast.makeText(MainActivity.this, "切换失败：" + t.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("保持当前", null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("DuSheMain", "promptSwitch", t);
        }
    }

    /** 展示测速结果（已按速度排序），点任意一条即切换 */
    private void showApiSpeedResult(final List<SpeedTest.Item> sorted) {
        if (isFinishing()) return;
        if (sorted == null || sorted.isEmpty()) {
            speedTestApis(false);
            return;
        }
        final String[] names = new String[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            names[i] = (i + 1) + ". " + sorted.get(i).label();
        }
        final SpeedTest.Item best = bestPreferDirect(sorted);
        String t = (best == null) ? "测速结果（全部不通）"
                : "最快：" + best.name + " " + best.ms + "ms（点条目可切换）";
        new AlertDialog.Builder(this)
                .setTitle(t)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applySpeedItem(sorted.get(w));
                    }
                })
                .setPositiveButton("用最快的", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (best != null) applySpeedItem(best);
                        else Toast.makeText(MainActivity.this, "没有可用的源", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("重新测速", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        speedTestApis(false);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------- 测速：视频源（站点）级 ----------

    private void speedTestSites(final List<Site> picks) {
        if (picks == null || picks.isEmpty()) {
            Toast.makeText(this, "没有可测的源", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<SpeedTest.Item> list = new ArrayList<SpeedTest.Item>();
        for (int i = 0; i < picks.size(); i++) {
            Site s = picks.get(i);
            if (s.api == null || s.api.length() == 0) continue;
            SpeedTest.Item it = new SpeedTest.Item(s.name, withAcList(s.api));
            it.tag = s;
            list.add(it);
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "这些源没有可测的接口地址", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("视频源测速中");
        pd.setMessage("0 / " + list.size());
        pd.setCancelable(true);
        pd.show();

        SpeedTest.run(list, 6, new SpeedTest.Listener() {
            @Override
            public void onProgress(int done, int total, SpeedTest.Item it) {
                try {
                    if (pd.isShowing()) pd.setMessage(done + " / " + total + "\n" + it.label());
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onFinish(final List<SpeedTest.Item> sorted) {
                try {
                    pd.dismiss();
                } catch (Throwable ignored) {
                }
                try {
                final String[] names = new String[sorted.size()];
                for (int i = 0; i < sorted.size(); i++) {
                    names[i] = (i + 1) + ". " + sorted.get(i).label();
                }
                SpeedTest.Item best = SpeedTest.best(sorted);
                String t = (best == null) ? "源测速（都不通）"
                        : "源测速 · 最快：" + best.name + " " + best.ms + "ms";
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
                b.setTitle(t);
                b.setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Object tag = sorted.get(w).tag;
                        if (tag instanceof Site) useSite((Site) tag);
                    }
                });
                if (best != null && best.tag instanceof Site) {
                    final Site bs = (Site) best.tag;
                    b.setPositiveButton("用最快的", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            useSite(bs);
                        }
                    });
                }
                b.setNegativeButton("关闭", null);
                b.show();
                } catch (Throwable t) {
                    android.util.Log.w("DuSheMain", "speedTestSites onFinish", t);
                }
            }
        });
    }

    /** 苹果CMS 接口探测时带上 ac=list，更贴近真实取数速度 */
    private static String withAcList(String apiUrl) {
        if (apiUrl.indexOf("ac=") >= 0) return apiUrl;
        return apiUrl + (apiUrl.indexOf('?') >= 0 ? "&" : "?") + "ac=list";
    }

    // ---------- 数据加载 ----------

    private void loadHome() {
        mode = MODE_HOME;
        curTid = "";
        curPage = 1;
        hasMore = true;
        resetFill(false);
        pushFillSnap(false);
        updateBackBtn();
        new Loader().execute("home", null);
    }

    private void loadCategory(String tid) {
        mode = MODE_CAT;
        curTid = tid;
        curPage = 1;
        hasMore = true;
        resetFill(false);
        pushFillSnap(false);
        updateBackBtn();
        new Loader().execute("cat", tid);
    }

    /** 「其他」：合并展示所有归为其他的分类（短剧/动漫等） */
    private void loadOther() {
        mode = MODE_OTHER;
        curPage = 1;
        hasMore = true;
        resetFill(false);
        pushFillSnap(false);
        updateBackBtn();
        new Loader().execute("other", null);
    }

    private void loadSearch(String kw) {
        mode = MODE_SEARCH;
        curKw = kw;
        curPage = 1;
        hasMore = true;
        searchSite = null;
        resetFill(true);
        pushFillSnap(true);
        updateBackBtn();
        new Loader().execute("search", kw);
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        curPage++;
        // 无限滚动模式不再压入「上一页」快照（无整页翻页）；fill 游标由 collectFilled 自行维护
        new Loader().execute("more", null);
    }

    private void loadPrev() {
        if (loading || curPage <= 1) return;
        curPage--;
        restoreFillSnap(mode == MODE_SEARCH);
        new Loader().execute("prev", null);
    }

    /** 四个边缘按钮：左=上一页、右=下一页（翻 30 条）、上=回顶部、下=跳底部。
     *  它们 focusable=false，仅作可视提示与触摸点击；遥控器翻页由 GridView 的方向键处理，
     *  保证焦点始终在内容上，不会卡在按钮上。 */
    private void setupEdgeButtons() {
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (curPage > 1) loadPrev();
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (hasMore) loadMore();
            }
        });
        topBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (items.size() > 0) grid.setSelection(0);
            }
        });
        bottomBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (items.size() > 0) grid.setSelection(items.size() - 1);
            }
        });
    }

    // ---------- 边缘提示自动隐藏 ----------
    // 按钮背景本就半透明，但纯白文字在淡背景上过于扎眼；改为「轻提示」：
    // 整体降到 60% 透明度，且静止 EDGE_HINT_DELAY 后淡出，任何用户活动都会重新点亮。
    private static final long EDGE_HINT_DELAY = 3500L;   // 静止 3.5s 后淡出
    private Handler edgeHintHandler = new Handler();
    private Runnable edgeHintHide = new Runnable() {
        @Override public void run() { fadeEdgeHints(0f); }
    };

    /** 按当前状态恢复各按钮可见性，淡入到半透明提示，并预约自动隐藏。
     *  任何用户活动（方向键翻动 / 触摸 / 翻页加载完成）都应调用本方法刷新。 */
    private void showEdgeHints() {
        // 分页模式：左=上一页、右=下一页，按当前 curPage/hasMore 动态显隐
        if (prevBtn != null) prevBtn.setVisibility(curPage > 1 ? View.VISIBLE : View.GONE);
        if (nextBtn != null) nextBtn.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        if (topBtn != null) topBtn.setVisibility(curPage > 1 ? View.VISIBLE : View.GONE);
        if (bottomBtn != null) bottomBtn.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        fadeEdgeHints(0.6f);          // 半透明，作为轻量提示而非抢眼按钮
        scheduleHideEdgeHints();
    }

    /** 把当前可见的边缘按钮整体淡入/淡出到目标透明度 */
    private void fadeEdgeHints(float to) {
        View[] bs = { prevBtn, nextBtn, topBtn, bottomBtn };
        for (View v : bs) {
            if (v != null && v.getVisibility() == View.VISIBLE) {
                v.animate().alpha(to).setDuration(350).start();
            }
        }
    }

    /** 重置自动隐藏计时；在用户持续操作时保持提示可见，停手后准时淡出 */
    private void scheduleHideEdgeHints() {
        edgeHintHandler.removeCallbacks(edgeHintHide);
        edgeHintHandler.postDelayed(edgeHintHide, EDGE_HINT_DELAY);
    }

    /** 更新标题：软件名 + 源名（不再显示「已加载 N 部」，避免标题过长挡住右上角天气）；同时联动返回按钮显隐 */
    private void updateTitle() {
        String src = (curSite != null) ? curSite.name : "";
        if (mode == MODE_CAT) {
            // 分类页：隐藏「致强视频」，只显示接口名 + 该分类总影片数
            title.setText(src);
        } else {
            title.setText("致强视频 ▸ " + src);
        }
        updateBackBtn();
    }

    // ---------- Apple TV 风格：自定义高清壁纸 ----------

    /** 应用背景壁纸：本地设置的壁纸优先，其次远程 ads.json 配置，都没有则保持内置深蓝渐变 */
    private void applyWallpaper() {
        if (bg == null) return;
        String url = sp.getString(KEY_WALL, "");
        if (url == null || url.length() == 0) url = AdHelper.getWallpaper();
        if (url != null && url.length() > 0) {
            // 背景大图降采样到 400px 够清晰且省内存（1G 盒子也吃得消）
            ImageLoader.load(url, bg, 400);
        }
    }

    // ---------- Apple TV 风格：天气预报（Open-Meteo 免密钥）----------

    /** 拉取并展示天气；30 分钟缓存，任意失败均静默隐藏，绝不弹 Toast / 阻塞 */
    private void fetchWeather() { fetchWeather(false); }

    private void fetchWeather(final boolean force) {
        if (weather == null) return;
        long last = sp.getLong("wx_ts", 0);
        String cached = sp.getString("wx_str", "");
        if (!force && cached.length() > 0 && (System.currentTimeMillis() - last < 30 * 60 * 1000)) {
            weather.setText(cached);
            weather.setVisibility(View.VISIBLE);
            return;
        }
        // 天气位置：本地手动选择的城市优先，其次远程 ads.json 配置，都没有则默认北京
        String pCity = sp.getString(KEY_WX_CITY, "");
        final String city;
        final double lat, lon;
        if (pCity != null && pCity.length() > 0) {
            city = pCity;
            lat = sp.getFloat(KEY_WX_LAT, 39.9042f);
            lon = sp.getFloat(KEY_WX_LON, 116.4074f);
        } else {
            city = AdHelper.getWeatherCity();
            lat = AdHelper.getWeatherLat();
            lon = AdHelper.getWeatherLon();
        }
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon + "&current_weather=true";
                    String json = Net.getText(url, null);
                    if (json == null || json.trim().length() == 0) return null;
                    JSONObject o = new JSONObject(json.trim());
                    JSONObject cw = o.getJSONObject("current_weather");
                    double t = cw.getDouble("temperature");
                    int code = cw.optInt("weathercode", 0);
                    return city + "  " + Math.round(t) + "°  " + wxText(code);
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String s) {
                if (s != null && s.length() > 0) {
                    weather.setText(s);
                    weather.setVisibility(View.VISIBLE);
                    sp.edit().putString("wx_str", s).putLong("wx_ts", System.currentTimeMillis()).apply();
                } else if (force) {
                    weather.setVisibility(View.GONE);
                }
                // 非 force 且缓存为空、拉取失败：保持隐藏，不打扰用户
            }
        }.execute();
    }

    /** WMO 天气代码 → 中文（覆盖常见代码，未命中归为「多云」） */
    private static String wxText(int code) {
        switch (code) {
            case 0: return "晴";
            case 1:
            case 2: return "多云";
            case 3: return "阴";
            case 45:
            case 48: return "雾";
            case 51:
            case 53:
            case 55:
            case 56:
            case 57: return "冻雨";
            case 61:
            case 63:
            case 65:
            case 66:
            case 67:
            case 80:
            case 81:
            case 82: return "雨";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86: return "雪";
            case 95:
            case 96:
            case 99: return "雷阵雨";
            default: return "多云";
        }
    }

    /** 天气城市点选：弹出可滚动的城市列表，选中后写入设置并立即刷新天气（默认北京） */
    private void showCityPicker() {
        final String[] cities = WX_CITIES;
        final double[] lats = WX_LATS;
        final double[] lons = WX_LONS;
        int cur = -1;
        String now = sp.getString(KEY_WX_CITY, "");
        for (int i = 0; i < cities.length; i++) if (cities[i].equals(now)) { cur = i; break; }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择天气城市（当前：" + (now.length() > 0 ? now : "北京") + "）");
        b.setSingleChoiceItems(cities, cur, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                sp.edit().putString(KEY_WX_CITY, cities[w])
                        .putFloat(KEY_WX_LAT, (float) lats[w])
                        .putFloat(KEY_WX_LON, (float) lons[w])
                        .putString("wx_str", "").apply();   // 清缓存，强制刷新
                d.dismiss();
                fetchWeather(true);
                Toast.makeText(MainActivity.this, "天气城市已设为：" + cities[w], Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 读取 Manifest 中的 versionName，用于标题栏显示（避免写死版本号） */
    private String appVer() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "3.0";
        }
    }

    /** 读取 Manifest 中的 versionCode（用于升级比对，比 versionName 可靠） */
    private int appCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- 预留升级接口 ----------
    private ProgressDialog pdUpdate;
    private UpdateInfo pendingUpdate;

    private static class UpdateInfo {
        int versionCode;
        String versionName;
        String url;
        String note;
        boolean force;

        UpdateInfo(int c, String v, String u, String n, boolean f) {
            versionCode = c;
            versionName = v;
            url = u;
            note = n;
            force = f;
        }
    }

    /** 检查更新：manual=true 时无论结果都给反馈；false 为静默后台检查（仅发现新版本才轻提示） */
    private void checkUpdate(boolean manual) {
        new UpdateTask(manual).execute();
    }

    private class UpdateTask extends AsyncTask<Void, Void, String> {
        private boolean manual;
        private String errMsg = "";

        UpdateTask(boolean m) {
            manual = m;
        }

        @Override
        protected void onPreExecute() {
            if (manual) {
                pdUpdate = new ProgressDialog(MainActivity.this);
                pdUpdate.setMessage("正在检查更新…");
                pdUpdate.setCancelable(false);
                pdUpdate.show();
            }
        }

        @Override
        protected String doInBackground(Void... v) {
            try {
                String json = Net.getText(UPDATE_URL, null);
                if (json == null || json.trim().length() == 0) {
                    errMsg = "更新信息为空";
                    return "bad";
                }
                JSONObject o = new JSONObject(json.trim());
                int nc = o.optInt("versionCode", 0);
                String nv = o.optString("versionName", "");
                String u = o.optString("url", "");
                String note = o.optString("note", "");
                boolean force = o.optBoolean("force", false);
                if (nc <= 0 || u.length() == 0) {
                    errMsg = "更新信息格式不正确";
                    return "bad";
                }
                int cur = appCode();
                if (nc > cur) {
                    pendingUpdate = new UpdateInfo(nc, nv, u, note, force);
                    return "new";
                }
                return "latest";
            } catch (Throwable t) {
                errMsg = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
                return "err";
            }
        }

        @Override
        protected void onPostExecute(String r) {
            if (pdUpdate != null) {
                pdUpdate.dismiss();
                pdUpdate = null;
            }
            if ("new".equals(r)) {
                showUpdateDialog();
            } else if ("latest".equals(r)) {
                if (manual) Toast.makeText(MainActivity.this,
                        "已是最新版本 v" + appVer(), Toast.LENGTH_SHORT).show();
            } else if ("bad".equals(r)) {
                if (manual) Toast.makeText(MainActivity.this,
                        "更新信息错误：" + errMsg, Toast.LENGTH_SHORT).show();
            } else { // err
                if (manual) Toast.makeText(MainActivity.this,
                        "检查更新失败：" + errMsg, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 弹出升级提示（静默检查发现新版本也走这里） */
    private void showUpdateDialog() {
        if (pendingUpdate == null) return;
        final UpdateInfo info = pendingUpdate;
        String msg = (info.note != null && info.note.length() > 0)
                ? info.note : "发现新版本，是否立即下载安装？";
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("发现新版本 v" + info.versionName + "（当前 v" + appVer() + "）");
        b.setMessage(msg);
        b.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                downloadAndInstall(info.url);
            }
        });
        if (!info.force) {
            b.setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    pendingUpdate = null;
                }
            });
        }
        b.setCancelable(!info.force);
        b.show();
    }

    /** 下载 APK 到 app 外部私有目录（无需存储权限），下完自动调起系统安装器 */
    private void downloadAndInstall(final String url) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在下载更新…");
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();
        new DownloadTask(pd).execute(url);
    }

    private class DownloadTask extends AsyncTask<String, Integer, File> {
        private ProgressDialog pd;
        private String err;

        DownloadTask(ProgressDialog p) {
            pd = p;
        }

        @Override
        protected File doInBackground(String... a) {
            String url = a[0];
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getCacheDir();
            dir.mkdirs();
            File out = new File(dir, "update.apk");
            OutputStream os = null;
            try {
                byte[] data = Net.getBytes(url, null);
                if (data == null || data.length == 0) {
                    err = "下载内容为空";
                    return null;
                }
                os = new FileOutputStream(out);
                os.write(data);
                os.flush();
                return out;
            } catch (Throwable t) {
                err = (t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName();
                return null;
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(File f) {
            if (pd != null) pd.dismiss();
            if (f != null && f.exists() && f.length() > 0) {
                installApk(f);
            } else {
                Toast.makeText(MainActivity.this, "下载失败：" + err, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 调起系统安装器安装 APK（targetSdk 23，file:// 在老电视上可直接安装） */
    private void installApk(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动安装：" + (t.getMessage() != null ? t.getMessage() : ""),
                    Toast.LENGTH_LONG).show();
        }
    }

    private class Loader extends AsyncTask<String, Void, LoadResult> {
        private String kind;

        @Override
        protected void onPreExecute() {
            loading = true;
            // v4.7：滚动海报只在「首次内容加载」（网格还空）时展示。
            // 翻页 / 换分类 / 搜索等后续加载一律保持网格可见、不弹海报、不挡浏览，
            // 避免「每次按键都盖一层加载动画」导致看起来很慢。
            prog.setVisibility(View.GONE);
            boolean firstContent = (items == null || items.isEmpty());
            if (loadingPosters != null) {
                if (firstContent) loadingPosters.startLoading();
                else loadingPosters.stopLoading();
            }
            if (loadingLabel != null) {
                loadingLabel.setVisibility(firstContent ? View.VISIBLE : View.GONE);
            }
        }

        @Override
        protected void onCancelled() {
            if (loadingPosters != null) loadingPosters.stopLoading();
            if (loadingLabel != null) loadingLabel.setVisibility(View.GONE);
            loading = false;
        }

        @Override
        protected LoadResult doInBackground(String... a) {
            kind = a[0];
            LoadResult r = new LoadResult();

            try {
                SiteApi.ListResult lr = null;
                if ("home".equals(kind)) {
                    if (curPage == 1) {
                        cats = fetchCatsWithFallback();   // 首页分类：失败自动换源
                    }
                    r.cats = cats;
                    lr = collectFilled(MAX_ITEMS_PER_PAGE, false);
                } else if ("other".equals(kind)) {
                    lr = collectFilled(MAX_ITEMS_PER_PAGE, false);
                } else if ("cat".equals(kind)) {
                    lr = collectFilled(MAX_ITEMS_PER_PAGE, false);
                } else if ("search".equals(kind)) {
                    // 搜索：优先当前源；失败（返回网页/verify/超时）自动换下一个 JSON 源
                    lr = collectFilled(MAX_ITEMS_PER_PAGE, true);
                } else if ("more".equals(kind) || "prev".equals(kind)) {
                    if (mode == MODE_SEARCH) lr = collectFilled(MAX_ITEMS_PER_PAGE, true);
                    else lr = collectFilled(MAX_ITEMS_PER_PAGE, false);
                }
                r.items = (lr != null) ? lr.items : new ArrayList<VodItem>();
                r.hasMore = (lr != null) ? lr.hasMore : false;
                r.pageCount = (lr != null) ? lr.pageCount : 0;
                r.total = (lr != null) ? lr.total : 0;
                if (r.items == null || r.items.isEmpty()) r.hasMore = false;

                // 限制每屏最多30条（collectFilled 已保证，这里兜底）
                if (r.items != null && r.items.size() > MAX_ITEMS_PER_PAGE) {
                    r.items = new ArrayList<VodItem>(r.items.subList(0, MAX_ITEMS_PER_PAGE));
                }
            } catch (Throwable t) {
                r.err = t.getMessage();
            }
            return r;
        }

        @Override
        protected void onPostExecute(LoadResult r) {
            prog.setVisibility(View.GONE);
            if (loadingPosters != null) loadingPosters.stopLoading();
            if (loadingLabel != null) loadingLabel.setVisibility(View.GONE);
            loading = false;
            if (switchedMsg != null) {
                final String m = switchedMsg;
                switchedMsg = null;
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
            }
            if (r.err != null) {
                Toast.makeText(MainActivity.this, "加载失败：" + r.err, Toast.LENGTH_LONG).show();
                hasMore = false;
                return;
            }
            if ("home".equals(kind)) {
                cats = (r.cats != null) ? r.cats : new ArrayList<Category>();
                items = (r.items != null) ? r.items : new ArrayList<VodItem>();
                // 首页按播放量排列（热门排前）
                SiteApi.sortByHits(items);
                buildCats();
            } else if ("cat".equals(kind) || "other".equals(kind)) {
                items = (r.items != null) ? r.items : new ArrayList<VodItem>();
            } else if ("search".equals(kind)) {
                items = (r.items != null) ? r.items : new ArrayList<VodItem>();
                // 搜索结果：画质优先（4K/蓝光/高清排前），再按播放量
                SiteApi.sortSearch(items);
            } else if ("prev".equals(kind)) {
                items = (r.items != null) ? r.items : new ArrayList<VodItem>();
                if (mode == MODE_SEARCH) SiteApi.sortSearch(items);
                else if (mode == MODE_HOME) SiteApi.sortByHits(items);
            } else {
                // 「下拉加载更多」：追加而非整页替换，配合无限滚动；不重排，避免已显示内容乱序
                if (r.items != null && !r.items.isEmpty()) {
                    items.addAll(r.items);
                }
            }
            hasMore = (r.hasMore && r.items != null && r.items.size() > 0);
            curPageCount = (r.pageCount > 0) ? r.pageCount : curPageCount;
            // 分类页：保存接口返回的总条数用于标题显示
            if ("cat".equals(kind)) curTotal = r.total;
            curPageCount = (r.pageCount > 0) ? r.pageCount : curPageCount;
            updateTitle();
            // 确保返回按钮状态始终与当前 mode 一致（异步加载完成后再次校准）
            updateBackBtn();
            // 边缘提示：按状态恢复可见性 + 半透明淡入，并预约自动隐藏（静止后淡出）
            showEdgeHints();
            adapter.notifyDataSetChanged();
            if (kind.equals("home") || kind.equals("cat") || kind.equals("search")
                    || kind.equals("other") || kind.equals("prev")) {
                // 全新加载：焦点回到网格顶部，方向键即时生效
                grid.setSelection(0);
                prevSel = null;
                grid.requestFocus();
            }
            // 注意：「more」分支不跳回顶部、不抢焦点，保留用户当前滚动位置，滚动到底继续追加
        }
    }

    private static class LoadResult {
        List<Category> cats;
        List<VodItem> items;
        boolean hasMore = true;
        int pageCount = 0;
        int total = 0;
        String err;
        boolean fromCache = false;
    }

    // ---------- 跨源页填充 & 自动换源（首页/分类/其他/搜索通用）----------

    /** 翻页快照：记录填充游标与跨页残留，用于「上一页」精确还原 */
    private static class FillSnap {
        int page;
        ArrayList<VodItem> left;
    }

    /** 重置填充游标（全新加载某分类/搜索时调用） */
    private void resetFill(boolean search) {
        if (search) { searchFillPage = 1; searchFillLeft.clear(); searchHist.clear(); }
        else { browseFillPage = 1; browseFillLeft.clear(); browseHist.clear(); }
    }

    /** 压入当前填充状态快照（加载本页前调用，供上一页还原） */
    private void pushFillSnap(boolean search) {
        FillSnap s = new FillSnap();
        s.page = search ? searchFillPage : browseFillPage;
        s.left = new ArrayList<VodItem>(search ? searchFillLeft : browseFillLeft);
        (search ? searchHist : browseHist).add(s);
    }

    /** 还原到上一页的填充状态（上一页时调用）。
     *  栈顶是当前页的「前置快照」，先丢弃它，再还原新的栈顶（即上一页的前置快照）。 */
    private void restoreFillSnap(boolean search) {
        List<FillSnap> h = search ? searchHist : browseHist;
        if (h.isEmpty()) {
            if (search) { searchFillPage = 1; searchFillLeft.clear(); }
            else { browseFillPage = 1; browseFillLeft.clear(); }
            return;
        }
        h.remove(h.size() - 1);                       // 丢弃当前页前置快照
        FillSnap target = h.isEmpty() ? null : h.get(h.size() - 1);
        if (target == null) {                          // 已在第一页
            if (search) { searchFillPage = 1; searchFillLeft.clear(); }
            else { browseFillPage = 1; browseFillLeft.clear(); }
            return;
        }
        if (search) { searchFillPage = target.page; searchFillLeft = new ArrayList<VodItem>(target.left); }
        else { browseFillPage = target.page; browseFillLeft = new ArrayList<VodItem>(target.left); }
    }

    /** 把自动选中的可用源设为长期默认（写入 sp，下次开机直接用），不重新加载当前页 */
    private void applyDefaultSource(Site s) {
        if (s == null) return;
        curSite = s;
        api = new SiteApi(s);
        sp.edit().putString(KEY_SRC_NAME, s.name).putString(KEY_SRC_API, s.api).apply();
    }

    /** 首页分类：当前源失败则自动换下一个直连源，命中即设为默认 */
    private List<Category> fetchCatsWithFallback() throws Exception {
        Exception lastErr = null;
        try { return api.home().categories; }
        catch (Exception e) { lastErr = e; }
        int start = indexOfCurrentInDirect();
        int tried = 0;
        for (int k = 1; k <= DIRECT_SITES.length && tried < 6; k++) {
            int idx = start + k;
            if (idx >= DIRECT_SITES.length) idx -= DIRECT_SITES.length;
            Site s = directSite(DIRECT_SITES[idx][0], DIRECT_SITES[idx][1]);
            tried++;
            try {
                List<Category> c = new SiteApi(s).home().categories;
                if (c != null && !c.isEmpty()) {
                    applyDefaultSource(s);
                    switchedMsg = "当前源不可用，已自动改用：" + s.name;
                    return c;
                }
            } catch (Exception ignored) {}
        }
        if (lastErr != null) throw lastErr;
        return new ArrayList<Category>();
    }

    /** 浏览列表项：当前源失败则自动换下一个直连源，命中即设为默认 */
    private SiteApi.ListResult fetchItemsWithFallback(int kind, int srcPg) throws Exception {
        Exception lastErr = null;
        try { return doBrowseItems(api, kind, srcPg); }
        catch (Exception e) { lastErr = e; }
        int start = indexOfCurrentInDirect();
        int tried = 0;
        for (int k = 1; k <= DIRECT_SITES.length && tried < 6; k++) {
            int idx = start + k;
            if (idx >= DIRECT_SITES.length) idx -= DIRECT_SITES.length;
            Site s = directSite(DIRECT_SITES[idx][0], DIRECT_SITES[idx][1]);
            tried++;
            try {
                SiteApi.ListResult r = doBrowseItems(new SiteApi(s), kind, srcPg);
                if (r != null && r.items != null && !r.items.isEmpty()) {
                    applyDefaultSource(s);
                    switchedMsg = "当前源不可用，已自动改用：" + s.name;
                    return r;
                }
            } catch (Exception ignored) {}
        }
        if (lastErr != null) throw lastErr;
        return new SiteApi.ListResult();
    }

    /** 按 mode 取列表项（首页=全部最新 / 分类 / 其他合并） */
    private SiteApi.ListResult doBrowseItems(SiteApi a, int kind, int srcPg) throws Exception {
        if (kind == MODE_CAT) return a.list(curTid, srcPg);
        if (kind == MODE_OTHER) return a.listMulti(otherTids, srcPg);
        return a.list("", srcPg);   // HOME
    }

    /**
     * 跨源页填充到目标条数（默认30）：先放上一页跨页残留，再逐源页拉取；
     * 多出的部分留存到下一页（left），保证不丢内容且每屏满行（5/6列都无零头）。
     */
    private SiteApi.ListResult collectFilled(int want, boolean search) throws Exception {
        SiteApi.ListResult out = new SiteApi.ListResult();
        List<VodItem> buf = new ArrayList<VodItem>();
        List<VodItem> left = search ? searchFillLeft : browseFillLeft;
        int fp = search ? searchFillPage : browseFillPage;
        buf.addAll(left); left.clear();
        boolean sourceHasMore = false;
        int guard = 0;
        while (buf.size() < want && guard < 8) {
            guard++;
            SiteApi.ListResult sub;
            if (search) {
                sub = searchWithFallback(curKw, fp, fp == 1);
            } else {
                sub = fetchItemsWithFallback(mode, fp);
            }
            fp++;
            if (sub == null || sub.items == null || sub.items.isEmpty()) {
                sourceHasMore = (sub != null) ? sub.hasMore : false;
                break;
            }
            buf.addAll(sub.items);
            sourceHasMore = sub.hasMore;
            if (!sub.hasMore) break;
        }
        if (buf.size() > want) {
            left.addAll(new ArrayList<VodItem>(buf.subList(want, buf.size())));
            buf = new ArrayList<VodItem>(buf.subList(0, want));
            out.hasMore = true;
        } else {
            out.hasMore = sourceHasMore && buf.size() > 0;
        }
        out.items = buf;
        out.pageCount = 0;
        if (search) searchFillPage = fp; else browseFillPage = fp;
        return out;
    }

    // ---------- 搜索自动换源（当前源返回网页/verify/超时时，自动试下一个 JSON 源）----------

    /** 搜索使用的 SiteApi：若已回退到某个可用源，就用它（保证翻页一致） */
    private SiteApi searchApi() {
        if (searchSite != null) return new SiteApi(searchSite);
        return api;
    }

    /** 当前源在直连列表中的下标；不在列表里（自定义源）返回 -1，回退从 0 开始 */
    private int indexOfCurrentInDirect() {
        String cur = (curSite != null && curSite.api != null) ? curSite.api : "";
        for (int i = 0; i < DIRECT_SITES.length; i++) {
            if (DIRECT_SITES[i][1].equalsIgnoreCase(cur)) return i;
        }
        return -1;
    }

    /**
     * 搜索并自动换源。
     * @param resetSite 为 true 时（全新搜索）重置回退状态，从当前源重新开始；
     *                  分页（下一页/上一页）传 false，沿用已命中的源。
     */
    private SiteApi.ListResult searchWithFallback(String kw, int pg, boolean resetSite) throws Exception {
        if (resetSite) {
            searchSite = null;
            switchedMsg = null;
        }
        Exception lastErr = null;
        try {
            SiteApi.ListResult r = searchApi().search(kw, pg);
            if (r != null) return r;            // 合法结果（含空结果）直接返回，不回退
        } catch (Exception e) {
            lastErr = e;
        }
        // 当前源失败：自动换下一个 JSON 源，最多试 5 个，避免卡死
        int start = indexOfCurrentInDirect();
        int tried = 0;
        for (int k = 1; k <= DIRECT_SITES.length && tried < 5; k++) {
            int idx = start + k;
            if (idx >= DIRECT_SITES.length) idx -= DIRECT_SITES.length;
            Site s = directSite(DIRECT_SITES[idx][0], DIRECT_SITES[idx][1]);
            tried++;
            try {
                SiteApi.ListResult r = new SiteApi(s).search(kw, pg);
                if (r != null && !r.items.isEmpty()) {
                    searchSite = s;
                    applyDefaultSource(s);   // 顺手把可用源设为长期默认
                    switchedMsg = "当前搜索源不可用，已自动改用：" + s.name;
                    return r;
                }
            } catch (Exception ignored) {
            }
        }
        if (lastErr != null) throw lastErr;
        return new SiteApi.ListResult();   // 全部失败：返回空，由上层提示
    }

    // ---------- 分类条（完整镜像源的真实分类）----------

    /** 顶层大类：当源存在更细的子分类（国产剧/美剧…）时，这些往往为空占位，自动隐藏 */
    private static final String[] GENERIC_HIDE = {"电影", "电视剧"};
    /** 子分类标识关键词：源分类名含其一即视为「有真实子分类」，触发顶层大类隐藏 */
    private static final String[] GRANULAR_MARK = {"国产", "港台", "欧美", "美", "日", "韩", "泰"};

    /** 统一按钮焦点特效：禁用系统默认焦点缩放动画（避免真机 Android TV 上按钮被压小/卡死），
     *  仅保留「亮蓝边 + 1.1 放大」做焦点提示。 */
    private void focusZoom(final TextView t) {
        if (android.os.Build.VERSION.SDK_INT >= 21) t.setStateListAnimator(null);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setScaleX(has ? 1.1f : 1f);
                v.setScaleY(has ? 1.1f : 1f);
                v.setBackgroundResource(has ? R.drawable.btn_dark_focused : R.drawable.btn_dark);
            }
        });
    }

    private void buildCats() {
        catRow.removeAllViews();
        // 「首页」：最新/全部（采用源真实数据，可翻页）
        TextView homeBtn = new TextView(this);
        homeBtn.setText("首页");
        homeBtn.setTextSize(18);
        homeBtn.setPadding(20, 12, 20, 12);
        homeBtn.setFocusable(true);
        homeBtn.setClickable(true);
        homeBtn.setTextColor(0xffe8e8e8);
        homeBtn.setBackgroundResource(R.drawable.btn_dark);
        focusZoom(homeBtn);
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadHome(); }
        });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hl.setMargins(0, 0, 16, 0);
        homeBtn.setLayoutParams(hl);
        catRow.addView(homeBtn);

        // 完整镜像源的真实分类（外层已是 HorizontalScrollView，可横滚）。
        // 若源含细分子类（国产剧/美剧…），自动隐藏空的顶层大类（电影/电视剧），
        // 避免点开无条目、内容被塞进「其他」。
        boolean hasGranular = false;
        for (Category c : cats) {
            if (c != null && c.name != null) {
                for (String k : GRANULAR_MARK) {
                    if (c.name.contains(k)) { hasGranular = true; break; }
                }
            }
            if (hasGranular) break;
        }
        for (Category c : cats) {
            if (c == null || c.id == null || c.id.length() == 0) continue;
            if (hasGranular) {
                boolean hide = false;
                for (String g : GENERIC_HIDE) {
                    if (g.equals(c.name)) { hide = true; break; }
                }
                if (hide) continue;
            }
            catRow.addView(makeCatTab(c.name, c.id, false));
        }
    }

    /** 生成一个分类标签按钮（isOther=true 表示点它加载「其他」合并列表） */
    private TextView makeCatTab(final String name, final String tid, final boolean isOther) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(18);
        t.setPadding(20, 12, 20, 12);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTextColor(0xffe8e8e8);
        t.setBackgroundResource(R.drawable.btn_dark);
        focusZoom(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (isOther) loadOther();
                else loadCategory(tid);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 16, 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** 打开独立搜索页，并把当前源的「真实分类」传过去（弥补首页只显示前5个分类的遗憾） */
    private void openSearch() {
        Intent si = new Intent(MainActivity.this, SearchActivity.class);
        if (cats != null && !cats.isEmpty()) {
            String[] names = new String[cats.size()];
            String[] tids = new String[cats.size()];
            for (int i = 0; i < cats.size(); i++) {
                names[i] = cats.get(i).name;
                tids[i] = cats.get(i).id;
            }
            si.putExtra("catNames", names);
            si.putExtra("catTids", tids);
        }
        if (curSite != null && curSite.api != null) si.putExtra("apiUrl", curSite.api);
        startActivity(si);
    }

    // ---------- 操作按钮栏（搜索 / 切换源 / 测速 / 换接口）----------

    private void buildActions() {
        actRow.removeAllViews();
        String[] labels = {"搜索", "收藏", "测速选最快", "设置"};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setTextSize(18);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setPadding(24, 12, 24, 12);
            t.setFocusable(true);
            t.setClickable(true);
            // 圆角深色底 + 白色粗体 + 立体阴影，任何背景都看得清；焦点态由 selector 自动高亮
            t.setTextColor(0xffe8e8e8);
            t.setShadowLayer(2f, 1f, 1f, 0xff000000);
            t.setBackgroundResource(R.drawable.btn_dark);
            focusZoom(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == 0) openSearch();
                    else if (idx == 1) startActivity(new Intent(MainActivity.this, FavActivity.class));
                    else if (idx == 2) speedTestApis(true);
                    else if (idx == 3) openSettings();
                }
            });
            // 按钮之间留统一间距（16dp，与分类条一致，避免互相紧贴）
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 16, 0);
            t.setLayoutParams(lp);
            actRow.addView(t);
        }

        // 留言入口（公众号二维码）：来自 AdHelper，支持远程 ads.json 配置。
        // 与标准按钮同款样式 + 焦点特效，遥控器方向键可直接选中、确认打开。
        for (final AdHelper.TopButton tb : AdHelper.getTopButtons()) {
            TextView t = new TextView(this);
            t.setText(tb.label);
            t.setTextSize(18);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setPadding(24, 12, 24, 12);
            t.setFocusable(true);
            t.setClickable(true);
            t.setTextColor(0xffe8e8e8);
            t.setShadowLayer(2f, 1f, 1f, 0xff000000);
            t.setBackgroundResource(R.drawable.btn_dark);
            focusZoom(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("wechat".equals(tb.type)) AdHelper.showWechat(MainActivity.this);
                    else AdHelper.openUrl(MainActivity.this, tb.url);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 16, 0);
            t.setLayoutParams(lp);
            actRow.addView(t);
        }

        // 「返回」按钮：仅首页之外的栏目页面（分类/搜索/其他）才显示，点击回首页。
        // 首页是最顶层，不需要返回，故初始按 mode 显隐，切换时由 updateBackBtn() 刷新。
        navBack = new TextView(this);
        navBack.setText("← 返回");
        navBack.setTextSize(18);
        navBack.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        navBack.setPadding(24, 12, 24, 12);
        navBack.setFocusable(true);
        navBack.setClickable(true);
        navBack.setTextColor(0xffe8e8e8);
        navBack.setShadowLayer(2f, 1f, 1f, 0xff000000);
        navBack.setBackgroundResource(R.drawable.btn_dark);
        focusZoom(navBack);
        navBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goBack(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 0, 16, 0);
        navBack.setLayoutParams(blp);
        actRow.addView(navBack);
        updateBackBtn();
    }

    /** 返回按钮显隐：搜索页显示；首页/分类页/其他页隐藏 */
    private void updateBackBtn() {
        if (navBack != null) navBack.setVisibility(mode == MODE_SEARCH ? View.VISIBLE : View.GONE);
    }

    /** 返回：搜索页回首页，其他情况等同系统返回（退出） */
    private void goBack() {
        if (mode == MODE_SEARCH) {
            loadHome();
        } else {
            onBackPressed();
        }
    }

    // ---------- 打开设置页 ----------

    private void openSettings() {
        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
    }

    // ---------- 配置 / 菜单 ----------

    // 旧 showConfig()（换接口弹窗）已移除：其全部功能（自定义接口 / 直连源列表 / 更新接口源 /
    // 检查更新）现由全屏 SettingsActivity 承载，入口为操作栏「设置」与菜单「设置」。

    // ---------- 直连源列表：本地恢复 / 远程更新 ----------

    /** 恢复上次「更新接口源」拉取的直连源列表；无记录或格式错误则维持内置默认表。
     *  抽成静态方法，供 MainActivity 与 SettingsActivity 共用同一份偏好。 */
    public static void loadSavedDirectSites(SharedPreferences sp) {
        try {
            String json = sp.getString(KEY_SITES_JSON, "");
            if (json == null || json.trim().length() == 0) return;
            String[][] parsed = parseSitesJson(json);
            if (parsed != null && parsed.length > 0) DIRECT_SITES = parsed;
        } catch (Throwable t) {
            android.util.Log.w("DuSheMain", "loadSavedDirectSites", t);
        }
    }

    /** 解析直连源 JSON：支持 [{"name":..,"api":..}]，api 字段也可叫 url */
    public static String[][] parseSitesJson(String json) {
        try {
            JSONArray a = new JSONArray(json.trim());
            List<String[]> out = new ArrayList<String[]>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                String api = o.optString("api", "");
                if (api.length() == 0) api = o.optString("url", "");
                if (name.length() > 0 && api.length() > 0) {
                    out.add(new String[]{name, api});
                }
            }
            return out.isEmpty() ? null : out.toArray(new String[out.size()][]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 原 updateDirectSites() 已迁移至 SettingsActivity（更新接口源按钮）。 */

    private void showOptions() {
        String[] opts = {
                "搜索",
                "我的收藏",
                "测速结果 / 切换源",
                "重新测速（" + DIRECT_SITES.length + " 个直连源）",
                "设置（源 / 列数 / 自启动）",
                "返回首页"
        };
        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                    if (w == 0) {
                        openSearch();
                    } else if (w == 1) {
                        startActivity(new Intent(MainActivity.this, FavActivity.class));
                    } else if (w == 2) {
                        // 有上次结果就直接列出来切换，没有就先测
                        if (apiSpeed != null && !apiSpeed.isEmpty()) showApiSpeedResult(apiSpeed);
                        else speedTestApis(false);
                    } else if (w == 3) {
                        speedTestApis(false);
                    } else if (w == 4) {
                        openSettings();
                    } else {
                        loadHome();
                    }
                    }
                })
                .show();
    }

    /** 处理从搜索页跳转回来的搜索关键字（MainActivity 是 singleTask，会走这里） */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getStringExtra("action");
        if ("checkupdate".equals(action)) {
            // 来自设置页「检查更新」按钮：手动检查软件版本
            checkUpdate(true);
            return;
        }
        String catTid = intent.getStringExtra("catTid");
        String kw = intent.getStringExtra("searchKw");
        if (catTid != null && catTid.length() > 0) {
            loadCategory(catTid);
        } else if (kw != null && kw.length() > 0) {
            loadSearch(kw);
        }
    }

    /** 当前焦点是否落在 root 容器内（含 root 自身） */
    private boolean focusInside(View root) {
        View f = getCurrentFocus();
        if (f == null || root == null) return false;
        View p = f;
        while (p != null) {
            if (p == root) return true;
            if (p.getParent() instanceof View) p = (View) p.getParent();
            else break;
        }
        return false;
    }

    /** 取一行中水平位置最接近参照 View 的可聚焦按钮，用于上下两行之间跳转 */
    private View nearestInRow(LinearLayout row, View from) {
        if (row == null || from == null) return null;
        int[] fl = new int[2], tl = new int[2];
        try { from.getLocationOnScreen(fl); } catch (Throwable t) { return null; }
        float fx = fl[0] + from.getWidth() / 2f;
        View best = null;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c == null || c.getVisibility() != View.VISIBLE || !c.isFocusable()) continue;
            try { c.getLocationOnScreen(tl); } catch (Throwable t) { continue; }
            float d = Math.abs(tl[0] + c.getWidth() / 2f - fx);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    /**
     * v4.6 修复：遥控器「上」键两级回顶。
     * 网格第一行 → 分类行；分类行 → 顶部导航栏（搜索/收藏/测速/设置/留言）。
     * 分类按钮是动态生成的、没有 nextFocusUpId，系统几何搜索会把焦点丢给
     * 标题栏那个不可见的 focusable 容器，表现为「只能到分类，上不去导航栏」。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN
                && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            View f = getCurrentFocus();
            if (f != null && focusInside(catRow)) {
                View t = nearestInRow(actRow, f);
                if (t != null) { t.requestFocus(); return true; }
            } else if (f != null && focusInside(grid)) {
                int cols = Math.max(1, grid.getNumColumns());
                int pos = grid.getSelectedItemPosition();
                if (pos >= 0 && pos < cols) {
                    View t = nearestInRow(catRow, f);
                    if (t != null) { t.requestFocus(); return true; }
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        // v4.6：左右翻页只在焦点位于影片网格时生效。以前无条件拦截，
        // 导致顶部导航栏 / 分类行按左右无法横向移动焦点。
        boolean inGrid = focusInside(grid);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && curPage > 1 && inGrid) {
            loadPrev(); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && hasMore && inGrid) {
            loadMore(); return true;
        }
        // 其他方向键：刷新边缘提示
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            showEdgeHints();
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showOptions();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) showEdgeHints();
        return super.dispatchTouchEvent(ev);
    }

    // ---------- 状态 ----------

    private void showStatus(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    // ---------- 适配器 ----------

    /** 把卡片复位为「未选中」默认态：取消缩放/金边，片名单行省略号，图片恢复裁切 */
    private void resetCard(View v) {
        if (v == null) return;
        v.setScaleX(1f);
        v.setScaleY(1f);
        if (android.os.Build.VERSION.SDK_INT >= 21) v.setElevation(0f);
        v.setBackgroundResource(R.drawable.card_bg_selector);
        TextView nm = (TextView) v.findViewById(R.id.name);
        if (nm != null) {
            nm.setSingleLine(true);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        ImageView pc = (ImageView) v.findViewById(R.id.pic);
        if (pc != null) pc.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
    }

    /** 影视图高度：列宽 × 4/3（3:4 竖向海报），720 与 1080 下保持一致比例 */
    private int posterHeight(ViewGroup parent) {
        int cols = grid.getNumColumns();
        if (cols <= 0) cols = (getResources().getDisplayMetrics().widthPixels <= 1280) ? 5 : 6;
        int gw = (parent != null) ? parent.getWidth() : 0;
        if (gw <= 0) gw = getResources().getDisplayMetrics().widthPixels;
        int pad = grid.getPaddingLeft() + grid.getPaddingRight();
        int hsp = Math.max(0, cols - 1) * grid.getHorizontalSpacing();
        int colW = (gw - pad - hsp) / cols;
        if (colW <= 0) colW = 120;
        return (int) (colW * 4f / 3f);
    }

    private class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            if (p < 0 || p >= items.size()) return null;
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            // 单条渲染异常不得拖垮整个 GridView（防止某条脏数据导致整页闪退）
            try {
                if (cv == null) {
                    cv = getLayoutInflater().inflate(R.layout.item_grid, null);
                }
                // 禁用卡片默认焦点缩放动画，并用 elevation=0 复位层叠（保证「手动放大」与金边严格同步，
                // 且每次复用都从默认层叠开始，只有选中项才会被临时抬到 Z 轴顶层）
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    cv.setStateListAnimator(null);
                    cv.setElevation(0f);
                }
                VodItem it = items.get(p);
                ImageView pic = (ImageView) cv.findViewById(R.id.pic);
                TextView name = (TextView) cv.findViewById(R.id.name);
                TextView remark = (TextView) cv.findViewById(R.id.remark);
                name.setText(it.name);
                remark.setText(it.remarks != null ? it.remarks : "");
                // 复位为「未选中」默认态（防止复用已聚焦视图时残留2行名/完整图）
                name.setSingleLine(true);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                // 影视图统一 3:4 竖向比例：图片高度 = 列宽 × 4/3（720/1080 都一致）
                int ph = posterHeight(parent);
                ViewGroup.LayoutParams plp = pic.getLayoutParams();
                if (plp == null) plp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph);
                plp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                plp.height = ph;
                pic.setLayoutParams(plp);
                ImageLoader.load(it.pic, pic, 200);  // 提高清晰度：200px
                return cv;
            } catch (Throwable t) {
                // 异常时返回一个空的占位卡片，保证列表可继续滚动
                TextView ph = new TextView(MainActivity.this);
                ph.setHeight(180);
                ph.setText("");
                return ph;
            }
        }
    }
}
