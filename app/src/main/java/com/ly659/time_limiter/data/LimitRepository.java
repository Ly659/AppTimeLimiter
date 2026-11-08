package com.ly659.time_limiter.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则与用量的统一存取层（SharedPreferences）。
 * 所有方法都是同步的、内存优先的，可以安全地在服务线程中调用。
 */
public class LimitRepository {

    private static final String PREF = "time_limiter_prefs";
    private static final String KEY_RULES = "rules";
    private static final String KEY_USAGE_PREFIX = "usage_";
    private static final String KEY_GUARD_ENABLED = "guard_enabled";
    private static final String KEY_LAST_DAY = "last_day";

    private static final LimitRepository INSTANCE = new LimitRepository();

    public static LimitRepository get() {
        return INSTANCE;
    }

    private SharedPreferences prefs;
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, UsageState> usageCache = new HashMap<>();
    private String today;
    private boolean loaded;

    public synchronized void init(Context ctx) {
        if (loaded) return;
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        today = dayKey(System.currentTimeMillis());
        rules.clear();
        rules.addAll(Rule.listFromJson(prefs.getString(KEY_RULES, "[]")));
        rollOverIfNeeded();
        loaded = true;
    }

    private void ensureInit(Context ctx) {
        if (!loaded) init(ctx);
    }

    // ---------------------------------------------------------------- 日期

    public static String dayKey(long timeMillis) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return fmt.format(timeMillis);
    }

    /** 凌晨 3 点作为一天的边界，避免熬夜使用时被“半夜清零”打断 */
    private static final int DAY_BOUNDARY_HOUR = 3;

    public static String logicalDay(long timeMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMillis);
        if (c.get(Calendar.HOUR_OF_DAY) < DAY_BOUNDARY_HOUR) {
            c.add(Calendar.DAY_OF_YEAR, -1);
        }
        return dayKey(c.getTimeInMillis());
    }

    private synchronized void rollOverIfNeeded() {
        String current = logicalDay(System.currentTimeMillis());
        if (!current.equals(today)) {
            today = current;
            usageCache.clear();
        }
        prefs.edit().putString(KEY_LAST_DAY, today).apply();
    }

    // ---------------------------------------------------------------- 规则

    public synchronized List<Rule> getRules() {
        return new ArrayList<>(rules);
    }

    public synchronized Rule findRule(String pkg) {
        for (Rule r : rules) {
            if (r.packageName.equals(pkg)) return r;
        }
        return null;
    }

    public synchronized boolean hasAnyRule() {
        for (Rule r : rules) {
            if (r.enabled) return true;
        }
        return false;
    }

    public synchronized void addOrUpdateRule(Rule rule) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).packageName.equals(rule.packageName)) {
                rules.set(i, rule);
                persistRules();
                return;
            }
        }
        rules.add(rule);
        persistRules();
    }

    public synchronized void removeRule(String pkg) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).packageName.equals(pkg)) {
                rules.remove(i);
                break;
            }
        }
        persistRules();
    }

    public synchronized void setRuleEnabled(String pkg, boolean enabled) {
        Rule r = findRule(pkg);
        if (r != null) {
            r.enabled = enabled;
            persistRules();
        }
    }

    private void persistRules() {
        prefs.edit().putString(KEY_RULES, Rule.listToJson(rules)).apply();
    }

    // ---------------------------------------------------------------- 守护开关

    public synchronized boolean isGuardEnabled() {
        return prefs != null && prefs.getBoolean(KEY_GUARD_ENABLED, false);
    }

    public synchronized void setGuardEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GUARD_ENABLED, enabled).apply();
    }

    // ---------------------------------------------------------------- 用量

    private synchronized UsageState loadState(Context ctx, String pkg) {
        ensureInit(ctx);
        rollOverIfNeeded();
        UsageState s = usageCache.get(pkg);
        if (s != null) return s;
        String raw = prefs.getString(KEY_USAGE_PREFIX + pkg, null);
        s = new UsageState(today);
        if (raw != null) {
            String[] parts = raw.split("\\|");
            try {
                if (parts.length >= 1) s.usedMs = Long.parseLong(parts[0]);
                if (parts.length >= 2) s.cooldownUntil = Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
            }
        }
        usageCache.put(pkg, s);
        return s;
    }

    private synchronized void saveState(String pkg, UsageState s) {
        s.day = today;
        usageCache.put(pkg, s);
        prefs.edit()
                .putString(KEY_USAGE_PREFIX + pkg, s.usedMs + "|" + s.cooldownUntil)
                .apply();
    }

    public synchronized UsageState getState(Context ctx, String pkg) {
        return loadState(ctx, pkg);
    }

    public synchronized long getUsedMs(Context ctx, String pkg) {
        return loadState(ctx, pkg).usedMs;
    }

    public synchronized long getRemainingMs(Context ctx, String pkg) {
        Rule r = findRule(pkg);
        if (r == null) return 0;
        long used = loadState(ctx, pkg).usedMs;
        return Math.max(0, r.dailyLimitMs - used);
    }

    /** 累加使用时长（毫秒） */
    public synchronized void addUsage(Context ctx, String pkg, long deltaMs) {
        if (deltaMs <= 0) return;
        UsageState s = loadState(ctx, pkg);
        s.usedMs += deltaMs;
        saveState(pkg, s);
    }

    /** 重置今日用量 */
    public synchronized void resetUsage(Context ctx, String pkg) {
        UsageState s = loadState(ctx, pkg);
        s.usedMs = 0;
        s.cooldownUntil = 0;
        saveState(pkg, s);
    }

    /** 进入冷却期 */
    public synchronized void setCooldown(Context ctx, String pkg, long untilMillis) {
        UsageState s = loadState(ctx, pkg);
        s.cooldownUntil = untilMillis;
        saveState(pkg, s);
    }

    public synchronized void clearCooldown(Context ctx, String pkg) {
        UsageState s = loadState(ctx, pkg);
        s.cooldownUntil = 0;
        saveState(pkg, s);
    }

    /** 清空所有 App 的今日用量（调试/手动重置用） */
    public synchronized void resetAllUsage(Context ctx) {
        for (Rule r : rules) {
            resetUsage(ctx, r.packageName);
        }
    }
}
