package com.ly659.time_limiter.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已安装应用查询工具。
 */
public class AppInfoUtil {

    public static class InstalledApp implements Comparable<InstalledApp> {
        public String packageName;
        public String label;
        public Drawable icon;
        public boolean system;

        @Override
        public int compareTo(InstalledApp o) {
            if (system != o.system) return system ? 1 : -1;
            return label.compareToIgnoreCase(o.label);
        }
    }

    /** 获取所有可启动的已安装应用（排除自身） */
    public static List<InstalledApp> getLaunchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<InstalledApp> result = new ArrayList<>();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String self = ctx.getPackageName();
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(self)) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            InstalledApp item = new InstalledApp();
            item.packageName = ai.packageName;
            item.label = pm.getApplicationLabel(ai).toString();
            item.icon = pm.getApplicationIcon(ai);
            item.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            result.add(item);
        }
        Collections.sort(result);
        return result;
    }

    public static String getLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    public static Drawable getIcon(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static boolean isLauncher(String pkg) {
        return "com.android.launcher".equals(pkg)
                || "com.android.launcher3".equals(pkg)
                || pkg.contains("launcher");
    }
}
