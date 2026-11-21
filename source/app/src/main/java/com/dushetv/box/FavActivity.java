package com.dushetv.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏页 v2：上下两个区块。
 * 上半区「观看记录」：播放过的影片自动加入（FavStore.addAuto），卡片显示「看到第X集」；
 * 下半区「我的收藏」：手动收藏的影片。
 * 点按进详情；长按可移除（观看记录=清除记录，收藏=取消收藏）。
 * 列数自适应：720 电视一排 5 个，1080 及以上一排 6 个。
 */
public class FavActivity extends Activity {

    private GridView watchGrid, favGrid;
    private TextView watchEmpty, favEmpty;
    private List<FavStore.Fav> watchItems = new ArrayList<FavStore.Fav>();
    private List<FavStore.Fav> favItems = new ArrayList<FavStore.Fav>();
    private FavAdapter watchAdapter, favAdapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.installGlobalTls();
        setContentView(R.layout.activity_fav);

        // 顶部「← 返回」：模拟器等无遥控器返回键也能点屏返回
        android.widget.TextView backBtn = (android.widget.TextView) findViewById(R.id.back);
        if (backBtn != null) backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        watchGrid = (GridView) findViewById(R.id.watchGrid);
        favGrid = (GridView) findViewById(R.id.favGrid);
        watchEmpty = (TextView) findViewById(R.id.watchEmpty);
        favEmpty = (TextView) findViewById(R.id.favEmpty);

        // 列数自适应分辨率：720 一排 5 个、1080 一排 6 个
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int cols = (dm.widthPixels <= 1280) ? 5 : 6;
        watchGrid.setNumColumns(cols);
        favGrid.setNumColumns(cols);

        watchAdapter = new FavAdapter(watchItems);
        favAdapter = new FavAdapter(favItems);
        watchGrid.setAdapter(watchAdapter);
        favGrid.setAdapter(favAdapter);

