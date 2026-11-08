package com.ly659.time_limiter.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.ly659.time_limiter.service.GuardService;
import com.ly659.time_limiter.util.NotificationHelper;

/**
 * 处理通知栏上的按钮点击。
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (NotificationHelper.ACTION_STOP_TIMING.equals(action)) {
            GuardService.finishSession(context);
        }
    }
}
