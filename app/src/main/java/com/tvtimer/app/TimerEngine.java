package com.tvtimer.app;

import android.content.Context;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 應用程式使用計時的核心狀態機（純邏輯，不直接碰 UI / 網路）。
 *
 * 所有對外效果都透過 {@link Events} 交給 WatchdogService 執行；
 * 所有狀態變更都即時寫入 Prefs，電視重開機不會遺失任何狀態。
 * 倒數一律以 SystemClock.elapsedRealtime() 計算，不受系統時間調整影響；
 * 每日累計以本地日期字串為 key，跨日自動歸零。
 */
public class TimerEngine {

    // ---- Telegram 核准請求種類 ----
    public static final int REQ_EXTEND = 1;      // 時間到後請求加時
    public static final int REQ_NEW_SESSION = 2; // 已達每日上限，請求開新時段
    public static final int REQ_UNLIMITED = 3;   // 請求不限制模式
    public static final int REQ_PARENT = 4;      // 請求家長模式

    // ---- gateStartSession / gateResumeSession 的回傳碼 ----
    public static final int GATE_STARTED = 0;
    public static final int GATE_WAITING = 1;    // 已改送 Telegram 核准請求

    // ---- gateSubmitPin 的回傳碼 ----
    public static final int PIN_OK = 0;           // 驗證成功，家長模式已開啟
    public static final int PIN_WRONG = 1;        // PIN 不正確
    public static final int PIN_LOCKED = 2;       // 連續錯誤過多，鎖定中（SEC-03）
    public static final int PIN_MUST_CHANGE = 3;  // 驗證成功但仍是預設 PIN，須先設定新 PIN（SEC-02）

    /** CAP-CUSTOM（v10）：每日上限改讀 Prefs（預設 120 分，家長可在設定頁自訂 10–600 分）。 */
    public long dailyCapMs() {
        return Prefs.getDailyCapMin(ctx) * 60_000L;
    }
    /** 核准請求 pending 的有效期限，逾時自動失效（SEC-04）。 */
    private static final long PENDING_TTL_MS = 30L * 60_000L;
    private static final long PARENT_MODE_MS = 2L * 60 * 60_000L;
    private static final long WARN_AT_MS = 60_000L;
    /** FG-3：UsageStats 心跳視為有效的新鮮窗口（前台訊號遺失時的備援計時依據）。 */
    private static final long HEARTBEAT_FRESH_MS = 15_000L;
    /** FG-5：計時中前台訊號遺失的回復寬限；寬限內視為仍在計時，逾時才轉暫停。 */
    private static final long FG_LOSS_GRACE_MS = 10_000L;
    /** PIP-1（v11）：無障礙視窗掃描約每秒一次，「觀看 App 視窗可見」訊號的新鮮窗口。 */
    private static final long VISIBLE_FRESH_MS = 4_000L;
    /** RB-1：換日歸零的時鐘信任窗——init 後須經過此時間（開機網路校時通常已完成）才允許換日。 */
    private static final long ROLLOVER_TRUST_MS = 45_000L;
    /** 我們自己呼叫 goHome() 後，短時間內忽略「launcher 成為前台」以免把剛顯示的 gate 又關掉。 */
    private static final long GATE_GUARD_MS = 1_500L;

    // ---- 觀看類 App（預設清單，可在 MainActivity 開關） ----
    public static final String PKG_YOUTUBE = "com.google.android.youtube.tv";
    public static final String PKG_YT_KIDS = "com.google.android.youtube.tvkids";
    public static final String PKG_NETFLIX = "com.netflix.ninja";
    public static final String PKG_DISNEY = "com.disney.disneyplus";
    public static final String PKG_PRIME = "com.amazon.amazonvideo.livingroom";
    public static final String[] WATCH_PKGS = {
            PKG_YOUTUBE, PKG_YT_KIDS, PKG_NETFLIX, PKG_DISNEY, PKG_PRIME
    };

    private static final int REASON_TIME_UP = 0;
    private static final int REASON_PARENT_END = 1;
    private static final int REASON_PARENT_MODE = 2;
    /** CAP-1（v7）：今日累計跨越每日上限，硬停。 */
    private static final int REASON_CAP = 3;
    /** MID-1（v7）：午夜熄燈，時段跨日結清。 */
    private static final int REASON_MIDNIGHT = 4;

    public interface Events {
        void showGate(int page, int pinPurpose, boolean deniedNote);

        /** 核准請求送出失敗：回到可重試的頁面並顯示送出失敗提示（SEC-04）。 */
        void showGateSendFailed(int page);

        void hideGate();

        /** 只在 gate 停留在暫時性頁面（開始／時間到／訊息）時才關閉。 */
        void hideGateIfTransient();

        void showWarning(int secondsLeft);

        void goHome();

        void launchApp(String pkg);

        void openMainActivity();

        void tgSessionStarted(int minutes, boolean unlimited, boolean resumed);

        void tgSessionPaused(int todayMinutes);

        void tgSessionEnded(int sessionMinutes, int todayMinutes, String reasonZh);

        void tgCapReached();

        void tgParentModeChanged(boolean on);

        void tgSendRequest(String reqId, int kind, int minutes, String pkg);

        void tgRequestResolved(String outcomeText);

        void tgPlain(String text);
    }

