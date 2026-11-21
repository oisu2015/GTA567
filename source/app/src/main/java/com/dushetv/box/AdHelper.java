package com.dushetv.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 广告 / 外链位配置中心。
 *
 * <p>设计要点（与现有「升级检查 / 直连源列表」同一思路）：
 * <ul>
 *   <li>v4.0 起<b>已取消京东商城</b>，只保留一个「留言」入口；</li>
 *   <li>「留言」按钮点开是<b>内置公众号二维码</b>（{@code res/drawable-nodpi/wechat_oa_v2.png}），
 *       不依赖在线二维码接口——电视没网、第三方接口挂了也照样能扫；</li>
 *   <li>开机静默拉取远程 {@code ads.json} 覆盖文案 / 显隐，后台改文案无需重新打包；</li>
 *   <li>TV 遥控器方向键导航：所有广告入口都做成可聚焦、可点按的标准按钮，沿用全局焦点特效，
 *       不会引入之前那种「按钮缩小 / 焦点卡死」的 TV bug。</li>
 * </ul>
 *
 * <p>远程 ads.json 示例（字段全可省，缺省用内置默认）：
 * <pre>
 * {
 *   "topButtons": [
 *     {"label":"留言","type":"wechat","name":"七微电脑"}
 *   ],
 *   "wechat": {"name":"七微电脑"},
 *   "wallpaper": "https://www.example.com/bg.jpg",
 *   "weather": {"city":"北京","lat":39.9042,"lon":116.4074}
 * }
 * </pre>
 */
public class AdHelper {
    /** 远程广告配置地址（与升级 / 源列表同域名，改这里即可换服务器） */
    private static final String ADS_URL = "https://7vga.cn/zqnew/ads.json";

    // ===== 默认占位（远程拉取失败或对应字段缺失时使用）=====
    private static final String DEFAULT_WECHAT_NAME = "七微电脑";
    private static final String DEFAULT_WECHAT_URL = "";
    /** 顶部入口按钮默认文案（v4.0：由「公众号」改名为「留言」） */
    private static final String DEFAULT_TIP_LABEL = "留言";

    // ===== 当前生效配置（静态，供各页面读取）=====
    private static List<TopButton> topButtons = defaultTopButtons();
    private static String wechatName = DEFAULT_WECHAT_NAME;
    private static String wechatUrl = DEFAULT_WECHAT_URL;
    /** 远程是否覆盖过顶部按钮：覆盖过才在拉取完成后重建操作栏，避免无谓抢焦点 */
    private static boolean topChanged = false;

    // ===== 自定义高清壁纸（Apple TV 风格背景层，远程可配；默认空→用内置渐变）=====
    /** 壁纸远程地址；为空时主界面回退到内置深蓝渐变背景 */
    private static String wallpaper = "";

    // ===== 天气预报（Open-Meteo 免密钥；远程可改城市，默认北京）=====
    private static String weatherCity = "北京";
    private static double weatherLat = 39.9042;
    private static double weatherLon = 116.4074;
    /** 远程是否覆盖过天气配置：覆盖过才在拉取完成后重新拉取天气 */
    private static boolean weatherChanged = false;

    /** 顶部操作栏上的一个广告 / 外链按钮 */
    public static class TopButton {
        String label;
        String type;   // "link" = 打开 url；"wechat" = 弹出公众号二维码
        String url;    // type=link 时使用
        String name;   // type=wechat 时使用（公众号名，用于搜索提示）
        TopButton(String label, String type, String url, String name) {
            this.label = label;
            this.type = type;
            this.url = url;
            this.name = name;
        }
    }

    /** 内置默认顶部按钮：仅「留言」（京东商城已于 v4.0 取消，详情页推广位也已移除） */
    private static List<TopButton> defaultTopButtons() {
        List<TopButton> l = new ArrayList<TopButton>();
        l.add(new TopButton(DEFAULT_TIP_LABEL, "wechat", null, DEFAULT_WECHAT_NAME));
        return l;
    }

    public static List<TopButton> getTopButtons() { return topButtons; }
    public static String getWechatName() { return wechatName; }
    public static String getWechatUrl() { return wechatUrl; }
    public static boolean topChanged() { return topChanged; }

    /** 自定义壁纸地址（空字符串表示用内置渐变） */
    public static String getWallpaper() { return wallpaper; }
    /** 天气城市名（用于显示） */
    public static String getWeatherCity() { return weatherCity; }
    public static double getWeatherLat() { return weatherLat; }
    public static double getWeatherLon() { return weatherLon; }
    public static boolean weatherChanged() { return weatherChanged; }

