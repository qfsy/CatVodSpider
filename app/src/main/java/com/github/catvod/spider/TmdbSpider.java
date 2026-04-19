package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.crawler.Spider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;

public class TmdbSpider extends Spider {

    private String dynamicApiKey = "";
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
            this.dynamicApiKey = extend.trim();
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

        String sort = extend.getOrDefault("sort", "popularity.desc");
        if ("vote_average.desc".equals(sort)) {
            return getHighScorePage(tid, networkId, page);
        }

        Map<String, String> params = new HashMap<>();
        params.put("with_networks", networkId);
        params.put("language", "zh-CN");
        params.put("page", String.valueOf(page));
        params.put("sort_by", sort);

        JSONObject res = fetchTmdb("/discover/tv", params);
        JSONArray results = res.optJSONArray("results");
        return new JSONObject()
                .put("page", page)
                .put("pagecount", res.optInt("total_pages", page))
                .put("list", parallelProcessList(results, "tv")).toString();
    }

    private String getHighScorePage(String tid, String networkId, int page) throws Exception {
        String cacheKey = "highscore_" + tid;
        CacheEntry<JSONArray> entry = poolCache.get(cacheKey);
        JSONArray fullList;

        if (entry == null || entry.isExpired(600_000)) {
            List<Future<JSONArray>> futures = new ArrayList<>();
            for (int i = 1; i <= 10; i++) { 
                final int p = i;
                futures.add(executor.submit(() -> {
                    Map<String, String> params = new HashMap<>();
                    params.put("with_networks", networkId);
                    params.put("language", "zh-CN");
                    params.put("page", String.valueOf(p));
                    params.put("vote_count.gte", "150");
                    JSONObject r = fetchTmdb("/discover/tv", params);
                    return r.optJSONArray("results");
                }));
            }
            List<JSONObject> allItems = new ArrayList<>();
            for (Future<JSONArray> f : futures) {
                JSONArray arr = f.get();
                if (arr != null) for (int j = 0; j < arr.length(); j++) allItems.add(arr.getJSONObject(j));
            }
            allItems.sort((a, b) -> Double.compare(b.optDouble("vote_average"), a.optDouble("vote_average")));
            fullList = new JSONArray(allItems);
            poolCache.put(cacheKey, new CacheEntry<>(fullList));
        } else {
            fullList = entry.value;
        }

        int start = (page - 1) * 20;
        int end = Math.min(start + 20, fullList.length());
        JSONArray slice = new JSONArray();
        for (int i = start; i < end; i++) slice.put(fullList.get(i));

        return new JSONObject()
                .put("page", page)
                .put("pagecount", (int) Math.ceil(fullList.length() / 20.0))
                .put("list", parallelProcessList(slice, "tv")).toString();
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
        String type = parts[0];
        String tmdbId = parts[1];

        Map<String, String> params = new HashMap<>();
        params.put("language", "zh-CN");
        params.put("append_to_response", "credits");
        JSONObject data = fetchTmdb("/" + type + "/" + tmdbId, params);
        if (data == null) return "";

        // 解析演职员（参考 Bili.java 的逻辑增强数据丰富度）
        String actor = "未知";
        String director = "未知";
        JSONObject credits = data.optJSONObject("credits");
        if (credits != null) {
            JSONArray cast = credits.optJSONArray("cast");
            if (cast != null) {
                List<String> actors = new ArrayList<>();
                for (int i = 0; i < Math.min(cast.length(), 5); i++) actors.add(cast.getJSONObject(i).optString("name"));
                actor = actors.isEmpty() ? "未知" : TextUtils.join(" / ", actors);
            }
            JSONArray crew = credits.optJSONArray("crew");
            if (crew != null) {
                for (int i = 0; i < crew.length(); i++) {
                    if ("Director".equals(crew.getJSONObject(i).optString("job"))) {
                        director = crew.getJSONObject(i).optString("name");
                        break;
                    }
                }
            }
        }

        String title = data.optString("name", data.optString("title"));
        JSONObject vod = new JSONObject();
        
        // --- 核心改动：规范化字段名与类型，对齐 OK 影视解析器 ---
        vod.put("vod_id", ids.get(0));
        vod.put("vod_name", title);
        vod.put("vod_pic", TMDB_DETAIL_IMG + data.optString("poster_path"));
        vod.put("type_name", type.equals("tv") ? "电视剧" : "电影");
        vod.put("vod_year", data.optString("first_air_date", data.optString("release_date", "    ")).substring(0, 4));
        vod.put("vod_remarks", "⭐" + String.format("%.1f", data.optDouble("vote_average")));
        vod.put("vod_actor", actor);
        vod.put("vod_director", director);
        vod.put("vod_content", data.optString("overview", "暂无简介").trim());
        
        // 模仿 Bili.java 的播放源结构
        vod.put("vod_play_from", "TMDB_Search");
        vod.put("vod_play_url", "🔍 全网资源搜索$" + "search://" + title);

        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id.startsWith("search://")) {
            String keyword = id.substring(9);
            return new JSONObject().put("parse", 0).put("url", "").put("key", keyword).put("search", 1).toString();
        }
        return new JSONObject().put("parse", 0).put("url", id).toString();
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
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", UA);
        conn.setConnectTimeout(3000);
        Thread.sleep(new Random().nextInt(40) + 10);
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder res = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) res.append(line);
        in.close();
        return new JSONObject(res.toString());
    }

    private static class CacheEntry<T> {
        final T value; final long time;
        CacheEntry(T v) { this.value = v; this.time = System.currentTimeMillis(); }
        boolean isExpired(long ttl) { return System.currentTimeMillis() - time > ttl; }
    }
}