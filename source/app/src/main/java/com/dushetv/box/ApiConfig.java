package com.dushetv.box;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 TVBox 风格的「接口」JSON。
 * 兼容两种外层结构：直接的 {"sites":[...]} 或 {"video":{"sites":[...]}}。
 */
public class ApiConfig {
    public String spider = "";
    public String wallpaper = "";
    public List<Site> sites = new ArrayList<Site>();

    /**
     * TVBox 配置文件普遍不是严格 JSON：带行注释、块注释，字符串里还混入裸制表符/换行。
     * org.json 会直接抛错，所以先清洗一遍。
     * 注意：必须区分「字符串内外」，否则会误删 https 地址里的双斜杠。
     */
    public static String sanitize(String s) {
        if (s == null) return "";
        int n = s.length();
        StringBuilder sb = new StringBuilder(n);
        boolean inStr = false, esc = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    sb.append(c);
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    sb.append(c);
                    esc = true;
                    continue;
                }
                if (c == '"') {
                    inStr = false;
                    sb.append(c);
                    continue;
                }
                // 字符串内部的裸控制字符 -> 空格，避免 org.json 报错
                if (c < 0x20) {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                sb.append(c);
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == '/') {           // 行注释
                    i += 2;
                    while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                    i--;
                    continue;
                }
                if (d == '*') {           // 块注释
                    i += 2;
                    while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        String out = sb.toString().trim();
        // 去掉 BOM / 前置杂字符，定位到第一个 { 或 [
        int b1 = out.indexOf('{'), b2 = out.indexOf('[');
        int start = (b1 < 0) ? b2 : (b2 < 0 ? b1 : Math.min(b1, b2));
        if (start > 0) out = out.substring(start);
        return out;
    }

    public static ApiConfig parse(String json) throws Exception {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Throwable first) {
            root = new JSONObject(sanitize(json));
        }
        ApiConfig cfg = new ApiConfig();
        cfg.spider = root.optString("spider", "");
        cfg.wallpaper = root.optString("wallpaper", "");
        JSONArray arr = null;
        if (root.has("video")) {
            JSONObject v = root.getJSONObject("video");
            if (v.has("sites")) arr = v.getJSONArray("sites");
        }
        if (arr == null && root.has("sites")) arr = root.getJSONArray("sites");
        if (arr == null) throw new Exception("接口里没有 sites 字段");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Site s = new Site();
            s.key = o.optString("key", "").trim();
            s.name = o.optString("name", s.key).trim();
            s.api = o.optString("api", "").trim();
            // 有些配置只给 name 不给 key，别把它们丢掉
            if (s.key.length() == 0) s.key = s.name;
            if (s.name.length() == 0) s.name = s.key;
            s.type = o.optInt("type", 1);
            s.searchable = o.optInt("searchable", 1);
            s.quickSearch = o.optInt("quickSearch", 1);
            s.filterable = o.optInt("filterable", 1);
            s.playerType = o.optInt("playerType", -1);
            s.playerUrl = o.optString("playerUrl", "");
            s.ext = o.optString("ext", "");
            s.jar = o.optString("jar", "");
            if (s.key.length() > 0 && s.api.length() > 0) cfg.sites.add(s);
        }
        if (cfg.sites.isEmpty()) throw new Exception("接口里没有有效的站点");
        return cfg;
    }
}
