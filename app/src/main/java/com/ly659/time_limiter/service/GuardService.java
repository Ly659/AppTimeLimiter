package com.ly659.time_limiter.service;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.ly659.time_limiter.R;
import com.ly659.time_limiter.data.LimitRepository;
import com.ly659.time_limiter.data.Rule;
import com.ly659.time_limiter.data.UsageState;
import com.ly659.time_limiter.ui.MainActivity;
import com.ly659.time_limiter.util.AppInfoUtil;
import com.ly659.time_limiter.util.NotificationHelper;
import com.ly659.time_limiter.util.TimeFormat;

/**
 * 守护服务：
 * 1. 轮询前台应用，发现目标 App 时开始计时
 * 2. 常驻通知显示剩余时间（秒级刷新，带手动结束按钮）
 * 3. 时长用尽时弹窗提醒并强制退出目标 App，进入冷却期
 */
public class GuardService extends Service {

    private static final String TAG = "GuardService";
    public static final String ACTION_START = "com.ly659.time_limiter.action.START";
    public static final String ACTION_STOP = "com.ly659.time_limiter.action.STOP";
    public static final String ACTION_FINISH_SESSION = "com.ly659.time_limiter.action.FINISH_SESSION";
    public static final String ACTION_TICK_ONCE = "com.ly659.time_limiter.action.TICK_ONCE";

    /** 前台检测轮询间隔 */
    private static final long POLL_INTERVAL_MS = 700;
    /** 通知刷新间隔（秒级） */
    private static final long NOTIFY_INTERVAL_MS = 1000;
    /** 一次 tick 中最多计入的时长，防止休眠唤醒后被一次性计入 */
    private static final long MAX_TICK_MS = 3000;
    /** 与 UsageStats 对账的时间窗口（必须大于对账间隔 15s） */
    private static final long REALIGN_WINDOW_MS = 60 * 1000L;

    private static volatile boolean running = false;

    private Handler handler;
    private LimitRepository repo;
    private UsageStatsManager usm;
    private ActivityManager am;
    private WindowManager windowManager;

    /** 当前正在使用的目标 App（null 表示没有） */
    private String currentPkg;
    private String currentLabel;
    private long sessionStartElapsed;
    private long lastRealignMs;
    private long lastNotifyMs;
    private long lastLoggedUsageMs;
    private String lastForeground;

