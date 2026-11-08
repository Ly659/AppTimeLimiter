package com.ly659.time_limiter.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ly659.time_limiter.R;
import com.ly659.time_limiter.receiver.NotificationActionReceiver;
import com.ly659.time_limiter.ui.MainActivity;

/**
 * 通知构建与发送。
 */
public class NotificationHelper {

    public static final String CH_GUARD = "guard_status";   // 常驻计时通知
    public static final String CH_ALERT = "time_up";        // 超时提醒

    public static final int ID_GUARD = 1001;
    public static final int ID_ALERT = 1002;

    public static final String ACTION_STOP_TIMING = "com.ly659.time_limiter.STOP_TIMING";

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel guard = new NotificationChannel(
                CH_GUARD, ctx.getString(R.string.ch_guard), NotificationManager.IMPORTANCE_LOW);
        guard.setDescription(ctx.getString(R.string.ch_guard_desc));
        guard.setShowBadge(false);
        guard.setSound(null, null);
        nm.createNotificationChannel(guard);

        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription(ctx.getString(R.string.ch_alert_desc));
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);
    }

    private static PendingIntent openApp(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 构建后台守护常驻通知。
     *
     * @param appLabel   当前正在使用的目标 App 名称
     * @param remaining  剩余时长（毫秒）
     * @param total      今日总额度（毫秒）
     * @param limitShown 秒级刷新模式（true 显示 mm:ss）
     */
    public static Notification buildGuardNotification(Context ctx, String appLabel,
                                                      long remaining, long total,
                                                      boolean limitShown) {
        int percent = total <= 0 ? 0 : (int) Math.min(100, remaining * 100 / total);
        String title = appLabel == null
                ? ctx.getString(R.string.notif_idle_title)
                : ctx.getString(R.string.notif_using_title, appLabel);
        String text = appLabel == null
                ? ctx.getString(R.string.notif_idle_text)
                : ctx.getString(R.string.notif_remaining, TimeFormat.hms(remaining));

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_GUARD)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        if (appLabel != null) {
            b.setProgress(100, percent, false);
            if (!limitShown && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                b.setSubText(TimeFormat.hms(remaining));
            }
            Intent stop = new Intent(ctx, NotificationActionReceiver.class)
                    .setAction(ACTION_STOP_TIMING);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new NotificationCompat.Action.Builder(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(
                            ctx, R.drawable.ic_stop),
                    ctx.getString(R.string.action_finish_early), pi).build());
        }
        return b.build();
    }

    /** 超时提醒通知 */
    public static void showTimeUpNotification(Context ctx, String packageName,
                                              String appLabel, long cooldownMs) {
        Intent i = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("focus_pkg", packageName);
        PendingIntent pi = PendingIntent.getActivity(ctx, 10, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String cd = cooldownMs > 0 ? TimeFormat.human(cooldownMs) : "";
        String text = TextUtils.isEmpty(cd)
                ? ctx.getString(R.string.notif_timeup_text, appLabel)
                : ctx.getString(R.string.notif_timeup_text_cooldown, appLabel, cd);

        Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(ctx.getString(R.string.notif_timeup_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(ID_ALERT, n);
        } catch (SecurityException ignored) {
            // 未授予通知权限
        }
    }

    public static void cancel(Context ctx, int id) {
        NotificationManagerCompat.from(ctx).cancel(id);
    }
}
