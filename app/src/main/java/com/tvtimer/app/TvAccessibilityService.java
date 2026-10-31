package com.tvtimer.app;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * 前台 App 偵測：只聽 TYPE_WINDOW_STATE_CHANGED，把前台套件名寫入
 * ForegroundTracker 並通知 WatchdogService 重新評估。
 * 忽略：自己（gate 懸浮窗／MainActivity）、systemui（音量條等）、
 * 輸入法視窗（在 YouTube 內搜尋時不該把計時暫停）。
 */
public class TvAccessibilityService extends AccessibilityService {

    private static volatile TvAccessibilityService instance;

    public static TvAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        WatchdogService.notifyForegroundChanged(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence cs = event.getPackageName();
        if (cs == null) {
            return;
        }
        String pkg = cs.toString();
        if (pkg.equals(getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.contains("inputmethod")) {
            return;
        }
        ForegroundTracker.update(pkg, SystemClock.elapsedRealtime());
        WatchdogService.notifyForegroundChanged(this);
    }

    @Override
    public void onInterrupt() {
        // 不需要處理
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    /** 以無障礙服務執行 HOME（把觀看 App 送到背景、停止播放）。 */
    public static boolean performHome() {
        TvAccessibilityService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