    /** 給 gate 與 MainActivity 讀取的唯讀快照。 */
    public static final class Snapshot {
        public boolean hasToken;
        public boolean parentMode;
        public boolean sessionActive;
        public boolean unlimited;
        public boolean counting;
        public boolean needsResume;
        public long remainingMs;
        public long todayMs;
        public boolean capReached;
        public String sessionPkg = "";
        public String gateTargetPkg = "";
        public String blockedPkg = "";
        public String messageText = "";
        public boolean hasPending;
        // FG-3 診斷欄位（供 /status 與 logcat 判讀計時路徑）
        public String dbgFgPkg;
        public boolean dbgShouldCount;
        public long dbgHeartbeatAgeSec = -1;
        /** PIP-1 診斷欄位：session App 視窗最後一次被掃描為可見的距今秒數；-1 = 未曾可見。 */
        public long dbgVisibleAgeSec = -1;
    }

    private final Context ctx;
    private final Events events;
    private final String ownPkg;

    // ---- in-memory state（init 時自 Prefs 載入，變更即寫回） ----
    private boolean sessionActive;
    private boolean sessionUnlimited;
    private long remainingMs;
    private long watchedMs;
    private String sessionPkg = "";
    private boolean warned;
    private String todayDate = "";
    private long todayMs;
    /** RB-1：本次 init 的 elapsedRealtime（換日信任窗起點）。 */
    private long initElapsed;
    /** MID-1：進行中時段所屬日期（yyyy-MM-dd）。 */
    private String sessionDate = "";
    private boolean capNotified;
    private long parentElapsedDeadline; // 0 = 家長模式關閉

    // ---- runtime-only ----
    private String lastFgPkg;
    private String gateTargetPkg = "";
    private String blockedPkg = "";
    private String messageText = "";
    /** 預設 PIN 驗證通過、等待在 gate 完成強制改 PIN 後要延續的用途（SEC-02）。 */
    private int pendingPinPurpose;
    private boolean needsResume;
    private boolean wasCounting;
    private long lastTickAnchor = -1;
    private long gateGuardUntil;
    private boolean gateShowing;
    /** FG-3：session 觀看 App 最後一次被前台來源（無障礙／UsageStats 心跳）確認的 elapsed 時間；-1 = 未曾確認。 */
    private long lastWatchConfirmElapsed = -1;
    /** FG-3：tick() 最近一次的 shouldCount 判定（診斷用）。 */
    private boolean lastShouldCount;
    /** FG-5：計時中 fg 剛離開觀看 App 的 elapsed 時間（回復寬限起點）；-1 = 未在寬限中。 */
    private long fgLostSinceElapsed = -1;

    public TimerEngine(Context ctx, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.events = events;
        this.ownPkg = ctx.getPackageName();
    }

    public void init() {
        sessionActive = Prefs.getSessionActive(ctx);
        sessionUnlimited = Prefs.getSessionUnlimited(ctx);
        remainingMs = Prefs.getSessionRemainingMs(ctx);
        watchedMs = Prefs.getSessionWatchedMs(ctx);
        sessionPkg = Prefs.getSessionPkg(ctx);
        sessionDate = Prefs.getSessionDate(ctx);
        warned = Prefs.getSessionWarned(ctx);
        todayDate = Prefs.getTodayDate(ctx);
        todayMs = Prefs.getTodayMs(ctx);
        capNotified = todayString().equals(Prefs.getCapNotifiedDate(ctx));
        needsResume = sessionActive; // 重開機／服務重啟後，一律先確認續看
        initElapsed = SystemClock.elapsedRealtime();
        rolloverIfNeeded();
        // MID-1：舊版資料無 sessionDate 時以已存今日日期補記，避免升級後誤殺進行中時段
        if (sessionActive && sessionDate.isEmpty()) {
            sessionDate = todayDate;
            persistSession();
        }
        // 家長模式：以存下的 wall-clock 截止時間重建 elapsed 截止時間
        long wallDeadline = Prefs.getParentDeadlineWall(ctx);
        long wallNow = System.currentTimeMillis();
        if (wallDeadline > wallNow) {
            parentElapsedDeadline = SystemClock.elapsedRealtime() + (wallDeadline - wallNow);
        } else {
            parentElapsedDeadline = 0;
            if (wallDeadline != 0) {
                Prefs.setParentDeadlineWall(ctx, 0);
            }
        }
    }

    // ================= queries =================

    public boolean hasToken() {
        return !Prefs.getToken(ctx).isEmpty();
    }

    public boolean isParentMode() {
        return parentElapsedDeadline != 0
                && SystemClock.elapsedRealtime() < parentElapsedDeadline;
    }

    public boolean isSessionActive() {
        return sessionActive;
    }

    public boolean capReached() {
        return todayMs >= dailyCapMs();
    }

    public void setGateShowing(boolean showing) {
        gateShowing = showing;
    }

    /** FG-3：WatchdogService 依 UsageStats 事件確認 session 觀看 App 仍在前台時呼叫，刷新心跳。 */
    public void noteForegroundHeartbeat() {
        lastWatchConfirmElapsed = SystemClock.elapsedRealtime();
    }

    /** FG-3：UsageStats 顯示 session 觀看 App 已 STOPPED（真的離開）時呼叫，清除心跳。 */
    public void clearForegroundHeartbeat() {
        lastWatchConfirmElapsed = -1;
    }

    /** PIP-1（v11）：session 觀看 App 最後一次被無障礙視窗掃描確認「視窗可見」的 elapsed 時間；-1 = 未曾確認。 */
    private long lastWatchVisibleElapsed = -1;

