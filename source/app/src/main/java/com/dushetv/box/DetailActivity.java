package com.dushetv.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DetailActivity extends Activity {
    private SiteApi api;
    private String apiUrl;
    private String vodId, vodName, pic;
    private JSONObject vod;
    private String vodJson;
    private PlayData pd;
    private int curSrc = 0;
    private int epFocusPos = 0;   // 遥控器当前停在哪集（选中项），驱动焦点态
    private String vodKey = "";

    private ImageView poster;
    private TextView title, remarks, desc;
    private LinearLayout srcRow;
    private GridView eps;
    private TextView favBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        Intent in = getIntent();
        apiUrl = in.getStringExtra("api");
        int type = in.getIntExtra("type", 1);
        vodId = in.getStringExtra("vodId");
        vodName = in.getStringExtra("vodName");
        pic = in.getStringExtra("pic");
        vodJson = in.getStringExtra("vodJson");
        vodKey = (apiUrl != null ? apiUrl : "") + ":" + (vodId != null ? vodId : "");

        Site s = new Site();
        s.api = apiUrl;
        s.type = type;
        api = new SiteApi(s);

        poster = (ImageView) findViewById(R.id.poster);
        title = (TextView) findViewById(R.id.title);
        remarks = (TextView) findViewById(R.id.remarks);
        desc = (TextView) findViewById(R.id.desc);
        srcRow = (LinearLayout) findViewById(R.id.srcs);
        eps = (GridView) findViewById(R.id.eps);
        favBtn = (TextView) findViewById(R.id.fav);
        TextView backBtn = (TextView) findViewById(R.id.back);
        if (backBtn != null) {
            backBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onBackPressed(); }
            });
        }

        title.setText(vodName != null ? vodName : "");
        // 海报按比例 3:4 显示（宽度由左侧区块决定，布局完成后取宽度算高度）
        poster.post(new Runnable() {
            @Override public void run() { applyPosterRatio(); }
        });
        if (pic != null && pic.length() > 0) ImageLoader.load(pic, poster, 240);

        // 收藏按钮：根据当前是否已收藏切换文案，点按切换状态
        updateFavBtn();
        favBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FavStore.Fav f = currentFav();
                boolean added = FavStore.toggle(DetailActivity.this, f);
                updateFavBtn();
                Toast.makeText(DetailActivity.this,
                        added ? "已加入收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
            }
        });
        // 收藏按钮焦点态：亮蓝圆角边 + 1.1 放大（与全局按钮统一）
        focusZoom(favBtn);

        eps.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pd == null || curSrc >= pd.episodes.size()) return;
                List<Episode> list = pd.episodes.get(curSrc);
                if (pos < 0 || pos >= list.size()) return;
                Episode e = list.get(pos);
                Intent pi = new Intent(DetailActivity.this, PlayerActivity.class);
                pi.putExtra("url", e.url);
                pi.putExtra("name", (vodName != null ? vodName + " " : "") + e.name);
                pi.putExtra("referer", apiUrl);
                // 把当前线路的整集列表一并交给播放器，支持「上一集/下一集」
                pi.putExtra("epsJson", episodesToJson(list));
                pi.putExtra("epIndex", pos);
                pi.putExtra("vodKey", vodKey);
                pi.putExtra("curSrc", curSrc);
                // 影片信息：播放器据此自动写入「观看记录」（收藏页上半区）
                pi.putExtra("vodId", vodId);
                pi.putExtra("vodName", vodName);
                pi.putExtra("pic", pic);
                pi.putExtra("api", apiUrl);
                pi.putExtra("type", getIntent().getIntExtra("type", 1));
                pi.putExtra("siteName", getIntent().getStringExtra("siteName"));
                startActivity(pi);
            }
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放器返回后，刷新选集着色（当前集/已看可能已变化）
        if (pd != null && !pd.sources.isEmpty()) {
            showSrc(curSrc);
        }
    }

    private void load() {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... v) {
                try {
                    // 优先使用搜索/列表结果自带的原始 JSON（已含播放地址），跳过易错位的二次请求
                    if (vodJson != null && vodJson.length() > 0) {
                        try { vod = new JSONObject(vodJson); } catch (Throwable ignored) { vod = null; }
                    }
                    // 若原始数据没有播放地址，再降级用 id 重新拉详情
                    if (vod != null) {
                        try {
                            if (SiteApi.parsePlayData(vod).sources.isEmpty()) vod = null;
                        } catch (Throwable ignored) { vod = null; }
                    }
                    if (vod == null) {
                        vod = api.detail(vodId);
                    }
                    return null;
                } catch (Throwable t) {
                    return t.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String err) {
                if (err != null) {
                    Toast.makeText(DetailActivity.this, "详情加载失败：" + err, Toast.LENGTH_LONG).show();
                    return;
                }
                if (vod == null) return;
                String nm = vod.optString("vod_name", vodName);
                String rm = vod.optString("vod_remarks", "");
                String dc = vod.optString("vod_content", vod.optString("vod_blurb", ""));
                String pc = vod.optString("vod_pic", pic);
                title.setText(nm);
                remarks.setText(rm);
                desc.setText(dc);
                if (pc != null && pc.length() > 0) ImageLoader.load(pc, poster, 240);
                try {
                    pd = SiteApi.parsePlayData(vod);
                } catch (Throwable t) {
                    Toast.makeText(DetailActivity.this, "解析播放地址失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                if (pd.sources.isEmpty()) {
                    Toast.makeText(DetailActivity.this, "该影片暂无播放地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                buildSrcs();
                // 优先默认选中包含 m3u8 的线路（feifan 等直连源常提示下载无法播放）
                int defSrc = 0;
                for (int i = 0; i < pd.sources.size(); i++) {
                    if (pd.sources.get(i).toLowerCase().contains("m3u8")) {
                        defSrc = i;
                        break;
                    }
                }
                showSrc(defSrc);
            }
        }.execute();
    }

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

    private void buildSrcs() {
        srcRow.removeAllViews();
        for (int i = 0; i < pd.sources.size(); i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(pd.sources.get(i));
            t.setTextSize(17);
            t.setPadding(20, 12, 20, 12);
            t.setFocusable(true);
            t.setClickable(true);
            t.setTextColor(0xffe8e8e8);
            t.setBackgroundResource(R.drawable.btn_dark);
            focusZoom(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSrc(idx);
                }
            });
            // 源按钮之间留统一间距（16dp），不再紧贴
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.setMargins(0, 0, 16, 0);
            t.setLayoutParams(slp);
            srcRow.addView(t);
        }
    }

    private void showSrc(int idx) {
        curSrc = idx;
        List<Episode> list = pd.episodes.get(idx);
        final ArrayList<Episode> data = new ArrayList<Episode>(list);
        eps.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int p) {
                return data.get(p);
            }

            @Override
            public long getItemId(int p) {
                return p;
            }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_ep, null);
                // 禁用默认焦点缩放动画，保证「手动 1.1 放大」在真机 TV 上不被系统压小/卡死
                if (android.os.Build.VERSION.SDK_INT >= 21) cv.setStateListAnimator(null);
                final TextView tv = (TextView) cv.findViewById(R.id.ep);
                tv.setText(data.get(p).name);
                // 焦点态由 epFocusPos 统一驱动（复用安全，不会丢焦点色）
                int cur = WatchStore.getCurrentEp(DetailActivity.this, vodKey, curSrc);
                if (cur < 0) cur = 0;
                boolean watched = WatchStore.isWatched(DetailActivity.this, vodKey, curSrc, p);
                if (p == epFocusPos) {
                    // 焦点态：亮蓝圆角边 + 1.1 放大（与全局按钮统一）
                    tv.setBackgroundResource(R.drawable.btn_dark_focused);
                    tv.setTextColor(0xffffffff);
                    tv.setScaleX(1.1f);
                    tv.setScaleY(1.1f);
                } else {
                    tv.setScaleX(1f);
                    tv.setScaleY(1f);
                    // 默认黑边圆角；状态用文字色区分：当前集=绿、看过=灰、未看=黄
                    tv.setBackgroundResource(R.drawable.btn_dark);
                    if (p == cur) {
                        tv.setTextColor(0xff66ff99);
                    } else if (watched) {
                        tv.setTextColor(0xff888888);
                    } else {
                        tv.setTextColor(0xffFFD54F);
                    }
                }
                return cv;
            }
        });
        // TV 遥控器导航标准回调：选中项变化即刷新焦点态，比 OnFocusChangeListener 在 GridView 复用场景可靠
        eps.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                epFocusPos = position;
                ((BaseAdapter) eps.getAdapter()).notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        epFocusPos = 0;
        eps.setSelection(0);
    }

    // ---------- 收藏 ----------

    private FavStore.Fav currentFav() {
        FavStore.Fav f = new FavStore.Fav();
        f.key = (apiUrl != null ? apiUrl : "") + ":" + (vodId != null ? vodId : "");
        f.id = vodId;
        f.name = (vodName != null ? vodName : "");
        f.pic = (pic != null ? pic : "");
        f.api = apiUrl;
        int type = 1;
        try { type = getIntent().getIntExtra("type", 1); } catch (Throwable ignored) {}
        f.type = type;
        f.siteName = getIntent().getStringExtra("siteName");
        return f;
    }

    /** 详情页海报按 3:4 竖向比例：高度 = 宽度 × 4/3，centerCrop 不变形 */
    private void applyPosterRatio() {
        if (poster == null) return;
        int w = poster.getWidth();
        if (w <= 0) return;
        int h = (int) (w * 4f / 3f);
        ViewGroup.LayoutParams lp = poster.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(w, h);
        lp.width = w;
        lp.height = h;
        poster.setLayoutParams(lp);
    }

    private void updateFavBtn() {
        if (favBtn == null) return;
        boolean fav = FavStore.isFav(this, currentFav().key);
        favBtn.setText(fav ? "★ 已收藏" : "☆ 收藏");
    }

    /** 把一集列表序列化成 JSON 数组字符串，交给播放器用于上下集切换 */
    private static String episodesToJson(List<Episode> list) {
        try {
            JSONArray a = new JSONArray();
            for (Episode e : list) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("url", e.url);
                a.put(o);
            }
            return a.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }
}
