package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;

public class TmdbSpider extends Spider {

    private String dynamicApiKey = "";
    private String proxyHost = "";
    private int proxyPort = 0;

    private static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w300";
    private static final String TMDB_DETAIL_IMG = "https://image.tmdb.org/t/p/w500";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    
    private static final ExecutorService executor = Executors.newFixedThreadPool(8);
    
    @Override
    public void init(android.content.Context context, String extend) {
        if (extend != null && !extend.trim().isEmpty()) {
            String[] split = extend.trim().split("#");
            this.dynamicApiKey = split[0];
            if (split.length > 1) {
                try {
                    String[] proxyAddr = split[1].split(":");
                    this.proxyHost = proxyAddr[0];
                    this.proxyPort = Integer.parseInt(proxyAddr[1]);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        String[][] platforms = {{"netflix", "Netflix", "213"}, {"hbo", "HBO Max", "49"}, {"disney", "Disney+", "2739"}};
        JSONArray classes = new JSONArray();
        for (String[] p : platforms) {
            JSONObject cls = new JSONObject();
            cls.put("type_id", p[0]);
            cls.put("type_name", p[1]);
            classes.put(cls);
        }
        return new JSONObject().put("class", classes).toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try { page = Integer.parseInt(pg); } catch (Exception ignored) {}
        String networkId = tid.equals("netflix") ? "213" : (tid.equals("hbo") ? "49" : "2739");
        
        Map<String, String> params = new HashMap<>();
        params.put("with_networks", networkId);
        params.put("language", "zh-CN");
        params.put("page", String.valueOf(page));
        params.put("sort_by", "popularity.desc");

        JSONObject res = fetchTmdb("/discover/tv", params);
        JSONArray results = res.optJSONArray("results");
        
        JSONArray list = new JSONArray();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                JSONObject v = new JSONObject();
                v.put("vod_id", "tv|" + item.optInt("id"));
                v.put("vod_name", item.optString("name"));
                v.put("vod_pic", TMDB_IMAGE_BASE + item.optString("poster_path"));
                v.put("vod_remarks", "⭐" + item.optDouble("vote_average"));
                list.put(v);
            }
        }
        return new JSONObject().put("page", page).put("pagecount", res.optInt("total_pages")).put("list", list).toString();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String[] parts = ids.get(0).split("\\|");
        Map<String, String> params = new HashMap<>();
        params.put("language", "zh-CN");
        JSONObject data = fetchTmdb("/" + parts[0] + "/" + parts[1], params);
        if (data == null) return "";

        String title = data.optString("name", data.optString("title"));
        JSONObject vod = new JSONObject();
        vod.put("vod_id", ids.get(0));
        vod.put("vod_name", title);
        vod.put("vod_pic", TMDB_DETAIL_IMG + data.optString("poster_path"));
        vod.put("vod_year", data.optString("first_air_date", "    ").substring(0, 4));
        vod.put("vod_content", data.optString("overview", "暂无简介").trim());
        
        // --- 策略修改：不给具体分集，只给一个搜索动作线路 ---
        vod.put("vod_play_from", "手动搜索");
        // 这里不要带 $ 符号，防止壳子识别为选集列表触发预加载
        // 直接传 keyword，在 playerContent 里拦截 flag
        vod.put("vod_play_url", "全网资源搜索#" + title); 

        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // flag 是 "手动搜索"，id 是 "全网资源搜索#电影名"
        String keyword = id.contains("#") ? id.split("#")[1] : id;
        JSONObject result = new JSONObject();
        // 严格遵守 SPIDER.md 规范的 Action 跳转
        result.put("action", "search");
        result.put("key", keyword);
        return result.toString();
    }

    private JSONObject fetchTmdb(String endpoint, Map<String, String> params) throws Exception {
        params.put("api_key", this.dynamicApiKey);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append("=").append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        URL url = new URL("https://api.themoviedb.org/3" + endpoint + "?" + sb.toString());
        HttpURLConnection conn;
        if (!proxyHost.isEmpty()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            conn = (HttpURLConnection) url.openConnection(proxy);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        conn.setRequestProperty("User-Agent", UA);
        conn.setConnectTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder res = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) res.append(line);
        in.close();
        return new JSONObject(res.toString());
    }
}