package com.dushetv.box;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 接口里的一个站点 */
class Site {
    public String key;
    public String name;
    public String api;
    public String ext;
    public String jar;
    public int type;          // 0=xml, 1=json, 3=spider
    public int searchable = 1;
    public int quickSearch = 1;
    public int filterable = 1;
    public int playerType = -1;
    public String playerUrl = "";
}

/** 分类（首页顶部分类条） */
class Category {
    public String id;
    public String name;
}

/** 视频条目（列表/首页里的一个卡片） */
class VodItem {
    public String id;
    public String name;
    public String pic;
    public String remarks;
    /** 所属分类名（来自接口的 vod_class），用于首页按分类过滤短剧/动漫 */
    public String cat = "";
    /** 评分（0~10），用于首页排序 */
    public double score = -1;
    /** 播放量/热度，辅助排序 */
    public int hits = 0;
    /** 列表/搜索接口返回的原始 JSON（含播放地址），点击时直接用于详情，规避二次请求错位 */
    public JSONObject raw;
}

/** 一集 */
class Episode {
    public String name;
    public String url;
}

/** 解析后的分集结构：每个播放源 -> 它的剧集列表 */
class PlayData {
    public List<String> sources = new ArrayList<String>();
    public List<List<Episode>> episodes = new ArrayList<List<Episode>>();
}
