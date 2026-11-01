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

    /**
     * PIP-1（v11）：掃描無障礙視窗，判斷指定套件是否有「可見的應用視窗」。
     * 用途：平板子母畫面（PiP）／分割畫面時，觀看 App 不在前台但視窗仍在畫面上、
     * 影片續播；若只看前台訊號，計時會誤判離開而暫停，形成繞過路徑。
     * 命中條件：TYPE_APPLICATION 視窗、根節點套件相符、視窗面積達螢幕 3% 以上
     * （PiP 小窗約 5–10%、分割畫面約 50%；門檻取 3% 排除通知小圖示等雜訊）。
     * 全螢幕前台時同樣會命中，但呼叫端只把它當前台訊號的備援，不影響原判定。
     */
    public boolean isPkgWindowVisible(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            long screenArea = (long) dm.widthPixels * dm.heightPixels;
            if (screenArea <= 0) {
                return false;
            }
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root == null) {
                    continue;
                }
                CharSequence p = root.getPackageName();
                boolean match = p != null && pkg.equals(p.toString());
                root.recycle();
                if (!match) {
                    continue;
                }
                android.graphics.Rect b = new android.graphics.Rect();
                w.getBoundsInScreen(b);
                long area = (long) b.width() * b.height();
                if (area * 100 >= screenArea * 3) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 無障礙視窗列舉失敗時視為不可見，計時退回 FG-3／FG-5 既有路徑（fail-open 一致）
        }
        return false;
    }

    /** 以無障礙服務執行 HOME（把觀看 App 送到背景、停止播放）。 */
    public static boolean performHome() {
        TvAccessibilityService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
