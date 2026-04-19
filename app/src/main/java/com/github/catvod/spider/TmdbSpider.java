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
    private final ConcurrentHashMap<String, CacheEntry<JSONArray>> poolCache = new ConcurrentHashMap<>();
    
    private static final String[][] PLATFORMS = {
        {"netflix", "Netflix", "213"}, {"hbo", "HBO Max", "49"}, {"disney", "Disney+", "2739"},
        {"appletv", "Apple TV+", "2552"}, {"amazon", "Amazon Prime", "1024"}, {"hulu", "Hulu", "453"}
    };

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
        JSONArray classes = new JSONArray();
        for (String[] p : PLATFORMS) {
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
        String networkId = "213";
        for (String[] p : PLATFORMS) {
            if (p[0].equals(tid)) { networkId = p[2]; break; }
        }
        Map<String, String> params = new HashMap<>();
        params.put("with_networks", networkId);
        params.put("language", "zh-CN");
        params.put("page", String.valueOf(page));
        params.put("sort_by", extend.getOrDefault("sort", "popularity.desc"));
        JSONObject res = fetchTmdb("/discover/tv", params);
        JSONArray results = res.optJSONArray("results");
        return new JSONObject().put("page", page).put("pagecount", res.optInt("total_pages", page)).put("list", parallelProcessList(results, "tv")).toString();
    }

    private JSONArray parallelProcessList(JSONArray results, String type) throws Exception {
        if (results == null) return new JSONArray();
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            final JSONObject item = results.getJSONObject(i);
            futures.add(executor.submit(() -> {
                JSONObject v = new JSONObject();
                v.put("vod_id", type + "|" + item.optInt("id"));
                v.put("vod_name", item.optString("name", item.optString("title")));
                v.put("vod_pic", TMDB_IMAGE_BASE + item.optString("poster_path"));
                String year = item.optString("first_air_date", item.optString("release_date", ""));
                if (year.length() >= 4) year = year.substring(0, 4);
                v.put("vod_remarks", "⭐" + String.format("%.1f", item.optDouble("vote_average")) + (year.isEmpty() ? "" : " | " + year));
                return v;
            }));
        }
        JSONArray list = new JSONArray();
        for (Future<JSONObject> f : futures) {
            try { list.put(f.get(2, TimeUnit.SECONDS)); } catch (Exception ignored) {}
        }
        return list;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        String[] parts = ids.get(0).split("\\|");
        Map<String, String> params = new HashMap<>();
        params.put("language", "zh-CN");
        params.put("append_to_response", "credits");
        JSONObject data = fetchTmdb("/" + parts[0] + "/" + parts[1], params);
        if (data == null) return "";

        String title = data.optString("name", data.optString("title"));
        JSONObject vod = new JSONObject();
        vod.put("vod_id", ids.get(0));
        vod.put("vod_name", title);
        vod.put("vod_pic", TMDB_DETAIL_IMG + data.optString("poster_path"));
        vod.put("type_name", parts[0].equals("tv") ? "电视剧" : "电影");
        vod.put("vod_year", data.optString("first_air_date", data.optString("release_date", "    ")).substring(0, 4));
        vod.put("vod_remarks", "⭐" + String.format("%.1f", data.optDouble("vote_average")));
        vod.put("vod_content", data.optString("overview", "暂无简介").trim());
        
        // --- 对齐官方文档：解决自动播放 ---
        // SPIDER.md 规定了如果想要在 playerContent 触发 action，
        // 我们给它一个线路名，地址设为关键词。
        vod.put("vod_play_from", "手动搜索");
        // 这里不传 URL 协议头，避免触发预加载播放器逻辑
        vod.put("vod_play_url", "🔍点击搜索本站资源$" + title);

        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // 按照 FongMi 文档规定：执行 action 操作
        JSONObject result = new JSONObject();
        result.put("action", "search"); // 指定跳转到搜索页
        result.put("key", id);          // 搜索关键词（即 detailContent 传入的 title）
        return result.toString();
    }

    private JSONObject fetchTmdb(String endpoint, Map<String, String> params) throws Exception {
        if (this.dynamicApiKey.isEmpty()) throw new Exception("API KEY IS MISSING");
        params.put("api_key", this.dynamicApiKey);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append("=").append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        URL url = new URL("https://api.themoviedb.org/3" + endpoint + "?" + sb.toString());
        HttpURLConnection conn;
        if (!proxyHost.isEmpty() && proxyPort != 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            conn = (HttpURLConnection) url.openConnection(proxy);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        conn.setRequestProperty("User-Agent", UA);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        Thread.sleep(new Random().nextInt(40) + 10);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) response.append(line);
        in.close();
        return new JSONObject(response.toString());
    }

    private static class CacheEntry<T> {
        final T value; final long time;
        CacheEntry(T v) { this.value = v; this.time = System.currentTimeMillis(); }
        boolean isExpired(long ttl) { return System.currentTimeMillis() - time > ttl; }
    }
}