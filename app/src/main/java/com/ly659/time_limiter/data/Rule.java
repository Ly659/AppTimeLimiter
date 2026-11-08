package com.ly659.time_limiter.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 此类的实例用于存储单个被限制 App 的配置。
 */
public class Rule {

    public String packageName;
    /** 显示名（缓存，避免每次重新查询） */
    public String appLabel;
    /** 每天允许使用的总时长（毫秒） */
    public long dailyLimitMs;
    /** 强制关闭后，多久之内不允许再次打开（毫秒） */
    public long cooldownMs;
    public boolean enabled = true;

    public Rule() {
    }

    public Rule(String packageName, String appLabel, long dailyLimitMs, long cooldownMs) {
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.dailyLimitMs = dailyLimitMs;
        this.cooldownMs = cooldownMs;
        this.enabled = true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("packageName", packageName);
        o.put("appLabel", appLabel);
        o.put("dailyLimitMs", dailyLimitMs);
        o.put("cooldownMs", cooldownMs);
        o.put("enabled", enabled);
        return o;
    }

    public static Rule fromJson(JSONObject o) {
        Rule r = new Rule();
        r.packageName = o.optString("packageName");
        r.appLabel = o.optString("appLabel");
        r.dailyLimitMs = o.optLong("dailyLimitMs", 30 * 60 * 1000L);
        r.cooldownMs = o.optLong("cooldownMs", 10 * 60 * 1000L);
        r.enabled = o.optBoolean("enabled", true);
        return r;
    }

    public static String listToJson(java.util.List<Rule> rules) {
        JSONArray arr = new JSONArray();
        for (Rule r : rules) {
            try {
                arr.put(r.toJson());
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    public static java.util.List<Rule> listFromJson(String json) {
        java.util.List<Rule> list = new java.util.ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Rule r = fromJson(arr.getJSONObject(i));
                if (r.packageName != null && !r.packageName.isEmpty()) list.add(r);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}
