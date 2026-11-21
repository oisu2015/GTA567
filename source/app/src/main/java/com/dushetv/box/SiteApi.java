package com.dushetv.box;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dushetv.box.Net;

/** 单个站点的 type 0/1 接口调用（苹果CMS 风格 JSON） */
public class SiteApi {
    private final Site site;
    private final Map<String, String> headers = new HashMap<String, String>();

    public SiteApi(Site s) {
        this.site = s;
    }

    public Site getSite() {
        return site;
    }

    private String buildUrl(Map<String, String> params) throws UnsupportedEncodingException {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) q.append("&");
            q.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "utf-8"));
        }
        String api = site.api;
        if (api.indexOf('?') >= 0) return api + "&" + q;
        return api + "?" + q;
    }

    private JSONObject request(Map<String, String> params) throws Exception {
        if (site.type == 3) {
            throw new Exception("该站点(type=3/spider)需要 JS 解析引擎，v1 暂不支持，请换用 JSON 类型接口");
        }
        String url = buildUrl(params);
        String body = Net.getText(url, headers);
        if (body == null || body.trim().length() == 0) {
            throw new Exception("接口返回为空");
        }
        String trimmed = body.trim();
        // 拦截非 JSON 响应：网页(html)会比纯文本更早出现，且绝不该把源码抛给用户
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            String low = trimmed.toLowerCase();
            if (low.startsWith("<!doctype") || low.contains("<html") || low.startsWith("<?xml")) {
                // 接口返回的是网页/XML 而非 JSON：提取 <title> 作为可读原因，绝不显示源码
                String reason = extractHtmlTitle(trimmed);
                String tag = (low.startsWith("<?xml")) ? "XML" : "网页";
                throw new Exception("该源返回的是" + tag
                        + (reason != null && reason.length() > 0 ? "（" + reason + "）" : "")
                        + "，很可能已失效或需要登录/JS 引擎");
            }
            // 纯文本：取前60字符作为提示，避免超长垃圾信息
            String preview = trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
            throw new Exception(preview);
        }
        try {
            return new JSONObject(body);
        } catch (Throwable t) {
            String h = body.trim();
            if (h.startsWith("<")) {
                String reason = extractHtmlTitle(h);
                throw new Exception("该源返回的是网页而非接口数据"
                        + (reason != null && reason.length() > 0 ? "（" + reason + "）" : "")
                        + "，可能已失效或需要登录/JS 引擎");
            }
            return new JSONObject(ApiConfig.sanitize(body));
        }
    }

    /**
     * 首页：分类 + 最近更新。
     * 苹果CMS 的 ac=list 只返回简表（没有封面图）但带 class 分类；
     * ac=detail 才返回带 vod_pic 的完整列表。所以这里分两次取，保证电视上有封面。
     */
    /**
     * 首页只拉分类（首页推荐流不可控、多为短剧，故不再使用推荐流）。
     * 真正的首页影片由 homeMoviesTv() 另行拉取。
     */
    public HomeResult home() throws Exception {
        HomeResult r = new HomeResult();
        Map<String, String> pc = new HashMap<String, String>();
        pc.put("ac", "list");
        pc.put("pg", "1");
        try {
            r.categories = parseCategories(request(pc));
        } catch (Throwable ignored) {
            // 分类拿不到不影响看片
        }
        return r;
    }

    /**
     * 首页影片：只取「电影」+「电视剧」+「综艺」三类内容混合，按评分热度排序，
     * 并按 item 自带分类（vod_class）精准剔除短剧/动漫。若接口没有这些标准分类
     * 则兜底遍历所有分类过滤；仍为空才拉全部分类首页过滤。
     */
    public List<VodItem> homeMoviesTv(List<Category> cats) throws Exception {
        String[] norms = {"电影", "电视剧", "综艺"};
        List<VodItem> out = new ArrayList<VodItem>();
        for (String n : norms) {
            String tid = findTid(cats, n);
            if (tid != null) {
                try { out.addAll(list(tid, 1).items); } catch (Throwable ignored) {}
            }
        }
        if (out.isEmpty() && cats != null && !cats.isEmpty()) {
            // 兜底1：没命中标准分类名时，遍历所有原始分类各拉一页再过滤
            int cnt = 0;
            for (Category c : cats) {
                if (cnt >= 20) break;
                if (c.id == null || c.id.length() == 0) continue;
                try { out.addAll(list(c.id, 1).items); } catch (Throwable ignored) {}
                cnt++;
            }
        }
        if (out.isEmpty()) {
            // 兜底2：连分类都没有，拉全部分类首页并过滤
            try {
                List<VodItem> all = list("", 1).items;
                out = new ArrayList<VodItem>();
                for (VodItem v : all) { if (!isShortDramaOrAnime(v)) out.add(v); }
            } catch (Throwable ignored) {}
        }
        // 按分类/片名过滤短剧/动漫（双保险）
        List<VodItem> cleaned = new ArrayList<VodItem>();
        for (VodItem v : out) { if (!isShortDramaOrAnime(v)) cleaned.add(v); }
        sortByScoreAndHits(cleaned);
        return cleaned;
    }

    /** item 是否明显是短剧/动漫（按接口分类 vod_class / 片名 / remarks 判断，用于首页过滤） */
    private static boolean isShortDramaOrAnime(VodItem v) {
        if (v == null) return false;
        String cat = (v.cat != null) ? v.cat : "";
        String name = (v.name != null) ? v.name : "";
        String remarks = (v.remarks != null) ? v.remarks : "";

        // 1) 分类字段直接命中
        if (cat.contains("短剧") || cat.contains("动漫")) return true;

        // 2) 片名命中
        if (name.contains("短剧") || name.contains("动漫")) return true;
        // 番剧/番组常用字
        if (name.contains("番") && !name.contains("番位")) return true;

        // 3) remarks 命中：短剧几乎总是标「全集」而非「更新至XX集」/「全XX集」
        //    「全集」且分类不是明确的电影/电视剧 → 短剧
        if (remarks.equals("全集") || remarks.matches("^全?\\d{1,3}集$")) {
            // 全集 / 全12集 这类格式，如果集数≤30且不含电影/电视剧分类，判为短剧
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(remarks);
            int eps = 999;
            if (m.find()) { try { eps = Integer.parseInt(m.group()); } catch (Throwable ignored) {} }
            if (eps <= 30 && !cat.contains("电影") && !cat.contains("电视剧")
                && !cat.contains("综艺") && !cat.contains("纪录片")) {
                return true;
            }
        }

        // 4) 更新至XX集 且集数偏小（≤40）且无明确长视频分类 → 可疑短剧
        if (remarks.contains("更新至") || remarks.matches("^更新.*\\d+.*集")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(remarks);
            int eps = 999;
            while (m.find()) { try { int n = Integer.parseInt(m.group()); if (n < eps) eps = n; } catch (Throwable ignored) {} }
            if (eps <= 40 && !cat.contains("电影") && !cat.contains("电视剧")
                && !cat.contains("综艺") && !cat.contains("纪录片")) {
                return true;
            }
        }

        // 5) 片名含典型短剧命名模式（感叹号、口语化、霸总/甜宠等关键词）
        String lowerName = name.toLowerCase();
        String[] sdKeywords = {"霸总", "甜宠", "闪婚", "重生", "逆袭", "萌宝", "总裁",
            "赘婿", "战神", "医妃", "王妃", "王爷", "太子", "殿下", "千金",
            "离婚后", "结婚后", "带球跑", "合约", "宠妻", "娇妻", "老婆"};
        for (String kw : sdKeywords) { if (name.contains(kw)) return true; }
        // 感叹号结尾 + 非电影标题（电影很少用!结尾）
        if (name.endsWith("!") || name.endsWith("！")) {
            if (!cat.contains("电影") && !cat.contains("综艺")) return true;
        }

        return false;
    }

    /** 合并多个分类 tid 的列表（用于「其他」收纳短剧/动漫等），带翻页信息 */
    public ListResult listMulti(List<String> tids, int pg) {
        ListResult r = new ListResult();
        if (tids == null) return r;
        int cnt = 0;
        for (String tid : tids) {
            if (tid == null || tid.length() == 0) continue;
            if (cnt >= 6) break;   // 剩余分类可能很多，限制单次请求数，避免电视端卡死
            try {
                ListResult sub = list(tid, pg);
                r.items.addAll(sub.items);
                if (sub.hasMore) r.hasMore = true;
            } catch (Throwable ignored) {}
            cnt++;
        }
        sortByHits(r.items);
        return r;
    }

    /** 在一组分类里找第一个归为指定标准大类的 tid */
    public static String findTid(List<Category> cats, String targetNorm) {
        if (cats == null) return null;
        for (Category c : cats) {
            if (targetNorm.equals(normalizeCat(c.name))) return c.id;
        }
        return null;
    }

    // ------- 标准分类归并（短剧/动漫一律归「其他」）-------

    private static final String[] MOVIE_KW = {"电影","剧情","动作","爱情","科幻","恐怖","战争","喜剧","悬疑","犯罪","奇幻","冒险","惊悚","武侠","历史","传记","歌舞","黑色电影","戏曲"};
    private static final String[] TV_KW = {"电视剧","国产剧","港台剧","日韩剧","欧美剧","泰剧","网剧","连续剧"};
    private static final String[] VARIETY_KW = {"综艺","综艺节目","真人秀","选秀","访谈","脱口秀","音乐","晚会","竞技"};
    private static final String[] SHORT_KW = {"短剧"};
    private static final String[] ANIME_KW = {"动漫","动画片","国漫","日漫","番剧","卡通","漫画改"};
    private static final String[] DOCU_KW = {"纪录片","人文","自然","社会","军事","科技","探索"};

    /** 把原始分类名归入标准大类：电影/电视剧/综艺/短剧/动漫/纪录片/其他 */
    public static String normalizeCat(String raw) {
        if (raw == null || raw.length() == 0) return "其他";
        String r = raw.toLowerCase();
        for (String kw : MOVIE_KW) { if (r.contains(kw.toLowerCase())) return "电影"; }
        for (String kw : TV_KW)   { if (r.contains(kw.toLowerCase())) return "电视剧"; }
        for (String kw : VARIETY_KW){ if (r.contains(kw.toLowerCase())) return "综艺"; }
        for (String kw : SHORT_KW){ if (r.contains(kw.toLowerCase())) return "短剧"; }
        for (String kw : ANIME_KW) { if (r.contains(kw.toLowerCase())) return "动漫"; }
        for (String kw : DOCU_KW)  { if (r.contains(kw.toLowerCase())) return "纪录片"; }
        return "其他";
    }

    /** 分类/翻页列表（用 ac=detail 才有封面图）。返回带 hasMore 的结果，支持服务器端分页 */
    public ListResult list(String tid, int pg) throws Exception {
        Map<String, String> p = new HashMap<String, String>();
        p.put("ac", "detail");
        p.put("pg", String.valueOf(pg));
        if (tid != null && tid.length() > 0) p.put("t", tid);
        JSONObject j = request(p);
        return wrapList(j, pg);
    }

    /** 搜索（ac=detail 命中更多且带封面），带翻页信息 */
    public ListResult search(String kw, int pg) throws Exception {
        String srcLabel = "[" + (site != null ? site.name : "?") + "] ";
        Map<String, String> p = new HashMap<String, String>();
        p.put("ac", "detail");
        p.put("wd", kw);
        p.put("pg", String.valueOf(pg));
        JSONObject j = null;
        try {
            j = request(p);
        } catch (Exception e) {
            // 接口返回非JSON/明文错误（如"暂不支持搜索"），尝试 ac=list 降级
            if (e.getMessage() != null && !e.getMessage().startsWith("http")) {
                try {
                    Map<String, String> p2 = new HashMap<String, String>();
                    p2.put("ac", "list");
                    p2.put("wd", kw);
                    p2.put("pg", String.valueOf(pg));
                    return wrapList(request(p2), pg);
                } catch (Throwable ignored) {
                    throw new Exception(srcLabel + "该源暂不支持搜索（"
                            + stripHtml(e.getMessage()) + "）。可菜单→更换接口换一个 JSON 源");
                }
            }
            throw e;
        }
        ListResult r = wrapList(j, pg);
        if (r.items.isEmpty() && pg == 1) {
            // 个别站点只认 ac=list 的搜索
            try {
                Map<String, String> p2 = new HashMap<String, String>();
                p2.put("ac", "list");
                p2.put("wd", kw);
                p2.put("pg", "1");
                r = wrapList(request(p2), pg);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }

    /** 把接口返回的列表包装为 ListResult，并依据 pagecount/total 判断是否有下一页 */
    private static ListResult wrapList(JSONObject j, int pg) throws JSONException {
        ListResult r = new ListResult();
        r.items = parseList(j);
        int pagecount = j.optInt("pagecount", 0);
        r.pageCount = pagecount;
        int total = j.optInt("total", 0);
        r.total = total;
        if (pagecount > 0) {
            r.hasMore = pg < pagecount;
        } else if (total > 0) {
            r.hasMore = (long) pg * Math.max(r.items.size(), 1) < total;
        } else {
            // 兜底：接口未返回总页数时，有内容就允许翻页，下一页为空则自动停止
            r.hasMore = !r.items.isEmpty();
        }
        return r;
    }

    /** 取源的热搜词（按搜索量排序，取前若干）。尽力而为：多个常见端点都试，失败返回空 */
    public static List<String> hotWords(String apiUrl) {
        List<String> out = new ArrayList<String>();
        if (apiUrl == null || apiUrl.length() == 0) return out;
        String base = baseHost(apiUrl);
        String[] urls = {
                base + "/index.php/ajax/hot?limit=15",
                base + "/index.php/ajax/hot",
                apiUrl + (apiUrl.indexOf('?') >= 0 ? "&" : "?") + "ac=hot"
        };
        for (String u : urls) {
            try {
                String body = Net.getText(u, null);
                if (body == null) continue;
                String t = body.trim();
                if (!t.startsWith("{") && !t.startsWith("[")) continue;
                List<String> found = parseHot(t);
                if (found != null && !found.isEmpty()) { out.addAll(found); break; }
            } catch (Throwable ignored) {
            }
        }
        // 去重 + 截断
        List<String> res = new ArrayList<String>();
        for (String s : out) if (!res.contains(s) && s.length() > 0) res.add(s);
        if (res.size() > 15) res = new ArrayList<String>(res.subList(0, 15));
        return res;
    }

    /** 从热搜接口返回里尽量解析出搜索词（兼容 list/data/数组根等多种格式） */
    private static List<String> parseHot(String t) {
        List<String> out = new ArrayList<String>();
        try {
            if (t.startsWith("[")) {
                JSONArray a = new JSONArray(t);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) addHotWord(out, o);
                }
            } else {
                JSONObject j = new JSONObject(t);
                JSONArray arr = j.optJSONArray("list");
                if (arr == null) arr = j.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) addHotWord(out, o);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addHotWord(List<String> out, JSONObject o) {
        String w = o.optString("searchword", "");
        if (w.length() == 0) w = o.optString("name", "");
        if (w.length() == 0) w = o.optString("word", "");
        if (w.length() > 0) out.add(w);
    }

    /** 取接口地址的 host 根（用于拼热搜等相对端点） */
    private static String baseHost(String api) {
        try {
            java.net.URL u = new java.net.URL(api);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Throwable t) {
            return api;
        }
    }

    /** 从 HTML 片段里提取 <title> 文本，作为可读的错误原因；失败返回 null */
    private static String extractHtmlTitle(String html) {
        if (html == null) return null;
        int i = html.toLowerCase().indexOf("<title");
        if (i < 0) return null;
        int gt = html.indexOf(">", i);
        if (gt < 0) return null;
        int end = html.toLowerCase().indexOf("</title>", gt);
        if (end < 0) return null;
        String t = html.substring(gt + 1, end).trim();
        // 去掉标签内可能的空白/换行，限制长度
        t = t.replaceAll("\\s+", " ");
        if (t.length() > 40) t = t.substring(0, 40) + "…";
        return t.length() > 0 ? t : null;
    }

    /** 把消息里残留的 HTML 标签剥掉，避免把源码显示给用户 */
    private static String stripHtml(String s) {
        if (s == null) return "";
        // 去掉 <...> 标签
        String t = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 60) t = t.substring(0, 60) + "…";
        return t;
    }

    /** 详情：返回 vod 对象（含 vod_play_from / vod_play_url） */
    public JSONObject detail(String vodId) throws Exception {
        Map<String, String> p = new HashMap<String, String>();
        p.put("ac", "detail");
        p.put("ids", vodId);
        JSONObject j = request(p);
        JSONArray list = j.optJSONArray("list");
        if (list == null || list.length() == 0) throw new Exception("详情为空");
        return list.getJSONObject(0);
    }

    // ---------- 解析 ----------

    public static List<Category> parseCategories(JSONObject j) throws JSONException {
        List<Category> out = new ArrayList<Category>();
        JSONArray cls = j.optJSONArray("class");
        if (cls != null) {
            for (int i = 0; i < cls.length(); i++) {
                JSONObject o = cls.getJSONObject(i);
                Category c = new Category();
                c.id = o.optString("type_id", "");
                c.name = o.optString("type_name", "");
                if (c.id.length() > 0) out.add(c);
            }
        }
        return out;
    }

    public static List<VodItem> parseList(JSONObject j) throws JSONException {
        List<VodItem> out = new ArrayList<VodItem>();
        JSONArray list = j.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            VodItem v = new VodItem();
            v.id = o.optString("vod_id", "");
            v.name = o.optString("vod_name", "");
            v.pic = o.optString("vod_pic", "");
            if (v.pic.length() == 0) v.pic = o.optString("vod_pic_thumb", "");
            v.remarks = o.optString("vod_remarks", "");
            if (v.remarks.length() == 0) v.remarks = o.optString("vod_year", "");
            v.cat = o.optString("vod_class", "");  // 所属分类名，用于首页按类过滤短剧/动漫
            // 评分和热度（用于首页排序）
            String sc = o.optString("vod_score", "0");
            try { v.score = Double.parseDouble(sc); } catch (Throwable ignored) {}
            String ht = o.optString("vod_hits", "0");
            try { v.hits = Integer.parseInt(ht); } catch (Throwable ignored) {}
            v.raw = o;   // 缓存原始 JSON（含播放地址），点击时直接用于详情，规避二次请求错位
            if (v.id.length() > 0) out.add(v);
        }
        return out;
    }

    /** 解析 vod_play_from / vod_play_url 为分源分集结构 */
    public static PlayData parsePlayData(JSONObject vod) throws JSONException {
        PlayData pd = new PlayData();
        String from = vod.optString("vod_play_from", "");
        String url = vod.optString("vod_play_url", "");
        String[] froms = from.length() > 0 ? from.split("\\$\\$\\$") : new String[0];
        String[] blocks = url.length() > 0 ? url.split("\\$\\$\\$") : new String[0];
        int n = Math.max(froms.length, blocks.length);
        if (n == 0) {
            // 没有多源分隔时，整段当一个源
            pd.sources.add("默认");
            pd.episodes.add(splitEpisodes(url));
            return pd;
        }
        for (int i = 0; i < n; i++) {
            String fname = (i < froms.length && froms[i].length() > 0) ? froms[i] : ("线路" + (i + 1));
            String block = (i < blocks.length) ? blocks[i] : "";
            pd.sources.add(fname);
            pd.episodes.add(splitEpisodes(block));
        }
        return pd;
    }

    private static List<Episode> splitEpisodes(String block) {
        List<Episode> eps = new ArrayList<Episode>();
        if (block == null || block.length() == 0) return eps;
        String[] arr = block.split("#");
        for (String s : arr) {
            if (s.length() == 0) continue;
            int idx = s.indexOf('$');
            Episode e = new Episode();
            if (idx >= 0) {
                e.name = s.substring(0, idx).trim();
                e.url = s.substring(idx + 1).trim();
            } else {
                e.name = s.trim();
                e.url = s.trim();
            }
            if (e.url.length() > 0) eps.add(e);
        }
        return eps;
    }

    /** 首页排序：评分优先（高→低），评分相同则按热度（高→低） */
    public static void sortByScoreAndHits(List<VodItem> items) {
        if (items == null || items.size() <= 1) return;
        Collections.sort(items, new Comparator<VodItem>() {
            @Override
            public int compare(VodItem a, VodItem b) {
                // 有评分的排前面
                if (a.score >= 0 && b.score < 0) return -1;
                if (a.score < 0 && b.score >= 0) return 1;
                if (a.score != b.score) return Double.compare(b.score, a.score);
                return Integer.compare(b.hits, a.hits);
            }
        });
    }

    /** 按播放量（vod_hits）降序排列，播放量相同再看评分。用于首页/分类/搜索列表 */
    public static void sortByHits(List<VodItem> items) {
        if (items == null || items.size() <= 1) return;
        Collections.sort(items, new Comparator<VodItem>() {
            @Override
            public int compare(VodItem a, VodItem b) {
                if (a.hits != b.hits) return Integer.compare(b.hits, a.hits);
                if (a.score >= 0 && b.score >= 0 && a.score != b.score)
                    return Double.compare(b.score, a.score);
                return 0;
            }
        });
    }

    /** 画质评分：4K / 蓝光 / 1080P / 高清 等权重更高（搜索结果优先展示高清） */
    public static int qualityScore(VodItem v) {
        if (v == null) return 0;
        String r = (v.remarks != null) ? v.remarks.toLowerCase() : "";
        String n = (v.name != null) ? v.name.toLowerCase() : "";
        String all = r + " " + n;
        if (all.contains("4k") || all.contains("2160")) return 100;
        if (all.contains("蓝光") || all.contains("bluray") || all.contains("bd")) return 95;
        if (all.contains("1080") || all.contains("hd") || all.contains("高清")) return 80;
        if (all.contains("720")) return 60;
        return 0;
    }

    /** 搜索结果排序：画质优先（4K/蓝光/高清排前），同画质按播放量降序 */
    public static void sortSearch(List<VodItem> items) {
        if (items == null || items.size() <= 1) return;
        Collections.sort(items, new Comparator<VodItem>() {
            @Override
            public int compare(VodItem a, VodItem b) {
                int qa = qualityScore(a), qb = qualityScore(b);
                if (qa != qb) return Integer.compare(qb, qa);
                if (a.hits != b.hits) return Integer.compare(b.hits, a.hits);
                if (a.score >= 0 && b.score >= 0 && a.score != b.score)
                    return Double.compare(b.score, a.score);
                return 0;
            }
        });
    }

    public static class HomeResult {
        public List<Category> categories = new ArrayList<Category>();
        public List<VodItem> list = new ArrayList<VodItem>();
    }

    /** 列表结果（带是否有下一页标记），用于支持服务器端分页 */
    public static class ListResult {
        public List<VodItem> items = new ArrayList<VodItem>();
        public boolean hasMore = false;
        /** 接口返回的总页数（pagecount）。0 表示未知，仅按 hasMore 兜底 */
        public int pageCount = 0;
        /** 接口返回的总条数（total）。0 表示未知 */
        public int total = 0;
    }
}
