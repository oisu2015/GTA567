package com.dushetv.box;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 独立搜索页：统一布局（720 / 1080 同一套）。
 * 顶部：搜索框整行 100% 宽 + 中文/英文切换 + 搜索按钮；
 * 下部左右分栏：左列=搜索历史（两行）+ 热搜榜，右列=中文拼音键盘 + 输入键盘。
 * <p>
 * 中文模式 = 拼音键盘：在字母键盘上拼拼音，下方候选条实时出现对应汉字，
 * 选中（点击/确认）即填入搜索框，再继续拼下一个字。拼音字典离线打包在
 * assets/pinyin.json（GB2312 全量汉字 + 高频字优先）。
 * 所有可点区域均用嵌套 LinearLayout 的 chip 行，保证电视遥控器方向键导航可预期。
 */
public class SearchActivity extends Activity {

    private static final String PREFS = "box";
    private static final String HIST_KEY = "search_history";
    /** 每页显示条数（历史/热搜 均取前 8，超出用「更多」开新页） */
    private static final int PAGE = 8;
    /** 内置热搜词（固定运营列表，覆盖源的随机热搜） */
    private static final String[] HOT = {
            "团长", "潜伏", "渗透", "亮剑", "面具", "鬼怪", "铁拳教育",
            "绝命毒师", "越狱", "疑犯追踪", "行尸走肉", "纸钞屋"
    };

    private EditText box;
    private LinearLayout keyboard;        // 英文键盘
    private LinearLayout cnPanel;         // 中文拼音键盘容器
    private LinearLayout cnKeyboard;      // 中文拼音字母键盘
    private TextView pyText;             // 拼音显示
    private LinearLayout candRow;         // 候选汉字条
    private LinearLayout histRow1, histRow2, hotChips;
    private TextView moreHist, moreHot, langToggle;
    private String apiUrl;

    private boolean isChinese = true;
    private List<String> allHot = new ArrayList<String>();
    private List<String> allHist = new ArrayList<String>();

    // 键盘尺寸参数：按钮字体统一加大（电视远距离可读），圆角背景
    private int KEY_TEXT = 22;     // 字母/数字键字号
    private int KEY_PAD_H = 22;    // 键左右内边距
    private int KEY_PAD_V = 16;    // 键上下内边距
    private int KEY_MARGIN = 6;    // 键水平间距
    private int ROW_PAD_V = 6;     // 每行键盘上下内边距
    private int CAND_TEXT = 26;    // 候选汉字字号
    private int CAND_PAD_H = 22;
    private int CAND_PAD_V = 9;
    private int CAND_MARGIN = 6;

    /** 拼音 -> 候选汉字列表（离线字典） */
    private Map<String, List<String>> pyMap = new HashMap<String, List<String>>();
    /** 当前正在拼的拼音 */
    private StringBuilder curPinyin = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Net.installGlobalTls();
        setContentView(R.layout.activity_search);

        // 顶部「← 返回」：模拟器等无遥控器返回键也能点屏返回
        android.widget.TextView backBtn = (android.widget.TextView) findViewById(R.id.back);
        if (backBtn != null) backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        box = (EditText) findViewById(R.id.box);
        keyboard = (LinearLayout) findViewById(R.id.keyboard);
        cnPanel = (LinearLayout) findViewById(R.id.cnPanel);
        cnKeyboard = (LinearLayout) findViewById(R.id.cnKeyboard);
        pyText = (TextView) findViewById(R.id.pyText);
        candRow = (LinearLayout) findViewById(R.id.candRow);
        histRow1 = (LinearLayout) findViewById(R.id.histRow1);
        histRow2 = (LinearLayout) findViewById(R.id.histRow2);
        hotChips = (LinearLayout) findViewById(R.id.hotChips);
        moreHist = (TextView) findViewById(R.id.moreHist);
        moreHot = (TextView) findViewById(R.id.moreHot);
        langToggle = (TextView) findViewById(R.id.langToggle);