    /** PIP-1（v11）：WatchdogService 掃描到 session App 的 PiP／分割畫面視窗仍可見時呼叫。 */
    public void noteWatchVisible() {
        lastWatchVisibleElapsed = SystemClock.elapsedRealtime();
    }

    public Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.hasToken = hasToken();
        s.parentMode = isParentMode();
        s.sessionActive = sessionActive;
        s.unlimited = sessionUnlimited;
        s.counting = wasCounting;
        s.needsResume = needsResume;
        s.remainingMs = remainingMs;
        s.todayMs = todayMs;
        s.capReached = capReached();
        s.sessionPkg = sessionPkg;
        s.gateTargetPkg = gateTargetPkg;
        s.blockedPkg = blockedPkg;
        s.messageText = messageText;
        s.hasPending = !Prefs.getPendId(ctx).isEmpty() && !Prefs.getPendResolved(ctx);
        s.dbgFgPkg = lastFgPkg;
        s.dbgShouldCount = lastShouldCount;
        s.dbgHeartbeatAgeSec = lastWatchConfirmElapsed < 0 ? -1
                : (SystemClock.elapsedRealtime() - lastWatchConfirmElapsed) / 1000L;
        s.dbgVisibleAgeSec = lastWatchVisibleElapsed < 0 ? -1
                : (SystemClock.elapsedRealtime() - lastWatchVisibleElapsed) / 1000L;
        return s;
    }

    public static String appName(String pkg) {
        if (pkg == null) {
            return "";
        }
        switch (pkg) {
            case PKG_YOUTUBE:
                return "YouTube";
            case PKG_YT_KIDS:
                return "YouTube Kids";
            case PKG_NETFLIX:
                return "Netflix";
            case PKG_DISNEY:
                return "Disney+";
            case PKG_PRIME:
                return "Prime Video";
            case "com.android.tv.settings":
            case "com.android.settings":
                return "設定";
            case "com.android.vending":
                return "Play 商店";
            case "com.miui.securitycenter":
                return "安全中心";
            case "com.miui.packageinstaller":
                return "安裝器";
            case "com.xiaomi.market":
                return "小米應用商店";
            case "com.sec.android.app.samsungapps":
                return "Galaxy Store";
            case "com.huawei.appmarket":
                return "華為應用市場";
            default:
                return pkg;
        }
    }

    /** WL-1：自訂 App 的顯示名稱（家長加入時存下的 label）；非自訂 App 傳回內建名稱。 */
    public static String appName(Context c, String pkg) {
        if (pkg != null) {
            String label = Prefs.getCustomWatch(c).get(pkg);
            if (label != null && !label.isEmpty()) {
                return label;
            }
            // v6：系統 App 名稱跟隨介面語言（無 Context 的 appName(pkg) 維持中文原值）
            if ("com.android.tv.settings".equals(pkg) || "com.android.settings".equals(pkg)) {
                return L.t(c, "設定", "Settings");
            }
            if ("com.android.vending".equals(pkg)) {
                return L.t(c, "Play 商店", "Play Store");
            }
        }
        return appName(pkg);
    }

    public boolean isWatchingApp(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : WATCH_PKGS) {
            if (p.equals(pkg)) {
                // WL-3：被家長移除的預設 App 視同不在清單
                if (Prefs.getRemovedPresets(ctx).contains(pkg)) {
                    return false;
                }
                return Prefs.getWatchEnabled(ctx, pkg);
            }
        }
        // WL-1：家長自訂清單
        if (Prefs.getCustomWatch(ctx).containsKey(pkg)) {
            return Prefs.getWatchEnabled(ctx, pkg);
        }
        return false;
    }

    public boolean isBlockedApp(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (pkg.equals("com.android.tv.settings")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.android.vending")
                || pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.google.android.packageinstaller")
                // BLK-PAD-1（v11）：平板品牌系統管理入口與安裝繞道
                // 小米安全中心內含應用管理（可停用／解除安裝 App）、小米安裝器、
                // 各品牌自家應用商店（可繞過 Play 商店封鎖裝新 App）
                || pkg.equals("com.miui.securitycenter")
                || pkg.equals("com.miui.packageinstaller")
                || pkg.equals("com.xiaomi.market")
                || pkg.equals("com.sec.android.app.samsungapps")
                || pkg.equals("com.huawei.appmarket")) {
            return true;
        }
        // 自己的 MainActivity 在鎖定狀態下也要先過 PIN（避免小孩改設定）
        return pkg.equals(ownPkg);
    }

    // ================= foreground / tick =================

    public void onForegroundChanged(String pkg) {
        lastFgPkg = pkg;
        if (!hasToken() || isParentMode() || pkg == null) {
            return;
        }
        if (isBlockedApp(pkg)) {
            blockedPkg = pkg;
            events.goHome();
            gateGuardUntil = SystemClock.elapsedRealtime() + GATE_GUARD_MS;
            if (pkg.equals(ownPkg)) {
                events.showGate(GateController.PAGE_PIN, GateController.PURPOSE_SETTINGS, false);
            } else {
                messageText = L.t(ctx, "「", "\"") + appName(ctx, pkg) + L.t(ctx, "」已鎖定，要由家長解除。", "\" is locked. A parent needs to unlock it.");
                events.showGate(GateController.PAGE_MESSAGE, 0, false);
            }
            return;
        }
        if (isWatchingApp(pkg)) {
            gateTargetPkg = pkg;
            if (!sessionActive) {
                events.goHome();
                gateGuardUntil = SystemClock.elapsedRealtime() + GATE_GUARD_MS;
                events.showGate(GateController.PAGE_START, 0, false);
            } else if (needsResume) {
                events.goHome();
                gateGuardUntil = SystemClock.elapsedRealtime() + GATE_GUARD_MS;
                events.showGate(GateController.PAGE_START, 0, false);
            } else {
                events.hideGateIfTransient();
            }
            return;
        }
        // 其他 App / 主畫面：若 gate 還停在暫時性頁面且已過保護時間，關掉它
        if (SystemClock.elapsedRealtime() > gateGuardUntil) {
            events.hideGateIfTransient();
        }
    }

    public void tick() {
        long nowEl = SystemClock.elapsedRealtime();

        if (parentElapsedDeadline != 0 && nowEl >= parentElapsedDeadline) {
            endParentMode();
        }
        rolloverIfNeeded();
        expirePendingIfNeeded();
        endSessionIfPastMidnight();
        enforceBlockedApp();
        if (!sessionActive || isParentMode()) {
            wasCounting = false;
            lastTickAnchor = -1;
            return;
        }

        boolean fgWatch = lastFgPkg != null && isWatchingApp(lastFgPkg);
        if (fgWatch) {
            lastWatchConfirmElapsed = nowEl;
            fgLostSinceElapsed = -1;
        } else if (wasCounting && fgLostSinceElapsed < 0) {
            // FG-5：計時中前台訊號剛遺失，先記下時間點；寬限內若訊號回復就不算暫停
            fgLostSinceElapsed = nowEl;
        }
        // FG-3 備援：前台追蹤訊號遺失（lastFgPkg 停在非觀看 App）時，若 UsageStats 心跳
        // 仍在新鮮窗口內確認 session 的觀看 App 在前台，繼續計時，避免「影片照播、
        // 計時凍結」。心跳由 WatchdogService 依 UsageStats 事件維護：session App 最後
        // 一筆事件是 ACTIVITY_RESUMED 就持續刷新、是 ACTIVITY_STOPPED 就清除。
        boolean heartbeatFresh = sessionPkg != null && !sessionPkg.isEmpty()
                && isWatchingApp(sessionPkg)
                && lastWatchConfirmElapsed > 0
                && nowEl - lastWatchConfirmElapsed <= HEARTBEAT_FRESH_MS;
        // PIP-1（v11）：平板子母畫面（PiP）／分割畫面時，session App 不在前台
        // 但視窗仍可見、影片續播；WatchdogService 以無障礙視窗掃描餵入可見訊號，
        // 新鮮窗口內視為仍在觀看，避免「小窗續播、計時暫停」的繞過路徑。
        boolean visibleFresh = sessionPkg != null && !sessionPkg.isEmpty()
                && isWatchingApp(sessionPkg)
                && lastWatchVisibleElapsed > 0
                && nowEl - lastWatchVisibleElapsed <= VISIBLE_FRESH_MS;
        // FG-5 回復寬限：計時中遇到短暫前台閃爍（實測播放開始時 launcherx 視窗
        // 事件會閃入約數百 ms 至數秒），寬限內不立刻暫停；訊號（fg 或心跳）在
        // 寬限內回復就無縫續計，逾時才認定真的離開、轉暫停並要求續看確認。
        boolean inGrace = wasCounting && !fgWatch && !heartbeatFresh && !visibleFresh
                && fgLostSinceElapsed > 0
                && nowEl - fgLostSinceElapsed <= FG_LOSS_GRACE_MS;
        boolean shouldCount = (fgWatch || heartbeatFresh || visibleFresh || inGrace) && !needsResume;
        lastShouldCount = shouldCount;
        if (shouldCount) {
            if (lastTickAnchor < 0) {
                lastTickAnchor = nowEl;
            } else {
                long delta = nowEl - lastTickAnchor;
                lastTickAnchor = nowEl;
                if (delta > 0) {
                    long prevTodayMs = todayMs;
                    watchedMs += delta;
                    todayMs += delta;
                    if (!sessionUnlimited) {
                        remainingMs -= delta;
                        if (remainingMs <= WARN_AT_MS && remainingMs > 0 && !warned) {
                            warned = true;
                            events.showWarning((int) Math.ceil(remainingMs / 1000.0));
                        }
                        if (remainingMs <= 0) {
                            remainingMs = 0;
                            persistSession();
                            persistToday();
                            endSession(REASON_TIME_UP);
                            return;
                        }
                    }
                    // CAP-1（v7）：今日累計在本 tick 跨越每日上限 → 硬停結束時段。
                    // 以「跨越」觸發：開局時已達上限（家長核准的超額時段）不誤殺；
                    // 不限制時段豁免（使用者 2026-10-31 裁示）。
                    if (!sessionUnlimited && prevTodayMs < dailyCapMs() && todayMs >= dailyCapMs()) {
                        capNotified = true;
                        Prefs.setCapNotifiedDate(ctx, todayDate);
                        persistSession();
                        persistToday();
                        endSession(REASON_CAP);
                        return;
                    }
                    if (capReached() && !capNotified) {
                        capNotified = true;
                        Prefs.setCapNotifiedDate(ctx, todayDate);
                        events.tgCapReached();
                    }
                    persistSession();
                    persistToday();
                }
            }
            wasCounting = true;
        } else {
            if (wasCounting) {
                // 計時中 → 暫停（離開 App／待機／切去別的 App；已過 FG-5 寬限）
                wasCounting = false;
                needsResume = true;
                persistSession();
                events.tgSessionPaused(todayMinutes());
            }
            fgLostSinceElapsed = -1;
            lastTickAnchor = -1;
        }
    }

    public void onScreenOff() {
        lastFgPkg = null;
        if (isParentMode()) {
            endParentMode();
        }
    }

    // ================= gate actions =================

    /** 回傳 GATE_STARTED（已開始）或 GATE_WAITING（已改送核准請求）。 */
    public int gateStartSession(int minutes) {
        String target = targetPkg();
        if (capReached()) {
            createRequest(REQ_NEW_SESSION, minutes, target, 0);
            return GATE_WAITING;
        }
        startSessionInternal(minutes, target, false);
        return GATE_STARTED;
    }

    public int gateResumeSession() {
        if (!sessionActive) {
            return GATE_WAITING;
        }
        needsResume = false;
        events.hideGate();
        String target = !sessionPkg.isEmpty() ? sessionPkg : targetPkg();
        if (!target.isEmpty()) {
            events.launchApp(target);
        }
        events.tgSessionStarted(remainingMinutesCeil(), sessionUnlimited, true);
        return GATE_STARTED;
    }

    /** 請求不限制模式（僅能由 Telegram 核准）。回傳 true 表示請求已送出／已在等待。 */
    public boolean gateRequestUnlimited() {
        createRequest(REQ_UNLIMITED, 0, targetPkg(), 0);
        return true;
    }

    /** 時間到頁面的「請爸媽加時」。 */
    public boolean gateRequestExtension() {
        String target = !sessionPkg.isEmpty() ? sessionPkg : targetPkg();
        createRequest(REQ_EXTEND, 0, target, 0);
        return true;
    }

    /** 以 Telegram 核准進入家長模式（purpose 沿用 gate 的 PIN 用途）。 */
    public boolean gateRequestParentApproval(int purpose) {
        createRequest(REQ_PARENT, 0, "", purpose);
        return true;
    }

    /**
     * gate PIN 驗證。回傳 {@link #PIN_OK}／{@link #PIN_WRONG}／{@link #PIN_LOCKED}／
     * {@link #PIN_MUST_CHANGE}：
     * - 連續錯誤達上限時由 Prefs 節流鎖定（SEC-03），鎖定期間一律回 PIN_LOCKED；
     * - 驗證成功但存著的仍是預設 PIN 時，不直接開家長模式，先把 gate 導向
     *   強制設定新 PIN 頁（SEC-02），由 {@link #gateSetNewPin} 完成後延續原用途。
     */
    public int gateSubmitPin(String pin, int purpose) {
        if (Prefs.pinLockoutRemainingMs(ctx) > 0) {
            return PIN_LOCKED;
        }
        if (pin == null || !pin.equals(Prefs.getPin(ctx))) {
            return Prefs.recordPinFailure(ctx) > 0 ? PIN_LOCKED : PIN_WRONG;
        }
        Prefs.resetPinFailures(ctx);
        if (Prefs.DEFAULT_PIN.equals(Prefs.getPin(ctx))) {
            pendingPinPurpose = purpose;
            events.showGate(GateController.PAGE_SET_PIN, purpose, false);
            return PIN_MUST_CHANGE;
        }
        startParentModeInternal();
        if (purpose == GateController.PURPOSE_SETTINGS) {
            events.openMainActivity();
        }
        return PIN_OK;
    }

    /**
     * 強制改 PIN 流程的完成點（SEC-02）：寫入新 PIN 後，延續先前以預設 PIN
     * 驗證通過的那次家長模式／設定頁用途。新 PIN 不得等於預設值。
     */
    public boolean gateSetNewPin(String newPin) {
        if (newPin == null || newPin.length() != 4
                || Prefs.DEFAULT_PIN.equals(newPin)) {
            return false;
        }
        for (int i = 0; i < newPin.length(); i++) {
            if (!Character.isDigit(newPin.charAt(i))) {
                return false;
            }
        }
        Prefs.setPin(ctx, newPin);
        int purpose = pendingPinPurpose;
        pendingPinPurpose = 0;
        startParentModeInternal();
        if (purpose == GateController.PURPOSE_SETTINGS) {
            events.openMainActivity();
        }
        return true;
    }

    public void gateDismissed() {
        // 使用者自行關閉 gate：不改變任何狀態；下次開啟觀看 App 會再次觸發。
    }

    // ================= Telegram commands / resolutions =================

    /** 家長在時段訊息上按 +15／+30（進行中或暫停中的時段直接加時）。 */
    public void tgAddMinutes(int minutes, String byName) {
        if (!sessionActive) {
            events.tgPlain(L.t(ctx, "目前沒有進行中的時段，無法加時。", "There's no active session, so time can't be added."));
            return;
        }
        if (sessionUnlimited) {
            events.tgPlain(L.t(ctx, "目前是不限制模式，不需要加時。", "The current session is unlimited — no need to add time."));
            return;
        }
        remainingMs += minutes * 60_000L;
        if (remainingMs > WARN_AT_MS) {
            warned = false;
        }
        persistSession();
        events.tgPlain("✅ " + byName + L.t(ctx, " 已加時 ", " added ") + minutes + L.t(ctx, " 分鐘，剩餘 ", " min. Remaining: ")
                + remainingMinutesCeil() + L.t(ctx, " 分鐘。", " min."));
    }

    /** 家長在時段訊息上按「現在結束」。 */
    public void tgEndNow(String byName) {
        if (!sessionActive) {
            events.tgPlain(L.t(ctx, "目前沒有進行中的時段。", "There's no active session."));
            return;
        }
        endSession(REASON_PARENT_END);
    }

    /**
     * 處理核准請求的回覆（first-approval-wins）：
     * 第一個家長的回覆生效並被記錄；之後的回覆只會收到「已處理」通知。
     * verb: ok / m15 / m30 / unlim / deny
     */
    public void tgResolveRequest(String reqId, String verb, String byName) {
        String pid = Prefs.getPendId(ctx);
        if (pid.isEmpty() || !pid.equals(reqId)) {
            events.tgPlain(L.t(ctx, "這個請求已失效。", "This request is no longer valid."));
            return;
        }
        if (Prefs.getPendResolved(ctx)) {
            events.tgPlain(L.t(ctx, "這個請求已由 ", "This request was already handled by ") + Prefs.getPendApprover(ctx) + L.t(ctx, " 處理了。", "."));
            return;
        }
        int kind = Prefs.getPendKind(ctx);
        int minutes = Prefs.getPendMinutes(ctx);
        String pkg = Prefs.getPendPkg(ctx);
        int purpose = Prefs.getPendPurpose(ctx);

        String outcome;
        boolean denied = false;
        switch (verb) {
            case "deny":
                denied = true;
                outcome = "❌ " + byName + L.t(ctx, " 拒絕了這個請求。", " denied this request.");
                break;
            case "ok":
                if (kind == REQ_NEW_SESSION) {
                    startSessionInternal(minutes, pkg, false);
                    outcome = "✅ " + byName + L.t(ctx, " 核准開局 ", " approved a ") + minutes + L.t(ctx, " 分鐘。", "-minute session.");
                } else if (kind == REQ_PARENT) {
                    startParentModeInternal();
                    if (purpose == GateController.PURPOSE_SETTINGS) {
                        events.openMainActivity();
                    }
                    outcome = "✅ " + byName + L.t(ctx, " 核准了家長模式（2 小時）。", " approved parent mode (2 hours).");
                } else {
                    return; // 此種類不該出現 ok，忽略
                }
                break;
            case "m15":
            case "m30": {
                int m = "m15".equals(verb) ? 15 : 30;
                if (kind == REQ_EXTEND || kind == REQ_NEW_SESSION) {
                    startSessionInternal(m, pkg, false);
                    outcome = "✅ " + byName + L.t(ctx, " 核准 +", " approved +") + m + L.t(ctx, " 分鐘。", " min.");
                } else {
                    return;
                }
                break;
            }
            case "unlim":
                if (kind == REQ_EXTEND || kind == REQ_NEW_SESSION || kind == REQ_UNLIMITED) {
                    startSessionInternal(0, pkg, true);
                    outcome = "✅ " + byName + L.t(ctx, " 核准不限制模式。", " approved unlimited mode.");
                } else {
                    return;
                }
                break;
            default:
                return; // 未知 verb，忽略
        }

        Prefs.setPendResolved(ctx, true, byName);
        events.tgRequestResolved(outcome);

        if (denied && gateShowing) {
            if (kind == REQ_EXTEND) {
                events.showGate(GateController.PAGE_TIMEUP, 0, true);
            } else if (kind == REQ_PARENT) {
                messageText = L.t(ctx, "爸媽拒絕了家長模式請求。", "A parent denied the parent mode request.");
                events.showGate(GateController.PAGE_MESSAGE, 0, false);
            } else {
                events.showGate(GateController.PAGE_START, 0, true);
            }
        }
    }

    // ================= parent mode =================

    public void startParentModeInternal() {
        if (sessionActive) {
            endSession(REASON_PARENT_MODE);
        }
        long wallDeadline = System.currentTimeMillis() + PARENT_MODE_MS;
        Prefs.setParentDeadlineWall(ctx, wallDeadline);
        parentElapsedDeadline = SystemClock.elapsedRealtime() + PARENT_MODE_MS;
        events.hideGate();
        events.tgParentModeChanged(true);
    }

    public void endParentMode() {
        if (parentElapsedDeadline == 0) {
            return;
        }
        parentElapsedDeadline = 0;
        Prefs.setParentDeadlineWall(ctx, 0);
        events.tgParentModeChanged(false);
    }

    // ================= internals =================

    private void startSessionInternal(int minutes, String pkg, boolean unlimited) {
        sessionActive = true;
        sessionUnlimited = unlimited;
        remainingMs = unlimited ? 0 : minutes * 60_000L;
        watchedMs = 0;
        warned = false;
        if (pkg != null && !pkg.isEmpty()) {
            sessionPkg = pkg;
        }
        sessionDate = todayDate;
        needsResume = false;
        wasCounting = false;
        lastTickAnchor = -1;
        persistSession();
        events.hideGate();
        if (pkg != null && !pkg.isEmpty()) {
            events.launchApp(pkg);
        }
        events.tgSessionStarted(minutes, unlimited, false);
    }

    private void endSession(int reason) {
        int watchedMin = watchedMinutesReport();
        sessionActive = false;
        sessionUnlimited = false;
        remainingMs = 0;
        needsResume = false;
        wasCounting = false;
        lastTickAnchor = -1;
        persistSession();

        String reasonZh;
        if (reason == REASON_TIME_UP) {
            reasonZh = L.t(ctx, "時間到", "time up");
        } else if (reason == REASON_PARENT_END) {
            reasonZh = L.t(ctx, "爸媽從 Telegram 結束", "ended by a parent from Telegram");
        } else if (reason == REASON_CAP) {
            reasonZh = L.t(ctx, "已達每日上限", "daily limit reached");
        } else if (reason == REASON_MIDNIGHT) {
            reasonZh = L.t(ctx, "午夜 12 點熄燈", "midnight lights-out");
        } else {
            reasonZh = L.t(ctx, "家長模式啟用", "parent mode started");
        }
        events.tgSessionEnded(watchedMin, todayMinutes(), reasonZh);

        if (reason == REASON_TIME_UP || reason == REASON_CAP) {
            if (lastFgPkg != null && isWatchingApp(lastFgPkg)) {
                events.goHome();
                gateGuardUntil = SystemClock.elapsedRealtime() + GATE_GUARD_MS;
            }
            events.showGate(GateController.PAGE_TIMEUP, 0, false);
        } else if (reason == REASON_MIDNIGHT) {
            // 家長模式中、或人不在觀看畫面（暫停在主畫面）時，只結清＋回報，不跳 gate 打擾
            if (!isParentMode() && lastFgPkg != null && isWatchingApp(lastFgPkg)) {
                events.goHome();
                gateGuardUntil = SystemClock.elapsedRealtime() + GATE_GUARD_MS;
                messageText = L.t(ctx, "午夜 12 點了，今天的看電視時間結束。",
                        "It's midnight - TV time is over for today.");
                events.showGate(GateController.PAGE_MESSAGE, 0, false);
            }
        } else if (reason == REASON_PARENT_END) {
            if (lastFgPkg != null && isWatchingApp(lastFgPkg)) {
                events.goHome();
                gateGuardUntil = SystemClock.elapsedRealtime() + GATE_GUARD_MS;
                messageText = L.t(ctx, "爸媽已結束這次的看電視時間。", "A parent ended this TV session.");
                events.showGate(GateController.PAGE_MESSAGE, 0, false);
            }
        }
    }

    /** 建立（或重用未解決的）核准請求，並請 service 送出 Telegram 訊息。 */
    private void createRequest(int kind, int minutes, String pkg, int purpose) {
        String existing = Prefs.getPendId(ctx);
        if (!existing.isEmpty() && !Prefs.getPendResolved(ctx)) {
            if (!pendingExpired()) {
                return; // 已有一個請求在等待，不重複送出
            }
            // 既有 pending 已逾時失效：清除後由新請求取代，不靜默吞掉（SEC-04）
            Prefs.clearPending(ctx);
        }
        String id = "r" + Prefs.nextReqSeq(ctx);
        Prefs.savePending(ctx, id, kind, minutes, pkg, purpose);
        events.tgSendRequest(id, kind, minutes, pkg);
    }

    /**
     * 核准請求送出失敗（Telegram 回 onSent(0)／例外，或尚未配對無從送出）：
     * 立即清除 pending，絕不讓「等待中」的假狀態卡死後續請求（SEC-04）；
     * gate 若正顯示等待頁，改回可重試的頁面並提示送出失敗。
     * 由 WatchdogService 在主執行緒呼叫。
     */
    public void onRequestSendFailed(String reqId) {
        String pid = Prefs.getPendId(ctx);
        if (pid.isEmpty() || !pid.equals(reqId) || Prefs.getPendResolved(ctx)) {
            return;
        }
        int kind = Prefs.getPendKind(ctx);
        Prefs.clearPending(ctx);
        if (gateShowing) {
            events.showGateSendFailed(returnPageForKind(kind));
        }
    }

    /** 未解決 pending 是否已逾時（含無建立時間的舊資料，一律視為逾時）。 */
    private boolean pendingExpired() {
        String pid = Prefs.getPendId(ctx);
        if (pid.isEmpty() || Prefs.getPendResolved(ctx)) {
            return false;
        }
        long created = Prefs.getPendCreatedWall(ctx);
        return created == 0
                || System.currentTimeMillis() - created > PENDING_TTL_MS;
    }

    /** tick 定期呼叫：逾時 pending 自動失效，gate 若停在等待頁則回到可重試頁面。 */
    private void expirePendingIfNeeded() {
        if (!pendingExpired()) {
            return;
        }
        int kind = Prefs.getPendKind(ctx);
        Prefs.clearPending(ctx);
        if (gateShowing) {
            events.showGate(returnPageForKind(kind), 0, false);
        }
    }

    /** 請求失敗／逾時後，gate 應回到哪一頁才能讓小孩重試。 */
    private static int returnPageForKind(int kind) {
        switch (kind) {
            case REQ_EXTEND:
                return GateController.PAGE_TIMEUP;
            case REQ_PARENT:
                return GateController.PAGE_PIN;
            case REQ_NEW_SESSION:
            case REQ_UNLIMITED:
            default:
                return GateController.PAGE_START;
        }
    }

    private String targetPkg() {
        if (!gateTargetPkg.isEmpty()) {
            return gateTargetPkg;
        }
        if (!sessionPkg.isEmpty()) {
            return sessionPkg;
        }
        return PKG_YOUTUBE;
    }

    /** BLK-1（v8）：封鎖 App 強制執行的最小間隔（節流，避免 goHome／重畫連發）。 */
    private static final long BLOCK_ENFORCE_INTERVAL_MS = 1_500L;
    /** BLK-1：上次強制執行的 elapsed 時間。 */
    private long lastBlockEnforceElapsed = -1L;

    /**
     * BLK-1（v8）：系統封鎖 App（設定／Play 商店／安裝器）的週期性強制鎖定。
     * 背景：v7 以前的封鎖只在前台「變化邊緣」觸發一次；2026-10-31 16:07 logcat 實證：
     * 攔截有觸發（goHome＋gate addView OK），但設定在約 0.3 秒後搶回前台、gate 被蓋住，
     * tracker 自此恆為 settings、不再有變化事件，封鎖永不重觸，使用者可在設定內自由操作
     * （含移除本 App）。改為 level-trigger：tick 每秒檢查，前台仍為封鎖 App 就週期性
     * 重申 goHome＋鎖定頁，直到前台真的離開封鎖 App 為止。
     * 自家 MainActivity 不在此列（維持邊緣觸發＋其設定區另有 PIN，避免干擾家長操作流程）；
     * 家長模式期間不執行（看門狗刻意休眠）。
     */
    private void enforceBlockedApp() {
        if (isParentMode() || !hasToken() || lastFgPkg == null) {
            return;
        }
        if (!isBlockedApp(lastFgPkg) || lastFgPkg.equals(ownPkg)) {
            return;
        }
        long nowEl = SystemClock.elapsedRealtime();
        if (lastBlockEnforceElapsed > 0
                && nowEl - lastBlockEnforceElapsed < BLOCK_ENFORCE_INTERVAL_MS) {
            return;
        }
        lastBlockEnforceElapsed = nowEl;
        blockedPkg = lastFgPkg;
        events.goHome();
        gateGuardUntil = nowEl + GATE_GUARD_MS;
        messageText = L.t(ctx, "「", "\"") + appName(ctx, lastFgPkg)
                + L.t(ctx, "」已鎖定，要由家長解除。", "\" is locked. A parent needs to unlock it.");
        events.showGate(GateController.PAGE_MESSAGE, 0, false);
    }

    /**
     * MID-1（v7）：今日日期已翻頁、但時段仍掛在舊日期 → 午夜熄燈結清（含暫停待續看）。
     * 電視關機跨午夜時，於開機後換日真正執行的那個 tick 一併結清。
     * 不限制時段經爸媽核准可跨午夜，豁免（使用者 2026-10-31 裁示）。
     */
    private void endSessionIfPastMidnight() {
        if (!sessionActive || sessionUnlimited) {
            return;
        }
        if (sessionDate == null || sessionDate.isEmpty()) {
            return;
        }
        if (!sessionDate.equals(todayDate)) {
            endSession(REASON_MIDNIGHT);
        }
    }

    private void rolloverIfNeeded() {
        String today = todayString();
        int cmp = today.compareTo(todayDate);
        if (cmp == 0) {
            return;
        }
        if (cmp < 0) {
            // RB-1：系統日期比已存紀錄還舊＝時鐘尚未校時（Chromecast 無電池 RTC，
            // 重開機初期 wall clock 停在出廠／舊時間）。此時絕不換日歸零；
            // 計時先沿用已存日期累計，tick 會持續重試，校時完成後自然對齊。
            return;
        }
        // cmp > 0：候選換日。必須先確認時鐘可信才真的歸零，否則延後（tick 每秒重試）：
        // (1) 距 init 已逾 ROLLOVER_TRUST_MS（開機網路校時通常在此窗內完成）；
        // (2) 目前 wall clock 不早於上次寫入今日累計的 wall clock（時鐘未倒退）。
        long nowWall = System.currentTimeMillis();
        long lastWall = Prefs.getTodayWallMs(ctx);
        boolean settled = SystemClock.elapsedRealtime() - initElapsed >= ROLLOVER_TRUST_MS;
        if (!settled || (lastWall > 0 && nowWall < lastWall)) {
            return;
        }
        todayDate = today;
        todayMs = 0;
        capNotified = false;
        persistToday();
    }

    private static String todayString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private int remainingMinutesCeil() {
        return (int) Math.ceil(remainingMs / 60000.0);
    }

    private int todayMinutes() {
        return (int) Math.round(todayMs / 60000.0);
    }

    private int watchedMinutesReport() {
        if (watchedMs <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(watchedMs / 60000.0));
    }

    private void persistSession() {
        Prefs.setSessionActive(ctx, sessionActive);
        Prefs.setSessionUnlimited(ctx, sessionUnlimited);
        Prefs.setSessionRemainingMs(ctx, remainingMs);
        Prefs.setSessionWatchedMs(ctx, watchedMs);
        Prefs.setSessionPkg(ctx, sessionPkg);
        Prefs.setSessionDate(ctx, sessionDate);
        Prefs.setSessionWarned(ctx, warned);
    }

    private void persistToday() {
        Prefs.setTodayDate(ctx, todayDate);
        Prefs.setTodayMs(ctx, todayMs);
        Prefs.setTodayWallMs(ctx, System.currentTimeMillis());
    }
}
