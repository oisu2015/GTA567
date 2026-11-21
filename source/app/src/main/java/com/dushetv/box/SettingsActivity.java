package com.dushetv.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 设置页（全屏，取代原「换接口」弹窗）。
 *
 * <p>包含四类设置：
 * <ol>
 *   <li>每行影片数（排列）：自动(按分辨率) / 5 个 / 6 个，循环切换；返回首页后生效。</li>
 *   <li>开机自启动开关：关掉后 {@link BootReceiver} 不再在重启时拉起主界面。</li>
 *   <li>壁纸：自定义高清壁纸地址（留空用系统渐变），返回首页后生效。</li>
 *   <li>影视接口：点「当前接口」弹出可滚动列表选择；或填自定义接口地址。选源/应用只写
 *       SharedPreferences，由 MainActivity.onResume 回读重新加载，本页不依赖 MainActivity 实例。</li>
 * </ol>
 *
 * <p>TV 适配要点：本页不再把 ListView 嵌进 ScrollView（会导致遥控器焦点陷阱、源列表选不动），
 * 改为「点当前接口 → 弹可滚动选择框」；所有行均为可聚焦/可点按，遵循现有工程纪律。</p>
 */
public class SettingsActivity extends Activity {
    private SharedPreferences sp;
    private EditText etUrl;
    private TextView valCols, valBoot, valWall, valWx, valSource, tvVer;
    private String[][] presets;

    // ===== Bing 每日壁纸库（对话框内共享状态）=====
    private static final String BING_API =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=%d&n=5&mkt=zh-CN";
    private int bingIdx = 0;            // 当前批次起点（0=最新一批）
    private String[][] bingItems = new String[0][2];   // [title, fullUrl]
    private GridView bingGrid;
    private TextView bingStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        // 顶部「← 返回」：模拟器等无遥控器返回键也能点屏返回
        android.widget.TextView backBtn = (android.widget.TextView) findViewById(R.id.back);
        if (backBtn != null) backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        etUrl = (EditText) findViewById(R.id.etUrl);
        valCols = (TextView) findViewById(R.id.valCols);
        valBoot = (TextView) findViewById(R.id.valBoot);
        valWall = (TextView) findViewById(R.id.valWall);
        valWx = (TextView) findViewById(R.id.valWx);
        valSource = (TextView) findViewById(R.id.valSource);
        tvVer = (TextView) findViewById(R.id.tvVer);

