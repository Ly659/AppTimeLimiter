package com.ly659.time_limiter.data;

/**
 * 单个 App 在「当前这一天」的使用状态。
 */
public class UsageState {

    /** 属于哪一天，格式 yyyyMMdd */
    public String day;
    /** 今天已累计使用时长（毫秒） */
    public long usedMs;
    /** 冷却结束时间戳（0 表示没有冷却） */
    public long cooldownUntil;
    /** 本轮计时开始的时间戳，0 表示当前没在计时 */
    public long sessionStart;

    public UsageState(String day) {
        this.day = day;
    }

    public boolean isCoolingDown(long now) {
        return cooldownUntil > now;
    }

    public long remainingCooldown(long now) {
        return Math.max(0, cooldownUntil - now);
    }
}