    private View overlayView;
    private boolean handlerInitPermission;
    /** 用户是否已手动关掉超时弹窗（避免每轮 tick 重复弹出） */
    private boolean overlayUserClosed;
    /** 上一次真正发起拦截的时间，用于限制重复触发 */
    private long lastBlockAt;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, GuardService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, GuardService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }

    /** 通知栏按钮：提前结束本轮计时 */
    public static void finishSession(Context ctx) {
        Intent i = new Intent(ctx, GuardService.class).setAction(ACTION_FINISH_SESSION);
        ctx.startService(i);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        repo = LimitRepository.get();
        repo.init(this);
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        checkHandlerInitPermission();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (ACTION_FINISH_SESSION.equals(action)) {
            finishCurrentSession(true);
            return START_STICKY;
        }

        // 先进入前台，避免 ANR / 被系统拒绝
        Notification n = NotificationHelper.buildGuardNotification(this, null, 0, 0, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.ID_GUARD, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NotificationHelper.ID_GUARD, n);
        }

        if (!running) {
            running = true;
            Log.i(TAG, "service started");
            handler.removeCallbacks(tickRunnable);
            handler.post(tickRunnable);
        }
        if (ACTION_TICK_ONCE.equals(action)) {
            handler.post(tickRunnable);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (currentPkg != null) {
            commitUsage();
        }
        dismissOverlay();
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 用户从最近任务划掉本 App 后，守护应当继续
        if (repo.isGuardEnabled()) {
            Intent restart = new Intent(this, GuardService.class).setAction(ACTION_START);
            PendingIntent pi = PendingIntent.getService(this, 99, restart,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager amgr =
                    (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (amgr != null) {
                amgr.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pi);
            }
        }
    }

    private void stopSelfSafely() {
        if (currentPkg != null) commitUsage();
        running = false;
        dismissOverlay();
        NotificationHelper.cancel(this, NotificationHelper.ID_GUARD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    // ---------------------------------------------------------------- 主循环

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Exception e) {
                Log.e(TAG, "tick error", e);
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void tick() {
        long now = System.currentTimeMillis();
        boolean screenOn = isScreenOn();

        // 屏幕熄灭时不计时：把当前会话的计时基准往后推
        if (!screenOn) {
            if (currentPkg != null) {
                sessionStartElapsed = SystemClock.elapsedRealtime();
            }
        }

        String fg = screenOn ? getForegroundPackage() : null;

        // 1. 累计用量
        if (currentPkg != null && screenOn) {
            long elapsed = SystemClock.elapsedRealtime();
            long tickMs = elapsed - sessionStartElapsed;
            if (tickMs < 0) tickMs = 0;
            tickMs = Math.min(tickMs, MAX_TICK_MS);
            if (tickMs > 0) {
                repo.addUsage(this, currentPkg, tickMs);
            }
            sessionStartElapsed = elapsed;

            // 每 15 秒做一次与 UsageStats 的对账，修正轮询误差
            if (now - lastRealignMs > 15000) {
                realignWithUsageStats(now);
                lastRealignMs = now;
            }
        }

        // 2. 前台应用变化处理
        if (fg != null && !fg.equals(lastForeground)) {
            onForegroundChanged(fg);
            lastForeground = fg;
        }

        // 3. 判断是否该拦截
        // 冷却期内任何时刻再次看到目标 App 都要踢出
        if (fg != null) {
            Rule fgRule = repo.findRule(fg);
            if (fgRule != null && fgRule.enabled) {
                UsageState fgState = repo.getState(this, fg);
                if (fgState.isCoolingDown(now)) {
                    if (!fg.equals(currentPkg)) currentLabel = fgRule.appLabel;
                    forceKick(fg, fgRule);
                    return;
                }
            }
        }
        if (currentPkg != null) {
            Rule rule = repo.findRule(currentPkg);
            if (rule != null) {
                UsageState st = repo.getState(this, currentPkg);
                if (st.usedMs >= rule.dailyLimitMs) {
                    onLimitReached(currentPkg, rule);
                    return;
                }
            }
        }

        // 4. 刷新通知（秒级）
        if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
            lastNotifyMs = now;
            updateNotification();
        }
    }

    private void onForegroundChanged(String fg) {
        Rule rule = repo.findRule(fg);
        boolean target = rule != null && rule.enabled;

        if (!target) {
            if (currentPkg != null) {
                Log.i(TAG, "session end by switch: " + currentPkg);
                commitUsage();
                clearSession();
            }
            updateNotification();
            return;
        }

        long now = System.currentTimeMillis();
        UsageState st = repo.getState(this, fg);

        // 冷却期内打开 → 立即踢出
        if (st.isCoolingDown(now)) {
            forceKick(fg, rule);
            return;
        }
        // 今日额度已用尽 → 立即踢出
        if (st.usedMs >= rule.dailyLimitMs) {
            onLimitReached(fg, rule);
            return;
        }

        // 开始计时
        if (currentPkg != null && !currentPkg.equals(fg)) {
            commitUsage();
        }
        currentPkg = fg;
        currentLabel = rule.appLabel != null ? rule.appLabel : AppInfoUtil.getLabel(this, fg);
        sessionStartElapsed = SystemClock.elapsedRealtime();
        lastRealignMs = now;
        lastLoggedUsageMs = st.usedMs;
        Log.i(TAG, "session start: " + fg + " used=" + st.usedMs);
        updateNotification();
    }

    private void clearSession() {
        currentPkg = null;
        currentLabel = null;
        sessionStartElapsed = 0;
        lastLoggedUsageMs = 0;
        overlayUserClosed = false;
    }

    /** 是否处于亮屏状态 */
    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    private void commitUsage() {
        if (currentPkg == null) return;
        long elapsed = SystemClock.elapsedRealtime();
        long tickMs = Math.max(0, Math.min(elapsed - sessionStartElapsed, MAX_TICK_MS));
        if (tickMs > 0) repo.addUsage(this, currentPkg, tickMs);
        sessionStartElapsed = elapsed;
    }

    /**
     * 与 UsageStats 对账：UsageStats 记录的是真实的前台时长，
     * 取最近 60 秒窗口的差值，把轮询漏计的时长补上（只补正，不扣减）。
     * 注意：窗口必须大于对账间隔，否则两次对账会重复计算同一段时间。
     */
    private void realignWithUsageStats(long now) {
        if (usm == null || currentPkg == null) return;
        long begin = now - REALIGN_WINDOW_MS;
        long statsMs = queryForegroundTime(currentPkg, begin, now);
        long repoNow = repo.getUsedMs(this, currentPkg);
        long repoDelta = repoNow - lastLoggedUsageMs;
        if (repoDelta < 0) return;
        long diff = statsMs - repoDelta;
        // 只有明显漏计才补，且最多补一个窗口长度
        if (diff > 5000) {
            repo.addUsage(this, currentPkg, Math.min(diff, REALIGN_WINDOW_MS));
            Log.i(TAG, "realign +" + diff + "ms for " + currentPkg);
        }
        lastLoggedUsageMs = repo.getUsedMs(this, currentPkg);
    }

    private long queryForegroundTime(String pkg, long begin, long end) {
        try {
            UsageEvents events = usm.queryEvents(begin, end);
            if (events == null) return 0;
            UsageEvents.Event e = new UsageEvents.Event();
            long lastResume = -1;
            long total = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getPackageName() == null || !e.getPackageName().equals(pkg)) continue;
                int type = e.getEventType();
                boolean resume = type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && type == UsageEvents.Event.ACTIVITY_RESUMED);
                boolean pause = type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && type == UsageEvents.Event.ACTIVITY_PAUSED);
                if (resume) {
                    lastResume = e.getTimeStamp();
                } else if (pause && lastResume > 0) {
                    total += Math.max(0, e.getTimeStamp() - lastResume);
                    lastResume = -1;
                }
            }
            if (lastResume > 0) total += Math.max(0, end - lastResume);
            return total;
        } catch (Exception ex) {
            return 0;
        }
    }

    /** 取当前前台应用包名 */
    private String getForegroundPackage() {
        // 方式一：UsageStats（Android 5.0+，需要“使用情况访问权限”）
        if (usm != null) {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 60 * 60 * 1000L, now);
            if (events != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                String latest = null;
                long latestTs = 0;
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    int type = e.getEventType();
                    boolean fgEvent = type == UsageEvents.Event.MOVE_TO_FOREGROUND
                            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED);
                    if (fgEvent && e.getTimeStamp() >= latestTs) {
                        latestTs = e.getTimeStamp();
                        latest = e.getPackageName();
                    }
                }
                if (latest != null) return latest;
            }
        }
        // 方式二：旧接口兜底（Android 5.x 部分机型可用）
        try {
            if (am != null) {
                java.util.List<ActivityManager.RunningAppProcessInfo> procs =
                        am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            return p.processName;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------- 通知刷新

    private void updateNotification() {
        Notification n;
        if (currentPkg != null) {
            Rule rule = repo.findRule(currentPkg);
            long total = rule == null ? 0 : rule.dailyLimitMs;
            long remaining = repo.getRemainingMs(this, currentPkg);
            n = NotificationHelper.buildGuardNotification(this, currentLabel, remaining, total, true);
        } else {
            n = NotificationHelper.buildGuardNotification(this, null, 0, 0, false);
        }
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NotificationHelper.ID_GUARD, n);
        } catch (Exception e) {
            Log.e(TAG, "notify failed", e);
        }
    }

    // ---------------------------------------------------------------- 拦截

    private void onLimitReached(String pkg, Rule rule) {
        long cooldownMs = Math.max(0, rule.cooldownMs);
        repo.setCooldown(this, pkg, System.currentTimeMillis() + cooldownMs);
        String label = currentLabel != null ? currentLabel : AppInfoUtil.getLabel(this, pkg);

        // 先把本轮用量刷盘
        commitUsage();
        clearSession();
        lastBlockAt = System.currentTimeMillis();
        updateNotification();

        kickApp(pkg);
        showTimeUpDialog(label, pkg, cooldownMs);
        NotificationHelper.showTimeUpNotification(this, pkg, label, cooldownMs);
        Log.i(TAG, "limit reached: " + pkg + ", cooldown=" + cooldownMs);
    }

    /** 冷却期内打开 → 踢出。同一 App 的提醒做节流，避免反复骚扰 */
    private void forceKick(String pkg, Rule rule) {
        if (pkg.equals(currentPkg)) clearSession();
        kickApp(pkg);
        long now = System.currentTimeMillis();
        if (now - lastBlockAt < 4000) return; // 4 秒内的重复触发忽略
        lastBlockAt = now;

        long remain = repo.getState(this, pkg).remainingCooldown(now);
        String label = rule != null && rule.appLabel != null
                ? rule.appLabel : AppInfoUtil.getLabel(this, pkg);
        if (!overlayUserClosed) {
            showCooldownDialog(label, remain);
        }
    }

    /** 把目标 App 切到桌面，等效于关闭 */
    private void kickApp(String pkg) {
        try {
            // 1. 尝试 finishAndRemoveTask（部分系统生效）
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "kick failed", e);
        }
        if (pkg != null) lastForeground = null; // 触发下一轮重新判定
    }

    /** 提前结束本轮：用户点通知栏的“结束使用” */
    private void finishCurrentSession(boolean userTriggered) {
        if (currentPkg != null) {
            commitUsage();
            String pkg = currentPkg;
            Rule rule = repo.findRule(pkg);
            if (userTriggered && rule != null) {
                // 手动结束后立即进入冷却，避免“点一下就继续玩”
                repo.setCooldown(this, pkg, System.currentTimeMillis() + rule.cooldownMs);
            }
            String label = currentLabel;
            clearSession();
            updateNotification();
            if (userTriggered) {
                kickApp(pkg);
                NotificationHelper.showTimeUpNotification(this, pkg, label,
                        rule == null ? 0 : rule.cooldownMs);
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗

    private boolean canDrawOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    /**
     * 超时提醒弹窗。
     * Android 10+ 后台无法直接 startActivity，因此：
     * - 有悬浮窗权限：用 WindowManager 直接显示弹窗（体验最好）
     * - 否则：发高优先级通知 + 尝试 startActivity（部分 ROM 允许）
     */
    private void showTimeUpDialog(String appLabel, String pkg, long cooldownMs) {
        String title = appLabel + " 使用时间已用完";
        String msg = cooldownMs > 0
                ? "已为你退出该应用。请等待 " + TimeFormat.human(cooldownMs) + "后再使用。"
                : "已为你退出该应用。今日额度已用完，明天再来吧。";

        if (canDrawOverlay()) {
            showOverlay(title, msg, R.drawable.ic_timeup_bg);
        } else {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra("focus_pkg", pkg);
                i.putExtra("over_limit", true);
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "cannot start activity from background: " + e.getMessage());
            }
        }
    }

    private void showCooldownDialog(String appLabel, long remainMs) {
        showOverlay(appLabel + " 已被限制",
                "还需等待 " + TimeFormat.human(remainMs) + " 才能再次使用。", 0);
    }

    private void showOverlay(String title, String message, int bgRes) {
        if (!canDrawOverlay()) {
            // 没有悬浮窗权限时降级为通知，避免静默失败
            NotificationHelper.showTimeUpNotification(this, null, title, 0);
            return;
        }
        if (overlayView != null) dismissOverlay();
        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            View v = inflater.inflate(R.layout.overlay_timeup,
                    new android.widget.FrameLayout(this), false);
            ((TextView) v.findViewById(R.id.overlay_title)).setText(title);
            ((TextView) v.findViewById(R.id.overlay_message)).setText(message);
            v.findViewById(R.id.overlay_ok).setOnClickListener(x -> {
                overlayUserClosed = true;
                dismissOverlay();
            });

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            // 左右留出边距，让圆角卡片浮在屏幕中央
            lp.width = Math.round(getResources().getDisplayMetrics().widthPixels * 0.88f);
            if (bgRes != 0) v.setBackgroundResource(bgRes);
            windowManager.addView(v, lp);
            overlayView = v;

            handler.postDelayed(this::dismissOverlay, 8000);
        } catch (Exception e) {
            Log.e(TAG, "overlay failed", e);
        }
    }

    private void dismissOverlay() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
    }

    private void checkHandlerInitPermission() {
        handlerInitPermission = hasUsageStatsPermission(this);
    }

    /** 静态工具：是否已获得“使用情况访问权限” */
    public static boolean hasUsageStatsPermission(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.getPackageName());
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return ctx.checkCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 供 UI 查询：当前服务是否具备使用情况权限（实时检测，用户授权后无需重启服务） */
    public boolean hasUsagePermission() {
        return hasUsageStatsPermission(this);
    }
}