    /**
     * 静默拉取远程广告配置。成功解析且与默认不同（覆盖过顶部按钮）时回调 onChanged，
     * 调用方据此按需重建操作栏。失败一律不打扰用户、退回默认。
     */
    public static void fetchAds(final Runnable onChanged) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    String json = Net.getText(ADS_URL, null);
                    if (json == null || json.trim().length() == 0) return false;
                    parse(json);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (ok && onChanged != null) {
                    try { onChanged.run(); } catch (Throwable ignored) {}
                }
            }
        }.execute();
    }

    /** 解析远程 ads.json，仅覆盖出现的字段，其余保持默认 */
    private static void parse(String json) {
        try {
            JSONObject o = new JSONObject(json.trim());

            // 公众号（名称 + 可选的关注主页链接）
            JSONObject wc = o.optJSONObject("wechat");
            if (wc != null) {
                if (wc.optString("name", "").length() > 0) wechatName = wc.optString("name");
                if (wc.optString("url", "").length() > 0) wechatUrl = wc.optString("url");
            }

            // 顶部操作栏按钮（留言等）
            JSONArray tb = o.optJSONArray("topButtons");
            if (tb != null && tb.length() > 0) {
                List<TopButton> list = new ArrayList<TopButton>();
                for (int i = 0; i < tb.length(); i++) {
                    JSONObject b = tb.optJSONObject(i);
                    if (b == null) continue;
                    String label = b.optString("label", "");
                    if (label.length() == 0) continue;
                    // v4.0 已取消京东商城：远程配置里若仍带京东字样，一律忽略，防止被远程加回来
                    if (isJD(label)) continue;
                    String type = b.optString("type", "link");
                    String url = b.optString("url", "");
                    String name = b.optString("name", "");
                    if (isJD(url)) continue;
                    list.add(new TopButton(label, type, url, name));
                }
                if (!list.isEmpty()) {
                    topButtons = list;
                    topChanged = true;
                }
            }

            // 自定义高清壁纸（Apple TV 风格背景）：远程可下发一张壁纸地址，空则不覆盖
            String wp = o.optString("wallpaper", "");
            if (wp != null && wp.trim().length() > 0 && !isJD(wp)) {
                wallpaper = wp.trim();
            }

            // 天气预报城市（Open-Meteo 免密钥，默认北京；可远程改城市与经纬度）
            JSONObject w = o.optJSONObject("weather");
            if (w != null) {
                if (w.optString("city", "").length() > 0) weatherCity = w.optString("city");
                double la = w.optDouble("lat", Double.NaN);
                double lo = w.optDouble("lon", Double.NaN);
                if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                    weatherLat = la;
                    weatherLon = lo;
                }
                weatherChanged = true;
            }
        } catch (Throwable t) {
            // 解析失败：静默保持默认
        }
    }

    /** 判断文案/链接是否属于京东（v4.0 已取消，用于过滤远程配置） */
    private static boolean isJD(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("京东") || t.contains("jd.com") || t.contains("jingdong");
    }

    /** 打开外部链接（浏览器 / 已安装 App 接管）。TV 上无浏览器时 Toast 提示 */
    public static void openUrl(Activity a, String url) {
        if (url == null || url.trim().length() == 0) {
            Toast.makeText(a, "暂未配置跳转链接", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(a, "无法打开链接：" + url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「留言」弹窗：<b>内置二维码图片</b> + 公众号名 + 留言建议引导。
     *
     * <p>二维码直接来自 {@code res/drawable-nodpi/wechat_oa_v2.png}（1032×1032，PNG 无损、
     * nodpi 不被 density 二次缩放），不依赖在线二维码接口，电视离线也能扫。
     */
    public static void showWechat(final Activity a) {
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("公众号留言");

        LinearLayout ll = new LinearLayout(a);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        ll.setPadding(40, 30, 40, 20);

        TextView tip = new TextView(a);
        String name = (wechatName != null && wechatName.length() > 0) ? wechatName : DEFAULT_WECHAT_NAME;
        tip.setText("微信「扫一扫」下方二维码关注「" + name + "」\n"
                + "欢迎在公众号「留言」区给我们建议，帮助我们把软件做得更好");
        tip.setTextSize(16);
        tip.setTextColor(0xffffffff);
        tip.setGravity(android.view.Gravity.CENTER);
        tip.setLineSpacing(1, 1.3f);
        ll.addView(tip);

        ImageView iv = new ImageView(a);
        // 280px：1080p 电视上约占屏高 1/4，比之前明显小，扫码仍然稳定（源图 1032px 足够清晰）
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(280, 280);
        ip.setMargins(0, 16, 0, 0);
        iv.setLayoutParams(ip);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageResource(R.drawable.wechat_oa_v2);
        ll.addView(iv);

        // 免责说明：本 App 永久免费，片源来自互联网
        TextView note = new TextView(a);
        note.setText("本 App 永久免费 · 视频来源互联网");
        note.setTextSize(14);
        note.setTextColor(0xff9e9e9e);
        note.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.setMargins(0, 14, 0, 0);
        note.setLayoutParams(np);
        ll.addView(note);

        b.setView(ll);
        // 远程若配置了公众号主页链接，额外给一个跳转按钮
        if (wechatUrl != null && wechatUrl.length() > 0) {
            b.setPositiveButton("打开主页", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    openUrl(a, wechatUrl);
                }
            });
        }
        b.setNegativeButton("关闭", null);
        b.show();
    }
}
