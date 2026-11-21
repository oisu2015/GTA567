package com.dushetv.box;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用「更多」分页页：从搜索页的 源分类/热搜榜/搜索历史 的「更多」进入，
 * 展示该分类下的全部条目（可滚动分页），点按执行对应动作。
 * action = "search" 时直接搜索该词；action = "cat" 时按 tid 浏览该分类。
 */
public class MoreActivity extends Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_ACTION = "action"; // "search" | "cat"
    public static final String EXTRA_TIDS = "tids";     // 仅 cat 需要

    private String action;
    private String[] tids;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.installGlobalTls();
        setContentView(R.layout.activity_more);

        // 顶部「← 返回」：模拟器等无遥控器返回键也能点屏返回
        android.widget.TextView backBtn = (android.widget.TextView) findViewById(R.id.back);
        if (backBtn != null) backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        TextView title = (TextView) findViewById(R.id.moreTitle);
        GridView grid = (GridView) findViewById(R.id.moreGrid);

        String t = getIntent().getStringExtra(EXTRA_TITLE);
        final String[] items = getIntent().getStringArrayExtra(EXTRA_ITEMS);
        action = getIntent().getStringExtra(EXTRA_ACTION);
        tids = getIntent().getStringArrayExtra(EXTRA_TIDS);
        if (t != null) title.setText(t);

        final List<String> list = new ArrayList<String>();
        if (items != null) for (String s : items) list.add(s);

        grid.setAdapter(new android.widget.ArrayAdapter<String>(
                this, R.layout.item_chip, R.id.chipText, list));
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, android.view.View v, int pos, long id) {
                if (pos < 0 || pos >= list.size()) return;
                String item = list.get(pos);
                if ("cat".equals(action)) {
                    String tid = (tids != null && pos < tids.length) ? tids[pos] : "";
                    browseCat(tid, item);
                } else {
                    submit(item);
                }
            }
        });

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        grid.post(new Runnable() {
            @Override public void run() {
                if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
            }
        });
    }

    private void submit(String kw) {
        if (kw == null) kw = "";
        kw = kw.trim();
        if (kw.length() == 0) return;
        Intent in = new Intent(this, MainActivity.class);
        in.putExtra("searchKw", kw);
        in.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(in);
        finish();
    }

    private void browseCat(String tid, String name) {
        Intent in = new Intent(this, MainActivity.class);
        in.putExtra("catTid", tid);
        in.putExtra("catName", name == null ? "" : name);
        in.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(in);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
