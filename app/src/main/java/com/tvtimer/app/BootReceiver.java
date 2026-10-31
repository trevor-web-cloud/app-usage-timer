package com.tvtimer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 開機完成後啟動看門狗服務（時段與每日累計都存在 Prefs，不會遺失）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            WatchdogService.start(context);
        }
    }
}