        setupGrid(watchGrid, watchItems, "清除观看记录", "确定清除《", "》的观看记录？");
        setupGrid(favGrid, favItems, "取消收藏", "确定把《", "》从收藏中移除？");
    }

    /** 两个网格共用的点按/长按/焦点放大逻辑 */
    private void setupGrid(final GridView grid, final List<FavStore.Fav> items,
                           final String actTitle, final String msgPre, final String msgPost) {
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                openDetail(items.get(pos));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                if (pos < 0 || pos >= items.size()) return true;
                final FavStore.Fav f = items.get(pos);
                new AlertDialog.Builder(FavActivity.this)
                        .setTitle(actTitle)
                        .setMessage(msgPre + f.name + msgPost)
                        .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                FavStore.removeFav(FavActivity.this, f.key);
                                refresh();
                                Toast.makeText(FavActivity.this, "已移除", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("保留", null)
                        .show();
                return true;
            }
        });
        // 焦点卡片放大 + 金黄粗边（card_bg_focused），与首页一致；选中时显示完整片名与完整图片
        final View[] prev = {null};
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (prev[0] != null) resetCard(prev[0]);
                if (v != null) {
                    v.setScaleX(1.12f);
                    v.setScaleY(1.12f);
                    v.setBackgroundResource(R.drawable.card_bg_focused);
                    // 用 Z 轴 elevation 置顶绘制：仅改层叠顺序、不改 children 索引，
                    // 不会破坏 GridView 两维焦点导航；金边不再被相邻卡片遮挡
                    if (android.os.Build.VERSION.SDK_INT >= 21) v.setElevation(8f);
                    TextView nm = (TextView) v.findViewById(R.id.name);
                    if (nm != null) { nm.setSingleLine(false); nm.setMaxLines(2); nm.setEllipsize(null); }
                    ImageView pc = (ImageView) v.findViewById(R.id.pic);
                    if (pc != null) pc.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    prev[0] = v;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        // 焦点离开网格时复位残留选中卡片
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && prev[0] != null) { resetCard(prev[0]); prev[0] = null; }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<FavStore.Fav> all = FavStore.getFavs(this);
        watchItems.clear();
        favItems.clear();
        for (FavStore.Fav f : all) {
            if (f.auto) watchItems.add(f);
            else favItems.add(f);
        }
        watchAdapter.notifyDataSetChanged();
        favAdapter.notifyDataSetChanged();
        watchGrid.setVisibility(watchItems.isEmpty() ? View.GONE : View.VISIBLE);
        watchEmpty.setVisibility(watchItems.isEmpty() ? View.VISIBLE : View.GONE);
        favGrid.setVisibility(favItems.isEmpty() ? View.GONE : View.VISIBLE);
        favEmpty.setVisibility(favItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openDetail(FavStore.Fav f) {
        Intent in = new Intent(this, DetailActivity.class);
        in.putExtra("api", f.api);
        in.putExtra("type", f.type);
        in.putExtra("siteName", f.siteName);
        in.putExtra("vodId", f.id);
        in.putExtra("vodName", f.name);
        in.putExtra("pic", f.pic);
        startActivity(in);
    }

    /** 影视图高度：列宽 × 4/3（3:4 竖向海报），720 与 1080 下保持一致比例 */
    private int posterHeight(GridView g) {
        int cols = g.getNumColumns();
        if (cols <= 0) cols = (getResources().getDisplayMetrics().widthPixels <= 1280) ? 5 : 6;
        int gw = g.getWidth();
        if (gw <= 0) gw = getResources().getDisplayMetrics().widthPixels;
        int pad = g.getPaddingLeft() + g.getPaddingRight();
        int hsp = Math.max(0, cols - 1) * g.getHorizontalSpacing();
        int colW = (gw - pad - hsp) / cols;
        if (colW <= 0) colW = 120;
        return (int) (colW * 4f / 3f);
    }

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

    /** 通用卡片适配器：观看记录显示「看到第X集」，收藏显示站点名 */
    private class FavAdapter extends BaseAdapter {
        private List<FavStore.Fav> items;

        FavAdapter(List<FavStore.Fav> src) {
            items = src;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int p) {
            return items.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            try {
                if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_grid, null);
                // 禁用默认焦点缩放动画，并用 elevation=0 复位层叠（保证「手动放大」与金边严格同步，
                // 且每次复用都从默认层叠开始，只有选中项才会被临时抬到 Z 轴顶层）
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    cv.setStateListAnimator(null);
                    cv.setElevation(0f);
                }
                FavStore.Fav f = items.get(p);
                ImageView pic = (ImageView) cv.findViewById(R.id.pic);
                TextView name = (TextView) cv.findViewById(R.id.name);
                TextView remark = (TextView) cv.findViewById(R.id.remark);
                name.setText(f.name);
                if (f.auto) {
                    // 观看记录：显示看到第几集（WatchStore 记录的当前集）
                    int ep = WatchStore.getCurrentEpAny(FavActivity.this, f.key);
                    remark.setText(ep >= 0 ? "看到第" + (ep + 1) + "集" : "已观看");
                    remark.setTextColor(0xffFFD54F);
                } else {
                    remark.setText(f.siteName == null ? "" : f.siteName);
                    remark.setTextColor(0xffaaaacc);
                }
                // 复位「未选中」默认态（防止复用聚焦视图残留2行名/完整图）
                name.setSingleLine(true);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                // 影视图统一 3:4 竖向比例：高度 = 列宽 × 4/3（与首页一致）
                GridView g = (parent == watchGrid) ? watchGrid : favGrid;
                int ph = posterHeight(g);
                ViewGroup.LayoutParams plp = pic.getLayoutParams();
                if (plp == null) plp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph);
                plp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                plp.height = ph;
                pic.setLayoutParams(plp);
                ImageLoader.load(f.pic, pic, 200);
                return cv;
            } catch (Throwable t) {
                TextView ph = new TextView(FavActivity.this);
                ph.setHeight(180);
                return ph;
            }
        }
    }
}
