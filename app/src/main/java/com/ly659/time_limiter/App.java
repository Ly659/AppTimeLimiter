package com.ly659.time_limiter;

import android.app.Application;
import android.util.Log;

import com.ly659.time_limiter.data.LimitRepository;
import com.ly659.time_limiter.service.GuardService;
import com.ly659.time_limiter.util.NotificationHelper;

public class App extends Application {

    private static final String TAG = "AppTimeLimiter";
    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationHelper.createChannels(this);
        // 预热仓库，触发一次数据加载
        LimitRepository.get().init(this);
        Log.i(TAG, "App started");
    }

    /**
     * 若守护已开启，则在进程重启后恢复后台守护（例如被系统回收后重新启动）。
     */
    public void ensureGuardRunning() {
        if (LimitRepository.get().isGuardEnabled() && !GuardService.isRunning()) {
            GuardService.start(this);
        }
    }

    public static App get() {
        return instance;
    }
}