        // 禁用各容器裁剪，保证按钮在焦点放大(1.1)时不被行/容器边界切掉
        keyboard.setClipChildren(false);
        cnKeyboard.setClipChildren(false);
        candRow.setClipChildren(false);
        hotChips.setClipChildren(false);
        histRow1.setClipChildren(false);
        histRow2.setClipChildren(false);

        apiUrl = getIntent().getStringExtra("apiUrl");

        TextView go = (TextView) findViewById(R.id.go);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit(box.getText().toString().trim());
            }
        });
        focusZoom(go);

        langToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleChinese(); }
        });
        focusZoom(langToggle);

        for (String kw : HOT) allHot.add(kw);
        allHist = getHistory();

        loadPinyinDict();
        buildKeyboard();
        buildCnKeyboard();
        buildHistoryChips();
        buildHotChips();
        loadHotWordsAsync();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (box != null) box.clearFocus();
        // 统一布局（720 / 1080 同一套）：顶部搜索框整行 + 左下历史/热搜 + 右下键盘
        keyboard.post(new Runnable() {
            @Override public void run() { applyLangState(); }
        });
    }

    // ---------------- 中文/英文 切换 ----------------

    /** 统一根据 isChinese 同步键盘可见性、切换键文字与初始焦点 */
    private void applyLangState() {
        keyboard.setVisibility(isChinese ? View.GONE : View.VISIBLE);
        cnPanel.setVisibility(isChinese ? View.VISIBLE : View.GONE);
        langToggle.setText(isChinese ? "英文" : "中文");
        if (isChinese) {
            if (curPinyin.length() == 0) focusCnKey();
            else refreshCands();
        } else {
            focusEnKey();
        }
    }

    private void toggleChinese() {
        isChinese = !isChinese;
        applyLangState();
    }

    // ---------------- 拼音字典（离线） ----------------

    private void loadPinyinDict() {
        try {
            InputStream is = getAssets().open("pinyin.json");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "utf-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            is.close();
            JSONObject obj = new JSONObject(sb.toString());
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = obj.getJSONArray(k);
                List<String> l = new ArrayList<String>();
                for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
                pyMap.put(k, l);
            }
        } catch (Throwable t) {
            // 字典缺失：中文键盘无候选，仍可输入英文拼音作搜索词
        }
    }

    // ---------------- 通用 chip ----------------

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

    private TextView makeChip(String text, View.OnClickListener action, boolean fullWidth) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xffffffff);
        t.setBackgroundResource(R.drawable.btn_dark);
        t.setPadding(18, 12, 18, 12);
        t.setFocusable(true);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                fullWidth ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (fullWidth) lp.setMargins(0, 0, 0, 8);
        else lp.setMargins(0, 0, 16, 0);
        t.setLayoutParams(lp);
        focusZoom(t);
        t.setOnClickListener(action);
        return t;
    }

    /** 候选汉字 chip（淡黄强调，选中即填入搜索框） */
    private TextView makeCandChip(final String ch) {
        TextView t = new TextView(this);
        t.setText(ch);
        t.setTextSize(CAND_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xffffffff);
        t.setBackgroundResource(R.drawable.btn_dark);
        t.setPadding(CAND_PAD_H, CAND_PAD_V, CAND_PAD_H, CAND_PAD_V);
        t.setFocusable(true);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(CAND_MARGIN, 0, CAND_MARGIN, 0);
        t.setLayoutParams(lp);
        focusZoom(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickCand(ch); }
        });
        return t;
    }

    // ---------------- 中文拼音键盘 ----------------

    private void buildCnKeyboard() {
        String[][] rows = {
                {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
                {"A", "S", "D", "F", "G", "H", "J", "K", "L"},
                {"Z", "X", "C", "V", "B", "N", "M"},
                {"退格", "清空", "搜索"}
        };
        for (String[] row : rows) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setGravity(Gravity.CENTER);
            rl.setPadding(0, ROW_PAD_V, 0, ROW_PAD_V);
            rl.setClipChildren(false);
            for (final String k : row) {
                rl.addView(makeCnKey(k));
            }
            cnKeyboard.addView(rl);
        }
    }

    private TextView makeCnKey(final String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(KEY_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xffffffff);
        t.setBackgroundResource(R.drawable.btn_dark);
        t.setPadding(KEY_PAD_H, KEY_PAD_V, KEY_PAD_H, KEY_PAD_V);
        t.setFocusable(true);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(KEY_MARGIN, 0, KEY_MARGIN, 0);
        t.setLayoutParams(lp);
        focusZoom(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onCnKey(label); }
        });
        return t;
    }

    private void onCnKey(String label) {
        if ("退格".equals(label)) delPinyin();
        else if ("清空".equals(label)) clearPinyin();
        else if ("搜索".equals(label)) submit(box.getText().toString().trim());
        else appendPinyin(label);
    }

    private void appendPinyin(String s) {
        curPinyin.append(s.toLowerCase());
        refreshCands();
    }

    private void delPinyin() {
        if (curPinyin.length() > 0) {
            curPinyin.setLength(curPinyin.length() - 1);
            refreshCands();
        }
    }

    private void clearPinyin() {
        curPinyin.setLength(0);
        pyText.setText("拼音：");
        candRow.removeAllViews();
    }

    /** 依据当前拼音刷新候选条 */
    private void refreshCands() {
        String py = curPinyin.toString();
        pyText.setText("拼音：" + py);
        candRow.removeAllViews();
        List<String> cands = (py.length() > 0) ? pyMap.get(py) : null;
        if (cands != null && !cands.isEmpty()) {
            for (String ch : cands) candRow.addView(makeCandChip(ch));
            // 自动焦点到首个候选，按确定即选
            candRow.getChildAt(0).requestFocus();
        } else {
            if (py.length() > 0) {
                TextView hint = new TextView(this);
                hint.setText("「" + py + "」继续输入拼音…");
                hint.setTextColor(0xff888888);
                hint.setTextSize(16);
                hint.setPadding(10, 8, 10, 8);
                candRow.addView(hint);
            }
            focusCnKey();
        }
    }

    /** 选中候选汉字：填入搜索框，清空拼音，焦点回键盘拼下一字 */
    private void pickCand(String ch) {
        append(ch);
        clearPinyin();
        focusCnKey();
    }

    private void focusCnKey() {
        if (cnKeyboard.getChildCount() > 0) {
            LinearLayout row0 = (LinearLayout) cnKeyboard.getChildAt(0);
            if (row0.getChildCount() > 0) row0.getChildAt(0).requestFocus();
        }
    }

    // ---------------- 英文键盘 ----------------

    private void buildKeyboard() {
        String[][] rows = {
                {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
                {"A", "S", "D", "F", "G", "H", "J", "K", "L"},
                {"Z", "X", "C", "V", "B", "N", "M"},
                {"空格", "删除", "清空", "搜索"}
        };
        for (String[] row : rows) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setGravity(Gravity.CENTER);
            rl.setPadding(0, ROW_PAD_V, 0, ROW_PAD_V);
            rl.setClipChildren(false);
            for (final String k : row) {
                rl.addView(makeKey(k));
            }
            keyboard.addView(rl);
        }
    }

    private TextView makeKey(final String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(KEY_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xffffffff);
        t.setBackgroundResource(R.drawable.btn_dark);
        t.setPadding(KEY_PAD_H, KEY_PAD_V, KEY_PAD_H, KEY_PAD_V);
        t.setFocusable(true);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(KEY_MARGIN, 0, KEY_MARGIN, 0);
        t.setLayoutParams(lp);
        focusZoom(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("空格".equals(label)) append(" ");
                else if ("删除".equals(label)) delete();
                else if ("清空".equals(label)) box.setText("");
                else if ("搜索".equals(label)) submit(box.getText().toString().trim());
                else append(label);
            }
        });
        return t;
    }

    private void append(String s) {
        Editable e = box.getText();
        int pos = box.getSelectionStart();
        if (pos < 0) pos = e.length();
        e.insert(pos, s);
        box.setSelection(box.getSelectionStart());
    }

    private void delete() {
        Editable e = box.getText();
        int pos = box.getSelectionStart();
        if (pos > 0) {
            e.delete(pos - 1, pos);
            box.setSelection(pos - 1);
        }
    }

    private void focusEnKey() {
        if (keyboard.getChildCount() > 0) {
            LinearLayout row0 = (LinearLayout) keyboard.getChildAt(0);
            if (row0.getChildCount() > 0) row0.getChildAt(0).requestFocus();
        }
    }

    // ---------------- 历史 / 分类 / 热搜 的 chip 行（分页） ----------------

    private void buildHistoryChips() {
        histRow1.removeAllViews();
        histRow2.removeAllViews();
        for (int i = 0; i < allHist.size() && i < PAGE; i++) {
            final String kw = allHist.get(i);
            TextView chip = makeChip(kw, new View.OnClickListener() {
                @Override public void onClick(View v) { submit(kw); }
            }, false);
            if (i < 3) histRow1.addView(chip);
            else histRow2.addView(chip);
        }
        moreHist.setVisibility(allHist.size() > PAGE ? View.VISIBLE : View.GONE);
        moreHist.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openMore("搜索历史", allHist, "search", null);
            }
        });
    }

    private void buildHotChips() {
        hotChips.removeAllViews();
        int shown = 0;
        for (String kw : allHot) {
            if (shown >= PAGE) break;
            final String k = kw;
            hotChips.addView(makeChip(k, new View.OnClickListener() {
                @Override public void onClick(View v) { submit(k); }
            }, true));
            shown++;
        }
        moreHot.setVisibility(allHot.size() > PAGE ? View.VISIBLE : View.GONE);
        moreHot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openMore("热搜榜（全部）", allHot, "search", null);
            }
        });
    }

    /** 后台拉取源的「热搜榜（按搜索量）」，成功后刷新热搜 chip */
    private void loadHotWordsAsync() {
        final String url = apiUrl;
        new AsyncTask<Void, Void, List<String>>() {
            @Override
            protected List<String> doInBackground(Void... v) {
                try {
                    return SiteApi.hotWords(url);
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(List<String> words) {
                // 保留内置固定热搜列表（团长/潜伏/…），不使用源的随机热搜覆盖
                return;
            }
        }.execute();
    }

    private List<String> toList(String[] arr) {
        List<String> out = new ArrayList<String>();
        if (arr != null) for (String s : arr) out.add(s == null ? "" : s);
        return out;
    }

    /** 打开通用「更多」分页页 */
    private void openMore(String title, List<String> items, String action, String[] tids) {
        Intent in = new Intent(this, MoreActivity.class);
        in.putExtra(MoreActivity.EXTRA_TITLE, title);
        in.putExtra(MoreActivity.EXTRA_ITEMS, items.toArray(new String[0]));
        in.putExtra(MoreActivity.EXTRA_ACTION, action);
        if (tids != null) in.putExtra(MoreActivity.EXTRA_TIDS, tids);
        startActivity(in);
    }

    private void browseCat(String tid, String name) {
        Intent in = new Intent(this, MainActivity.class);
        in.putExtra("catTid", tid);
        in.putExtra("catName", name == null ? "" : name);
        in.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(in);
        finish();
    }

    // ---------------- 历史持久化 ----------------

    private List<String> getHistory() {
        List<String> out = new ArrayList<String>();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String s = sp.getString(HIST_KEY, "");
        if (s == null || s.length() == 0) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i, ""));
        } catch (Throwable t) {
        }
        return out;
    }

    private void saveHistory(String kw) {
        List<String> list = getHistory();
        list.remove(kw);
        list.add(0, kw);
        while (list.size() > 20) list.remove(list.size() - 1);
        try {
            JSONArray a = new JSONArray();
            for (String s : list) a.put(s);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HIST_KEY, a.toString()).apply();
        } catch (Throwable t) {
        }
    }

    // ---------------- 提交 ----------------

    private void submit(String kw) {
        if (kw == null) kw = "";
        kw = kw.trim();
        if (kw.length() == 0) {
            Toast.makeText(this, "请输入关键字", Toast.LENGTH_SHORT).show();
            return;
        }
        saveHistory(kw);
        Intent in = new Intent(this, MainActivity.class);
        in.putExtra("searchKw", kw);
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
