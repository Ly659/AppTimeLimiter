package com.ly659.time_limiter.util;

import java.util.Locale;

/**
 * 时间格式化工具。
 */
public class TimeFormat {

    /** 毫秒 -> mm:ss 或 h:mm:ss */
    public static String hms(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** 毫秒 -> “30 分钟” / “1 小时 20 分钟” */
    public static String human(long ms) {
        long totalMin = Math.round(ms / 60000.0);
        if (totalMin < 60) {
            return totalMin + " 分钟";
        }
        long h = totalMin / 60;
        long m = totalMin % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分钟";
    }
}
