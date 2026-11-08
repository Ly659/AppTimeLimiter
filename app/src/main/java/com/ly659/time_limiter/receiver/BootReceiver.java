package com.ly659.time_limiter.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ly659.time_limiter.data.LimitRepository;
import com.ly659.time_limiter.service.GuardService;

/**
 * 开机 / 应用更新后自动恢复守护。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i("BootReceiver", "action=" + action);
        LimitRepository.get().init(context);
        if (LimitRepository.get().isGuardEnabled() && LimitRepository.get().hasAnyRule()) {
            GuardService.start(context);
        }
    }
}
