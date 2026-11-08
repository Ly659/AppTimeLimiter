package com.ly659.time_limiter.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.ly659.time_limiter.service.GuardService;

/**
 * 权限引导：跳转到系统的授权页面。
 */
public class PermissionUtil {

    /** 是否有“使用情况访问权限”（前台检测的核心） */
    public static boolean hasUsageAccess(Context ctx) {
        return GuardService.hasUsageStatsPermission(ctx);
    }

    /** 跳转到“使用情况访问权限”设置页 */
    public static boolean requestUsageAccess(Activity activity, int requestCode) {
        try {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(i, requestCode);
            return true;
        } catch (Exception e) {
            try {
                activity.startActivityForResult(
                        new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), requestCode);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /** 是否有悬浮窗权限（用于超时弹窗） */
    public static boolean hasOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(ctx);
    }

    public static void requestOverlay(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(i, requestCode);
        } catch (Exception ignored) {
        }
    }

    /** 跳转到本应用的电池优化白名单设置 */
    public static void requestIgnoreBatteryOptimization(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    /** 各大 ROM 的自启动管理页（尽力而为，找不到就打开应用详情页） */
    public static void openAutoStartSettings(Activity activity) {
        String[][] candidates = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
        };
        for (String[] c : candidates) {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(c[0], c[1]));
            try {
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        // 兜底：应用详情页
        Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(detail);
        } catch (Exception ignored) {
        }
    }

    /** 通知权限（Android 13+） */
    public static boolean hasPostNotification(Context ctx) {
        return androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    /** 请求通知权限，返回需要 requestPermissions 的权限数组 */
    public static String[] notificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{"android.permission.POST_NOTIFICATIONS"};
        }
        return new String[0];
    }
}
