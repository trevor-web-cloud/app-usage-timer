package com.tvtimer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * 看門狗主服務（前台服務）：
 * - 持有 TimerEngine（狀態機）、GateController（懸浮窗攔截頁）、TelegramClient。
 * - 主迴圈每 500ms 跑一次：以前台訊號（無障礙事件為主、UsageStats 交叉驗證）
 *   餵 engine 評估，並呼叫 engine.tick() 推進倒數。
 * - 螢幕關閉（待機）時通知 engine（家長模式依規格在此結束）。
 */
public class WatchdogService extends Service implements TimerEngine.Events, TelegramClient.Listener {

    private static final String TAG = "TvTimerWatchdog";
    public static final String ACTION_PARENT_ON = "com.tvtimer.app.action.PARENT_ON";
    public static final String ACTION_PARENT_OFF = "com.tvtimer.app.action.PARENT_OFF";

    private static final int NOTIF_ID = 4101;
    private static final String CHANNEL_ID = "watchdog";
    private static final long LOOP_MS = 500;

    private static volatile WatchdogService instance;

    private TimerEngine engine;
    private GateController gate;
    private TelegramClient telegram;
    private UsageStatsManager usageStats;
    private Handler mainHandler;
    private String lastDeliveredFg;
    /** FG-3 診斷：上次記下的 UsageStats 最新事件簽名（pkg|type|ts），變化時才印 log。 */
    private String lastUsageEventSig = "";
    /** FG-3 診斷：上次印出狀態 log 的 elapsed 時間（節流 10 秒）。 */
    private long lastStateLogElapsed;
    private BroadcastReceiver screenOffReceiver;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, WatchdogService.class);
            ctx.startForegroundService(i);
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService failed", e);
        }
    }

    /** 供 MainActivity 讀取即時狀態快照；服務不在時回傳 null。 */
    public static TimerEngine.Snapshot liveSnapshot() {
        WatchdogService s = instance;
        if (s == null || s.engine == null) {
            return null;
        }
        return s.engine.snapshot();
    }

    /** 無障礙服務有新訊號時呼叫；服務不在就順手把它拉起來。 */
    public static void notifyForegroundChanged(Context ctx) {
        WatchdogService s = instance;
        if (s != null) {
            final WatchdogService svc = s;
            // 無障礙回呼在 binder 執行緒上；engine 與視窗操作一律回主執行緒
            svc.mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        svc.evaluateForeground();
                    } catch (Exception e) {
                        Log.w(TAG, "evaluateForeground", e);
                    }
                }
            });
        } else {
            start(ctx);
        }
    }

    // ================= lifecycle =================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            // Android 14 對 FGS 類型較嚴格；失敗時降級為一般服務繼續運行
            Log.w(TAG, "startForeground failed; continuing degraded", e);
        }

        Prefs.importTokenIfNeeded(this);

        engine = new TimerEngine(this, this);
        engine.init();
        gate = new GateController(this, engine, new GateController.Callbacks() {
            @Override
            public void onVisibilityChanged(boolean showing) {
                engine.setGateShowing(showing);
            }

            @Override
            public void onPairingConfirmed() {
                confirmPendingPairing();
            }

            @Override
            public void onPairingRejected() {
                Prefs.clearPendingPairing(WatchdogService.this);
                gate.hide();
            }
        });
        telegram = new TelegramClient(this, this);
        telegram.ensureRunning(Prefs.getToken(this));
        usageStats = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

        registerScreenOffReceiver();
        mainHandler.post(loopTask);
        evaluateForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && engine != null) {
            if (ACTION_PARENT_ON.equals(intent.getAction())) {
                engine.startParentModeInternal();
            } else if (ACTION_PARENT_OFF.equals(intent.getAction())) {
                engine.endParentMode();
            }
        }
        // token 可能剛由 MainActivity 匯入，確保 Telegram 跟上
        Prefs.importTokenIfNeeded(this);
        if (telegram != null) {
            telegram.ensureRunning(Prefs.getToken(this));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(loopTask);
        }
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {
            }
            screenOffReceiver = null;
        }
        if (telegram != null) {
            telegram.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================= main loop / foreground =================

    private final Runnable loopTask = new Runnable() {
        @Override
        public void run() {
            try {
                evaluateForeground();
                engine.tick();
                maybeLogState();
            } catch (Exception e) {
                // 看門狗主迴圈絕不能因單次例外而死
                Log.w(TAG, "loop iteration failed", e);
            }
            // gate 頁面內容在顯示期間是靜態的（計時暫停中），不需要定期重畫，
            // 避免遙控器焦點被重畫搶走；狀態變化時 engine 會主動要求重開頁面。
            mainHandler.postDelayed(this, LOOP_MS);
        }
    };

    private void evaluateForeground() {
        crossCheckUsageStats();
        String cur = ForegroundTracker.pkg;
        if (cur == null ? lastDeliveredFg != null : !cur.equals(lastDeliveredFg)) {
            lastDeliveredFg = cur;
            engine.onForegroundChanged(cur);
        }
    }

    /**
     * UsageStats 交叉驗證＋FG-3 心跳維護＋FG-4 權威前台（主迴圈每 500ms 呼叫）：
     * 1) FG-4 權威前台（取代 v2 的 5 秒校正）：取最近 30 分鐘內最後一筆
     *    ACTIVITY_RESUMED 的套件為真實前台，與 tracker 不符就覆寫。
     *    依據 2026-10-31 實測：無障礙視窗事件在播放開始等轉移點會閃入
     *    launcherx（約數百 ms 至數秒）使 tracker 落在桌面、計時凍結；
     *    但 UsageStats 在那些時刻並無 launcher 的 RESUMED（真正的 Home
     *    才有），且安靜播放期間不產生任何新事件——「最後一筆 RESUMED」
     *    因此是本機上穩定且抗閃爍的前台真相。
     * 2) FG-3 心跳：查 session 觀看 App 最近 10 分鐘內的最後一筆事件——
     *    是 ACTIVITY_RESUMED 就持續刷新心跳，是 ACTIVITY_STOPPED 就清除。
     * 3) 最新事件簽名變化時印一行診斷 log（logcat tag TvTimerWatchdog）。
     */
    private void crossCheckUsageStats() {
        if (usageStats == null || engine == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            // FG-4：先用 events 簽名 log 觀察最新事件（窗口沿用 v3 的 30 秒）
            UsageEventInfo latest = latestUsageEvent(30_000);
            if (latest != null) {
                String sig = latest.pkg + "|" + latest.type + "|" + latest.ts;
                if (!sig.equals(lastUsageEventSig)) {
                    lastUsageEventSig = sig;
                    Log.i(TAG, "usage latest: pkg=" + latest.pkg + " type=" + latest.type
                            + " ageMs=" + (now - latest.ts)
                            + " tracker=" + ForegroundTracker.pkg);
                }
            }
            // FG-4 權威前台：最近 30 分鐘最後一筆 ACTIVITY_RESUMED 的套件
            UsageEventInfo fg = latestResumedUsageEvent(30 * 60_000L);
            if (fg != null
                    && !fg.pkg.equals("com.android.systemui")
                    && !fg.pkg.equals(ForegroundTracker.pkg)) {
                ForegroundTracker.usageEventWallMs = fg.ts;
                ForegroundTracker.update(fg.pkg, SystemClock.elapsedRealtime());
                Log.i(TAG, "fg reconcile -> " + fg.pkg
                        + " (resumed ageMs=" + (now - fg.ts) + ")");
            }
            TimerEngine.Snapshot s = engine.snapshot();
            if (s.sessionActive && s.sessionPkg != null && !s.sessionPkg.isEmpty()) {
                UsageEventInfo last = latestUsageEventFor(s.sessionPkg, 10 * 60_000L);
                if (last != null) {
                    if (last.type == UsageEvents.Event.ACTIVITY_RESUMED) {
                        engine.noteForegroundHeartbeat();
                    } else if (last.type == UsageEvents.Event.ACTIVITY_STOPPED) {
                        engine.clearForegroundHeartbeat();
                    }
                }
                // PIP-1（v11）：PiP／分割畫面下 session App 不在前台，UsageStats
                // 也未必有新事件；改以無障礙視窗掃描確認「視窗還在畫面上」
                TvAccessibilityService a11y = TvAccessibilityService.getInstance();
                if (a11y != null && a11y.isPkgWindowVisible(s.sessionPkg)) {
                    engine.noteWatchVisible();
                }
            }
        } catch (Exception ignored) {
            // 沒有 usage access 時 queryEvents 會回空／丟例外，略過即可
        }
    }

    private static final class UsageEventInfo {
        String pkg;
        int type;
        long ts;
    }

    /** 最近 windowMs 內最新的一筆 ACTIVITY_RESUMED／ACTIVITY_STOPPED 事件；無事件回 null。 */
    private UsageEventInfo latestUsageEvent(long windowMs) {
        long now = System.currentTimeMillis();
        UsageEvents events = usageStats.queryEvents(now - windowMs, now);
        if (events == null) {
            return null;
        }
        UsageEvents.Event e = new UsageEvents.Event();
        UsageEventInfo best = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int t = e.getEventType();
            if (t != UsageEvents.Event.ACTIVITY_RESUMED
                    && t != UsageEvents.Event.ACTIVITY_STOPPED) {
                continue;
            }
            if (best == null || e.getTimeStamp() >= best.ts) {
                if (best == null) {
                    best = new UsageEventInfo();
                }
                best.pkg = e.getPackageName();
                best.type = t;
                best.ts = e.getTimeStamp();
            }
        }
        return best;
    }

    /** 最近 windowMs 內最後一筆 ACTIVITY_RESUMED（不限套件）；無事件回 null。FG-4 權威前台用。 */
    private UsageEventInfo latestResumedUsageEvent(long windowMs) {
        long now = System.currentTimeMillis();
        UsageEvents events = usageStats.queryEvents(now - windowMs, now);
        if (events == null) {
            return null;
        }
        UsageEvents.Event e = new UsageEvents.Event();
        UsageEventInfo best = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) {
                continue;
            }
            if (best == null || e.getTimeStamp() >= best.ts) {
                if (best == null) {
                    best = new UsageEventInfo();
                }
                best.pkg = e.getPackageName();
                best.type = e.getEventType();
                best.ts = e.getTimeStamp();
            }
        }
        return best;
    }

    /** 指定套件在最近 windowMs 內的最後一筆 ACTIVITY_RESUMED／ACTIVITY_STOPPED 事件；無事件回 null。 */
    private UsageEventInfo latestUsageEventFor(String pkg, long windowMs) {
        long now = System.currentTimeMillis();
        UsageEvents events = usageStats.queryEvents(now - windowMs, now);
        if (events == null) {
            return null;
        }
        UsageEvents.Event e = new UsageEvents.Event();
        UsageEventInfo best = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (!pkg.equals(e.getPackageName())) {
                continue;
            }
            int t = e.getEventType();
            if (t != UsageEvents.Event.ACTIVITY_RESUMED
                    && t != UsageEvents.Event.ACTIVITY_STOPPED) {
                continue;
            }
            if (best == null || e.getTimeStamp() >= best.ts) {
                if (best == null) {
                    best = new UsageEventInfo();
                }
                best.pkg = e.getPackageName();
                best.type = t;
                best.ts = e.getTimeStamp();
            }
        }
        return best;
    }

    /** FG-3 診斷：每 10 秒把引擎關鍵狀態寫入 logcat（adb logcat -s TvTimerWatchdog）。 */
    private void maybeLogState() {
        if (engine == null) {
            return;
        }
        long nowEl = SystemClock.elapsedRealtime();
        if (nowEl - lastStateLogElapsed < 10_000) {
            return;
        }
        lastStateLogElapsed = nowEl;
        TimerEngine.Snapshot s = engine.snapshot();
        Log.i(TAG, "state active=" + s.sessionActive
                + " counting=" + s.counting
                + " needsResume=" + s.needsResume
                + " fg=" + s.dbgFgPkg
                + " shouldCount=" + s.dbgShouldCount
                + " hbAgeSec=" + s.dbgHeartbeatAgeSec
                + " remainingMs=" + s.remainingMs
                + " todayMs=" + s.todayMs);
    }

    private void registerScreenOffReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && engine != null) {
                    engine.onScreenOff();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
    }

    // ================= TimerEngine.Events =================

    @Override
    public void showGate(int page, int pinPurpose, boolean deniedNote) {
        // FG-6：gate 顯示／關閉全程留 log，下一輪實測可直接驗證攔截是否觸發
        Log.i(TAG, "showGate page=" + page + " fg=" + ForegroundTracker.pkg);
        gate.showPage(page, pinPurpose, deniedNote);
    }

    @Override
    public void showGateSendFailed(int page) {
        gate.showSendFailed(page);
    }

    @Override
    public void hideGate() {
        if (gate.isShowing()) {
            Log.i(TAG, "hideGate page=" + gate.currentPage());
        }
        gate.hide();
    }

    @Override
    public void hideGateIfTransient() {
        if (gate.isShowing() && gate.isTransientPage()) {
            Log.i(TAG, "hideGateIfTransient page=" + gate.currentPage()
                    + " fg=" + ForegroundTracker.pkg);
            gate.hide();
        }
    }

    @Override
    public void showWarning(int secondsLeft) {
        gate.showWarningBanner(secondsLeft);
    }

    @Override
    public void goHome() {
        boolean viaA11y = TvAccessibilityService.performHome();
        Log.i(TAG, "goHome viaA11y=" + viaA11y);
        if (viaA11y) {
            return;
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "goHome fallback failed", e);
        }
    }

    @Override
    public void launchApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) {
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                query.setPackage(pkg);
                List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
                if (found != null && !found.isEmpty()) {
                    ResolveInfo ri = found.get(0);
                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                    intent.setComponent(new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name));
                }
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
            } else {
                Log.w(TAG, "no launch intent for " + pkg);
            }
        } catch (Exception e) {
            // 背景啟動被系統擋下時不致命：時段已開始，倒數只在 App 到前台時才走，
            // 小孩自己點開 App 即可續行（gate 會顯示已開始的狀態）。
            Log.w(TAG, "launchApp failed for " + pkg, e);
        }
    }

    @Override
    public void openMainActivity() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openMainActivity failed", e);
        }
    }

    // ---- Telegram reports (engine -> Telegram) ----

    @Override
    public void tgSessionStarted(int minutes, boolean unlimited, boolean resumed) {
        long chatId = Prefs.getChatId(this);
        if (chatId == 0 || telegram == null) {
            return;
        }
        TimerEngine.Snapshot s = engine.snapshot();
        String text;
        if (resumed) {
            text = L.t(this, "▶️ 繼續上次：剩 ", "▶️ Resumed: ")
                    + (s.unlimited ? L.t(this, "不限制", "unlimited") : Math.max(1, (int) Math.ceil(s.remainingMs / 60000.0)) + L.t(this, " 分鐘", " min left"))
                    + L.t(this, "（今日累計 ", " (today: ") + Math.round(s.todayMs / 60000.0) + L.t(this, " 分鐘）", " min)");
        } else if (unlimited) {
            text = L.t(this, "▶️ 已開始：不限制模式（今日累計 ", "▶️ Started: unlimited mode (today: ")
                    + Math.round(s.todayMs / 60000.0) + L.t(this, " 分鐘）", " min)");
        } else {
            text = L.t(this, "▶️ 已開始看電視：", "▶️ Started watching: ") + minutes + L.t(this, " 分鐘（今日累計 ", " min (today: ")
                    + Math.round(s.todayMs / 60000.0) + L.t(this, " 分鐘）", " min)");
        }
        telegram.sendSessionMessageAsync(chatId, text);
    }

    @Override
    public void tgSessionPaused(int todayMinutes) {
        long chatId = Prefs.getChatId(this);
        if (chatId == 0 || telegram == null) {
            return;
        }
        telegram.sendSessionMessageAsync(chatId,
                L.t(this, "⏸ 已暫停（離開觀看畫面），今日累計 ", "⏸ Paused (left the viewing screen). Today: ") + todayMinutes + L.t(this, " 分鐘。", " min."));
    }

    @Override
    public void tgSessionEnded(int sessionMinutes, int todayMinutes, String reasonZh) {
        long chatId = Prefs.getChatId(this);
        if (chatId == 0 || telegram == null) {
            return;
        }
        telegram.sendTextAsync(chatId, L.t(this, "⏹ 本次結束（", "⏹ Session ended (") + reasonZh + L.t(this, "）：看了 ", "): watched ")
                + sessionMinutes + L.t(this, " 分鐘，今日累計 ", " min, today ") + todayMinutes + L.t(this, " 分鐘。", " min."));
    }

    @Override
    public void tgCapReached() {
        long chatId = Prefs.getChatId(this);
        if (chatId == 0 || telegram == null) {
            return;
        }
        telegram.sendTextAsync(chatId, L.t(this, "📢 今日已達上限 " + Prefs.getDailyCapMin(this) + " 分鐘，之後開局需要爸媽核准。", "📢 Today's " + Prefs.getDailyCapMin(this) + "-minute limit is reached. New sessions now need a parent's approval."));
    }

    @Override
    public void tgParentModeChanged(boolean on) {
        long chatId = Prefs.getChatId(this);
        if (chatId == 0 || telegram == null) {
            return;
        }
        telegram.sendTextAsync(chatId, on
                ? L.t(this, "🔓 家長模式已開啟（2 小時，或至電視待機）。", "🔓 Parent mode is on (2 hours, or until the TV sleeps).")
                : L.t(this, "🔒 家長模式已結束，恢復計時鎖定。", "🔒 Parent mode has ended. The timer lock is back on."));
    }

    @Override
    public void tgSendRequest(final String reqId, int kind, int minutes, String pkg) {
        final long chatId = Prefs.getChatId(this);
        if (chatId == 0 || telegram == null) {
            // 尚未配對／client 不在：等同送出失敗，立即回收 pending 避免卡死（SEC-04）
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    engine.onRequestSendFailed(reqId);
                }
            });
            return;
        }
        TimerEngine.Snapshot s = engine.snapshot();
        String text;
        switch (kind) {
            case TimerEngine.REQ_EXTEND:
                text = L.t(this, "⏰ 時間到了，小孩請求加時。（今日累計 ", "⏰ Time's up — your child is asking for more time. (Today: ")
                        + Math.round(s.todayMs / 60000.0) + L.t(this, " 分鐘）", " min)");
                break;
            case TimerEngine.REQ_NEW_SESSION:
                text = L.t(this, "🙋 小孩請求開局 ", "🙋 Your child is asking to start a ") + minutes + L.t(this, " 分鐘（今日已達上限 ", " min session (today's ") + Prefs.getDailyCapMin(this) + L.t(this, " 分鐘，需核准）。", "-minute limit reached — approval needed).");
                break;
            case TimerEngine.REQ_UNLIMITED:
                text = L.t(this, "🙋 小孩請求不限制模式。", "🙋 Your child is asking for unlimited mode.");
                break;
            case TimerEngine.REQ_PARENT:
                text = L.t(this, "🙋 電視端請求核准家長模式。", "🙋 Parent mode approval requested from the TV.");
                break;
            default:
                text = L.t(this, "🙋 小孩送出一個核准請求。", "🙋 Your child sent an approval request.");
                break;
        }
        telegram.sendApprovalRequest(chatId, reqId, kind, text,
                new TelegramClient.SentCallback() {
                    @Override
                    public void onSent(final long messageId) {
                        // SentCallback 在 Telegram 的 io 執行緒上觸發；engine 操作回主執行緒
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (messageId > 0) {
                                    Prefs.setPendMsgId(WatchdogService.this, messageId);
                                } else {
                                    // 送出失敗（含例外路徑）：回收 pending 讓小孩能重試（SEC-04）
                                    engine.onRequestSendFailed(reqId);
                                }
                            }
                        });
                    }
                });
    }

    @Override
    public void tgRequestResolved(String outcomeText) {
        long chatId = Prefs.getChatId(this);
        long msgId = Prefs.getPendMsgId(this);
        if (chatId != 0 && telegram != null) {
            telegram.editTextAsync(chatId, msgId, outcomeText);
        }
    }

    @Override
    public void tgPlain(String text) {
        long chatId = Prefs.getChatId(this);
        if (chatId != 0 && telegram != null) {
            telegram.sendTextAsync(chatId, text);
        }
    }

    // ================= TelegramClient.Listener =================

    @Override
    public void onGroupMessage(final long chatId, final String chatType,
                               final String chatTitle, final String fromName,
                               final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                long bound = Prefs.getChatId(WatchdogService.this);
                if (bound != 0) {
                    if (bound != chatId) {
                        return;
                    }
                    if ("/status".equals(text == null ? "" : text.trim())) {
                        if (telegram != null) {
                            telegram.sendTextAsync(chatId, statusText());
                        }
                    }
                    return;
                }
                // 未綁定：兩步配對（SEC-01）——先登記為「待確認配對」，
                // 等家長在 TV 端（gate 或設定頁）確認後才真正綁定。
                // 有效期限內第一個提出請求的群組優先，其他群組的訊息不覆蓋它。
                if (Prefs.pendingPairingId(WatchdogService.this) == 0) {
                    Prefs.savePendingPairing(WatchdogService.this, chatId, chatTitle);
                    if (gate != null && gate.isShowing()) {
                        gate.showPage(GateController.PAGE_PAIRING, 0, false);
                    }
                }
            }
        });
    }

    @Override
    public void onChatMigrated(final long oldChatId, final long newChatId) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                // 群組升級為 supergroup（SEC-06）：若遷移的正是已綁定群組，
                // 自動把綁定改到新 id（群組名稱不變），並在新群組發一則通知；
                // 後續所有送訊息都即時讀 Prefs 的 chat id，自然改走新 id。
                if (Prefs.getChatId(WatchdogService.this) != oldChatId) {
                    return;
                }
                Prefs.setChatId(WatchdogService.this, newChatId);
                if (telegram != null) {
                    telegram.sendTextAsync(newChatId,
                            L.t(WatchdogService.this, "📢 群組已升級，已自動重新綁定到這個群組，配對延續不中斷。", "📢 This group was upgraded and pairing was automatically moved here. It continues without interruption."));
                }
            }
        });
    }

    /**
     * 確認待確認配對並完成綁定（gate 與 MainActivity 共用）：
     * 寫入 chat id 與群組名稱、清除待確認紀錄、向群組發配對成功訊息。
     * 須在主執行緒呼叫。
     */
    private void confirmPendingPairing() {
        long pairId = Prefs.pendingPairingId(this);
        if (pairId == 0) {
            if (gate != null) {
                gate.hide();
            }
            return;
        }
        Prefs.setChatId(this, pairId);
        Prefs.setChatTitle(this, Prefs.getPairTitle(this));
        Prefs.clearPendingPairing(this);
        if (telegram != null) {
            telegram.sendTextAsync(pairId,
                    L.t(this, "✅ 配對成功！看電視計時的回報與核准會送到這個群組。", "✅ Paired! TV Timer reports and approvals will be sent to this group."));
        }
        if (gate != null && gate.isShowing()
                && gate.currentPage() == GateController.PAGE_PAIRING) {
            gate.hide();
        }
    }

    /** 供 MainActivity 的配對確認按鈕呼叫；服務不在時仍完成綁定（成功訊息略過）。 */
    public static void confirmPairingFromUi(Context ctx) {
        WatchdogService s = instance;
        if (s != null) {
            s.confirmPendingPairing();
            return;
        }
        long pairId = Prefs.pendingPairingId(ctx);
        if (pairId != 0) {
            Prefs.setChatId(ctx, pairId);
            Prefs.setChatTitle(ctx, Prefs.getPairTitle(ctx));
            Prefs.clearPendingPairing(ctx);
        }
    }

    /** 供 MainActivity 的配對拒絕按鈕呼叫。 */
    public static void rejectPairingFromUi(Context ctx) {
        Prefs.clearPendingPairing(ctx);
        WatchdogService s = instance;
        if (s != null && s.gate != null && s.gate.isShowing()
                && s.gate.currentPage() == GateController.PAGE_PAIRING) {
            s.gate.hide();
        }
    }

    @Override
    public void onCallback(final String callbackId, final long chatId, final long messageId,
                           final String data, final String fromName) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                long bound = Prefs.getChatId(WatchdogService.this);
                if (bound == 0 || chatId != bound || data == null) {
                    return; // 未配對或其他群組的按鈕，一律忽略
                }
                if (data.startsWith("ss:")) {
                    String verb = data.substring(3);
                    if ("m15".equals(verb)) {
                        engine.tgAddMinutes(15, fromName);
                    } else if ("m30".equals(verb)) {
                        engine.tgAddMinutes(30, fromName);
                    } else if ("end".equals(verb)) {
                        engine.tgEndNow(fromName);
                    }
                } else if (data.startsWith("rq:")) {
                    String[] parts = data.split(":");
                    if (parts.length == 3) {
                        engine.tgResolveRequest(parts[1], parts[2], fromName);
                    }
                }
            }
        });
    }

    private String statusText() {
        TimerEngine.Snapshot s = engine.snapshot();
        StringBuilder sb = new StringBuilder(L.t(this, "📊 看電視計時狀態\n", "📊 TV Timer status\n"));
        sb.append(L.t(this, "今日累計：", "Today: ")).append(Math.round(s.todayMs / 60000.0)).append(L.t(this, " 分鐘\n", " min\n"));
        if (s.parentMode) {
            sb.append(L.t(this, "目前：家長模式", "Status: parent mode"));
        } else if (s.sessionActive) {
            sb.append(L.t(this, "目前：", "Status: ")).append(s.counting ? L.t(this, "計時中", "timing") : L.t(this, "暫停中", "paused"))
                    .append(L.t(this, "［診斷 fg=", " [debug fg=")).append(s.dbgFgPkg == null ? "?" : s.dbgFgPkg)
                    .append(L.t(this, " 應計時=", " counting=")).append(s.dbgShouldCount ? L.t(this, "是", "yes") : L.t(this, "否", "no"))
                    .append(L.t(this, " 心跳=", " heartbeat=")).append(s.dbgHeartbeatAgeSec < 0 ? L.t(this, "無", "none")
                            : s.dbgHeartbeatAgeSec + L.t(this, "秒前", "s ago")).append(L.t(this, "］", "]"))
                    .append(L.t(this, "（", " (")).append(TimerEngine.appName(this, s.sessionPkg)).append(L.t(this, "）", ")"));
            if (!s.unlimited) {
                sb.append(L.t(this, "，剩 ", ", ")).append(Math.max(0, (int) Math.ceil(s.remainingMs / 60000.0)))
                        .append(L.t(this, " 分鐘", " min left"));
            } else {
                sb.append(L.t(this, "，不限制", ", unlimited"));
            }
        } else {
            sb.append(L.t(this, "目前：沒有進行中的時段", "Status: no active session"));
        }
        return sb.toString();
    }

    // ================= notification =================

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, L.t(this, "看電視計時", "TV Timer"), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(L.t(this, "看電視計時", "TV Timer"))
                .setContentText(L.t(this, "看門狗運作中", "Watchdog is running"))
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** 供 MainActivity 顯示權限狀態用。 */
    public static boolean hasOverlayPermission(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }
}