        // 当前版本号（动态读 Manifest，避免写死）
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            tvVer.setText("当前版本：v" + pi.versionName);
        } catch (Exception e) {
            tvVer.setText("当前版本：");
        }

        // 直连源列表：与首页共用同一份（并读取上次「更新接口源」拉取的结果）
        MainActivity.loadSavedDirectSites(sp);
        presets = MainActivity.DIRECT_SITES;

        // ---------- 每行影片数（排列） ----------
        applyColsUi();
        final View rowCols = findViewById(R.id.rowCols);
        focusRow(rowCols);
        rowCols.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = sp.getInt(MainActivity.KEY_COLS, 0);
                int next = (cur == 0) ? 5 : (cur == 5 ? 6 : 0);
                sp.edit().putInt(MainActivity.KEY_COLS, next).apply();
                applyColsUi();
                Toast.makeText(SettingsActivity.this, "已设为" + (next == 0 ? "自动" : next + " 个") + "，返回首页生效", Toast.LENGTH_SHORT).show();
            }
        });

        // ---------- 开机自启动 ----------
        applyBootUi();
        final View rowBoot = findViewById(R.id.rowBoot);
        focusRow(rowBoot);
        rowBoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !sp.getBoolean(MainActivity.KEY_BOOT, true);
                sp.edit().putBoolean(MainActivity.KEY_BOOT, on).apply();
                applyBootUi();
            }
        });

        // ---------- 壁纸：Bing 每日壁纸库 ----------
        applyWallUi();
        final View rowWall = findViewById(R.id.rowWall);
        focusRow(rowWall);
        rowWall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWallpaperDialog();
            }
        });

        // ---------- 天气位置 ----------
        applyWxUi();
        final View rowWx = findViewById(R.id.rowWx);
        focusRow(rowWx);
        rowWx.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWxPicker();
            }
        });

        // ---------- 影视接口：当前接口 → 可滚动选择 ----------
        applySourceUi();
        final View rowSource = findViewById(R.id.rowSource);
        focusRow(rowSource);
        rowSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSourcePicker();
            }
        });

        // ---------- 影视接口按钮（自定义地址 / 更新源 / 检查更新） ----------
        Button btnApply = (Button) findViewById(R.id.btnApply);
        Button btnRefresh = (Button) findViewById(R.id.btnRefresh);
        Button btnCheck = (Button) findViewById(R.id.btnCheck);
        focusBtn(btnApply);
        focusBtn(btnRefresh);
        focusBtn(btnCheck);

        btnApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = etUrl.getText().toString().trim();
                if (url.length() == 0) {
                    Toast.makeText(SettingsActivity.this, "请输入接口地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 自定义接口：写入 KEY_API 并清除直连源标记，返回首页由 onResume 重新加载
                sp.edit().putString(MainActivity.KEY_API, url).remove(MainActivity.KEY_SRC_API).apply();
                Toast.makeText(SettingsActivity.this, "已应用，返回首页生效", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateSources();
            }
        });
        btnCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 让首页检查更新：首页是 singleTask，会在 onNewIntent 收到该指令
                Intent it = new Intent(SettingsActivity.this, MainActivity.class);
                it.putExtra("action", "checkupdate");
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(it);
                finish();
            }
        });
    }

    // ---------- 各设置项的显示刷新 ----------

    private void applyColsUi() {
        int c = sp.getInt(MainActivity.KEY_COLS, 0);
        valCols.setText(c == 0 ? "自动" : (c + " 个"));
    }

    private void applyBootUi() {
        boolean on = sp.getBoolean(MainActivity.KEY_BOOT, true);
        valBoot.setText(on ? "开" : "关");
        valBoot.setBackgroundResource(on ? R.drawable.pill_on : R.drawable.pill_off);
    }

    private void applyWallUi() {
        String u = sp.getString(MainActivity.KEY_WALL, "");
        valWall.setText(u != null && u.length() > 0 ? "已设置" : "系统默认");
    }

    private void applyWxUi() {
        String c = sp.getString(MainActivity.KEY_WX_CITY, "");
        valWx.setText(c != null && c.length() > 0 ? c : "北京");
    }

    private void applySourceUi() {
        String name = sp.getString(MainActivity.KEY_SRC_NAME, "");
        String cfg = sp.getString(MainActivity.KEY_API, "");
        String txt;
        if (name != null && name.length() > 0) txt = name;
        else if (cfg != null && cfg.length() > 0) txt = "自定义接口";
        else txt = "未选择（默认）";
        valSource.setText(txt);
    }

    // ---------- 壁纸：Bing 每日壁纸库（选最近5张 / 刷新下一批 / 自定义地址 / 系统默认） ----------

    /** 主入口：弹出 Bing 每日壁纸库，点一张设为壁纸；右上角「自定义地址」「系统默认」为辅助 */
    private void showWallpaperDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 10, 24, 10);

        // 状态行：说明当前批次（最近 5 张 / 第 6-10 张…），也承载加载失败提示
        bingStatus = new TextView(this);
        bingStatus.setTextColor(0xff9aa0a8);
        bingStatus.setTextSize(14);
        bingStatus.setPadding(0, 0, 0, 8);
        root.addView(bingStatus);

        // 顶部操作行：上一批 / 下一批 / 自定义地址 / 系统默认
        LinearLayout opRow = new LinearLayout(this);
        opRow.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = new Button(this);
        prev.setText("← 上一批");
        Button next = new Button(this);
        next.setText("下一批 →");
        Button custom = new Button(this);
        custom.setText("自定义地址");
        Button def = new Button(this);
        def.setText("系统默认");
        opRow.addView(prev);
        opRow.addView(next);
        opRow.addView(custom);
        opRow.addView(def);
        root.addView(opRow);

        // 缩略图网格：5 张一行，点一张直接设为壁纸
        bingGrid = new GridView(this);
        bingGrid.setNumColumns(5);
        bingGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        bingGrid.setVerticalSpacing(8);
        bingGrid.setHorizontalSpacing(8);
        bingGrid.setPadding(0, 10, 0, 0);
        bingGrid.setLayoutParams(new GridView.LayoutParams(
                GridView.LayoutParams.MATCH_PARENT, 420));
        bingGrid.setFocusable(true);
        bingGrid.setClickable(true);
        bingGrid.setSelector(android.R.drawable.list_selector_background);
        root.addView(bingGrid);

        bingGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= bingItems.length) return;
                String full = bingItems[pos][1];
                if (full == null || full.length() == 0) return;
                sp.edit().putString(MainActivity.KEY_WALL, full).apply();
                applyWallUi();
                Toast.makeText(SettingsActivity.this,
                        "已设为壁纸（Bing 每日壁纸），返回首页生效", Toast.LENGTH_SHORT).show();
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("壁纸 · Bing 每日壁纸库（点一张应用）");
        b.setView(root);
        b.setNegativeButton("关闭", null);
        b.show();

        // 事件绑定（放 show 之后，避免未初始化）
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (bingIdx - 5 >= 0) fetchBing(bingIdx - 5);
                else Toast.makeText(SettingsActivity.this, "已是最新一批", Toast.LENGTH_SHORT).show();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchBing(bingIdx + 5); }
        });
        custom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCustomWallpaper(); }
        });
        def.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sp.edit().putString(MainActivity.KEY_WALL, "").apply();
                applyWallUi();
                Toast.makeText(SettingsActivity.this, "已恢复系统默认渐变", Toast.LENGTH_SHORT).show();
            }
        });

        // 初始加载最近 5 张
        fetchBing(0);
    }

    /** 拉取 Bing 指定批次（idx=0 最新，每批 5 张），解析为 [title, fullUrl] 并刷新网格 */
    private void fetchBing(final int idx) {
        bingStatus.setText("加载中…");
        new AsyncTask<Void, Void, String[][]>() {
            @Override
            protected String[][] doInBackground(Void... v) {
                try {
                    String url = String.format(BING_API, idx);
                    String json = Net.getText(url, null);
                    if (json == null || json.trim().length() == 0) return null;
                    JSONObject o = new JSONObject(json.trim());
                    JSONArray arr = o.getJSONArray("images");
                    String[][] out = new String[arr.length()][2];
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject im = arr.getJSONObject(i);
                        String urlbase = im.optString("urlbase", "");
                        String full = "https://www.bing.com" + urlbase + "_1920x1080.jpg";
                        out[i][0] = im.optString("title", "");
                        out[i][1] = full;
                    }
                    return out;
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String[][] r) {
                if (r != null && r.length > 0) {
                    bingItems = r;
                    bingIdx = idx;
                    bingStatus.setText(idx == 0
                            ? "最近 5 张（点一张应用）"
                            : "第 " + (idx + 1) + "-" + (idx + r.length) + " 张（点一张应用）");
                    bingGrid.setAdapter(new BingAdapter());
                } else {
                    bingStatus.setText("加载失败，请检查网络后重试");
                }
            }
        }.execute();
    }

    /** 缩略图适配器：复用 ImageLoader 异步加载，省内存 */
    private class BingAdapter extends BaseAdapter {
        @Override public int getCount() { return bingItems.length; }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            ImageView iv;
            if (cv instanceof ImageView) {
                iv = (ImageView) cv;
            } else {
                iv = new ImageView(SettingsActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xff222222);
            }
            iv.setLayoutParams(new GridView.LayoutParams(300, 170));
            iv.setPadding(2, 2, 2, 2);
            ImageLoader.load(bingItems[pos][1], iv, 320);
            return iv;
        }
    }

    // ---------- 壁纸：自定义地址（原输入对话框，作为 Bing 库的补充） ----------

    private void showCustomWallpaper() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("https://.../bg.jpg");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(sp.getString(MainActivity.KEY_WALL, ""));
        et.setTextColor(0xffffffff);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("自定义壁纸（填图片地址；留空=系统渐变）");
        b.setView(et);
        b.setPositiveButton("应用", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                String u = et.getText().toString().trim();
                sp.edit().putString(MainActivity.KEY_WALL, u).apply();
                applyWallUi();
                Toast.makeText(SettingsActivity.this,
                        u.length() > 0 ? "已设置壁纸，返回首页生效" : "已恢复系统默认渐变",
                        Toast.LENGTH_SHORT).show();
            }
        });
        b.setNeutralButton("系统默认", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                sp.edit().putString(MainActivity.KEY_WALL, "").apply();
                applyWallUi();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ---------- 天气位置：点按弹出可滚动城市列表（与主页右上角天气一致） ----------

    private void showWxPicker() {
        final String[] cities = MainActivity.WX_CITIES;
        final double[] lats = MainActivity.WX_LATS;
        final double[] lons = MainActivity.WX_LONS;
        String now = sp.getString(MainActivity.KEY_WX_CITY, "");
        int cur = -1;
        for (int i = 0; i < cities.length; i++) {
            if (cities[i].equals(now)) { cur = i; break; }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择天气城市（当前：" + (now.length() > 0 ? now : "北京") + "）");
        b.setSingleChoiceItems(cities, cur, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                sp.edit().putString(MainActivity.KEY_WX_CITY, cities[w])
                        .putFloat(MainActivity.KEY_WX_LAT, (float) lats[w])
                        .putFloat(MainActivity.KEY_WX_LON, (float) lons[w])
                        .putString("wx_str", "").apply();   // 清主页天气缓存，返回即刷新
                applyWxUi();
                d.dismiss();
                Toast.makeText(SettingsActivity.this,
                        "天气城市已设为：" + cities[w] + "，返回首页生效", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ---------- 影视接口：可滚动选择弹窗（替代原页内长列表） ----------

    private void showSourcePicker() {
        MainActivity.loadSavedDirectSites(sp);
        presets = MainActivity.DIRECT_SITES;
        String[] names = new String[presets.length];
        for (int i = 0; i < presets.length; i++) names[i] = "[直连·可直接播] " + presets[i][0];
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择影视接口（共 " + presets.length + " 个，可滚动）");
        b.setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int pos) {
                if (pos < 0 || pos >= presets.length) return;
                sp.edit()
                        .putString(MainActivity.KEY_SRC_NAME, presets[pos][0])
                        .putString(MainActivity.KEY_SRC_API, presets[pos][1])
                        .apply();
                applySourceUi();
                Toast.makeText(SettingsActivity.this, "已选择：" + presets[pos][0] + "，返回首页生效", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ---------- 从远程 sources.json 更新直连源列表 ----------

    private void updateSources() {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在更新直连源列表…");
        pd.setCancelable(false);
        pd.show();
        new AsyncTask<Void, Void, String>() {
            private String[][] parsed;
            private String raw;

            @Override
            protected String doInBackground(Void... v) {
                try {
                    raw = Net.getText(MainActivity.SOURCES_URL, null);
                    if (raw == null || raw.trim().length() == 0) return "empty";
                    parsed = MainActivity.parseSitesJson(raw);
                    if (parsed == null || parsed.length == 0) return "bad";
                    return "ok";
                } catch (Throwable t) {
                    return "err:" + ((t.getMessage() != null) ? t.getMessage() : t.getClass().getSimpleName());
                }
            }

            @Override
            protected void onPostExecute(String r) {
                try {
                    pd.dismiss();
                } catch (Throwable ignored) {
                }
                if ("ok".equals(r)) {
                    MainActivity.DIRECT_SITES = parsed;
                    sp.edit().putString(MainActivity.KEY_SITES_JSON, raw.trim()).apply();
                    Toast.makeText(SettingsActivity.this,
                            "直连源已更新：共 " + parsed.length + " 个", Toast.LENGTH_LONG).show();
                } else if ("empty".equals(r)) {
                    Toast.makeText(SettingsActivity.this,
                            "远程源列表为空，保留当前 " + presets.length + " 个", Toast.LENGTH_LONG).show();
                } else if ("bad".equals(r)) {
                    Toast.makeText(SettingsActivity.this,
                            "远程源列表格式不正确，保留当前 " + presets.length + " 个", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(SettingsActivity.this,
                            "更新失败：" + r.substring(4), Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    // ---------- 焦点高亮（复用按钮焦点态） ----------

    private void focusRow(View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackgroundResource(has ? R.drawable.btn_dark_focused : R.drawable.btn_dark);
            }
        });
    }

    private void focusBtn(Button b) {
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackgroundResource(has ? R.drawable.btn_dark_focused : R.drawable.btn_dark);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        // 菜单键直接退出设置页（与首页行为一致）
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
