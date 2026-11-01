package com.tvtimer.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全屏攔截頁（gate）：由 WatchdogService 以 TYPE_APPLICATION_OVERLAY 疊加顯示，
 * 不啟動任何 Activity，因此不受 Android 10+ 背景啟動 Activity 的限制。
 * 全部操作只需遙控器方向鍵＋OK＋返回。
 *
 * 頁面：開始（含續看）／自訂時間／時間到／等待核准／PIN／訊息。
 */
public class GateController {

    public static final int PAGE_START = 0;
    public static final int PAGE_CUSTOM = 1;
    public static final int PAGE_TIMEUP = 2;
    public static final int PAGE_WAITING = 3;
    public static final int PAGE_PIN = 4;
    public static final int PAGE_MESSAGE = 5;
    public static final int PAGE_SET_PIN = 6;   // 強制設定新 PIN（SEC-02）
    public static final int PAGE_PAIRING = 7;   // 群組配對確認（SEC-01）

    public static final int PURPOSE_PARENT = 1;
    public static final int PURPOSE_SETTINGS = 2;

    public interface Callbacks {
        void onVisibilityChanged(boolean showing);

        /** 配對確認頁按「確認綁定」（SEC-01）。 */
        void onPairingConfirmed();

        /** 配對確認頁按「拒絕」（SEC-01）。 */
        void onPairingRejected();
    }

    /** 攔截返回鍵與數字鍵的根 View。 */
    static class GateRootView extends FrameLayout {
        interface KeyHandler {
            boolean onBackPressed();

            boolean onDigit(int d);

            boolean onDeleteDigit();

            boolean onCustomAdjust(boolean increase);
        }

        KeyHandler handler;

        GateRootView(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && handler != null) {
                int kc = e.getKeyCode();
                if (kc == KeyEvent.KEYCODE_BACK) {
                    if (handler.onBackPressed()) {
                        return true;
                    }
                } else if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
                    if (handler.onDigit(kc - KeyEvent.KEYCODE_0)) {
                        return true;
                    }
                } else if (kc == KeyEvent.KEYCODE_DEL) {
                    if (handler.onDeleteDigit()) {
                        return true;
                    }
                } else if (kc == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (handler.onCustomAdjust(false)) {
                        return true;
                    }
                } else if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (handler.onCustomAdjust(true)) {
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent(e);
        }
    }

    private final Context ctx;
    private final WindowManager wm;
    private final TimerEngine engine;
    private final Callbacks cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private GateRootView root;
    private LinearLayout content;
    private int currentPage = -1;
    private boolean deniedNote;
    private boolean sendFailedNote;
    private int pinPurpose = PURPOSE_PARENT;
    private int pinReturnPage = PAGE_START;
    private final StringBuilder pinBuffer = new StringBuilder();
    private TextView pinDots;
    private TextView pinError;
    /** 跨 rebuild 保留的一次性 PIN 頁錯誤訊息（改 PIN 兩段式流程用）。 */
    private String pinPageError;
    private int setPinStage; // 0 = 輸入新 PIN，1 = 再輸入一次確認
    private String setPinFirst;
    private int customMinutes = 45;
    private TextView customValue;
    private View warningView;

    public GateController(Context ctx, TimerEngine engine, Callbacks cb) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.engine = engine;
        this.cb = cb;
    }

    public boolean isShowing() {
        return root != null;
    }

    public int currentPage() {
        return currentPage;
    }

    /** 暫時性頁面：前台 App 改變時可自動關閉；PIN 與等待頁是黏著的。 */
    public boolean isTransientPage() {
        return currentPage == PAGE_START || currentPage == PAGE_TIMEUP
                || currentPage == PAGE_MESSAGE;
    }

    // ================= show / hide =================

    public void showPage(int page, int purpose, boolean denied) {
        if (purpose != 0) {
            pinPurpose = purpose;
        }
        deniedNote = denied;
        currentPage = page;
        android.util.Log.i("TvTimerWatchdog", "gate showPage=" + page); // FG-6
        if (page == PAGE_PIN || page == PAGE_SET_PIN) {
            pinBuffer.setLength(0);
        }
        if (page == PAGE_SET_PIN) {
            setPinStage = 0;
            setPinFirst = null;
        }
        ensureAttached();
        if (root != null) {
            rebuild();
        }
    }

    /** 核准請求送出失敗：回到指定頁面並顯示「送出失敗，請再試一次」（SEC-04）。 */
    public void showSendFailed(int page) {
        sendFailedNote = true;
        showPage(page, 0, false);
    }

    public void hide() {
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
            content = null;
            currentPage = -1;
            cb.onVisibilityChanged(false);
        }
    }

    /** 狀態變化時重畫目前頁面（保留焦點位置）。 */
    public void refresh() {
        if (root == null || currentPage == PAGE_PIN || currentPage == PAGE_CUSTOM
                || currentPage == PAGE_SET_PIN) {
            return;
        }
        String tag = null;
        View f = root.findFocus();
        if (f != null && f.getTag() instanceof String) {
            tag = (String) f.getTag();
        }
        rebuild();
        if (tag != null) {
            View v = findByTag(root, tag);
            if (v != null) {
                v.requestFocus();
            }
        }
    }

    /**
     * 剩 1 分鐘警告：上方橫幅，不可聚焦、不攔截操作，10 秒後自動消失，
     * 不中斷正在播放的影片。
     */
    public void showWarningBanner(int secondsLeft) {
        hideWarningBanner();
        TextView t = Ui.text(ctx, L.t(ctx, "⏰ 剩 ", "⏰ ") + Math.max(1, (int) Math.ceil(secondsLeft / 60.0))
                + L.t(ctx, " 分鐘", " min left"), 28, Color.rgb(20, 20, 20));
        t.setBackgroundColor(Ui.COLOR_ACCENT);
        t.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 10), Ui.dp(ctx, 24), Ui.dp(ctx, 10));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Ui.dp(ctx, 24);
        try {
            wm.addView(t, lp);
            warningView = t;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideWarningBanner();
                }
            }, 10_000);
        } catch (Exception ignored) {
            // 沒有懸浮窗權限時略過警告（安裝步驟會授予）
        }
    }

    private void hideWarningBanner() {
        if (warningView != null) {
            try {
                wm.removeView(warningView);
            } catch (Exception ignored) {
            }
            warningView = null;
        }
    }

    // ================= window plumbing =================

    private void ensureAttached() {
        if (root != null) {
            return;
        }
        root = new GateRootView(ctx);
        root.setBackgroundColor(Ui.COLOR_BG);
        root.setFocusable(true);
        root.handler = new GateRootView.KeyHandler() {
            @Override
            public boolean onBackPressed() {
                return handleBack();
            }

            @Override
            public boolean onDigit(int d) {
                if (currentPage == PAGE_PIN) {
                    appendPinDigit(d);
                    return true;
                }
                if (currentPage == PAGE_SET_PIN) {
                    appendSetPinDigit(d);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDeleteDigit() {
                if (currentPage != PAGE_PIN && currentPage != PAGE_SET_PIN) {
                    return false;
                }
                if (pinBuffer.length() > 0) {
                    pinBuffer.deleteCharAt(pinBuffer.length() - 1);
                    updatePinDots();
                }
                return true;
            }

            @Override
            public boolean onCustomAdjust(boolean increase) {
                if (currentPage != PAGE_CUSTOM) {
                    return false;
                }
                adjustCustom(increase ? 5 : -5);
                return true;
            }
        };
        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(Ui.dp(ctx, 64), Ui.dp(ctx, 32), Ui.dp(ctx, 64), Ui.dp(ctx, 32));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(content, clp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.FILL;
        try {
            wm.addView(root, lp);
            android.util.Log.i("TvTimerWatchdog", "gate addView OK"); // FG-6
        } catch (Exception e) {
            // 無懸浮窗權限等情況：不顯示，但絕不能讓看門狗行程崩潰
            android.util.Log.w("TvTimerWatchdog", "gate addView FAILED", e); // FG-6
            root = null;
            content = null;
            return;
        }
        cb.onVisibilityChanged(true);
    }

    private void rebuild() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        pinDots = null;
        pinError = null;
        customValue = null;
        TimerEngine.Snapshot s = engine.snapshot();
        View first = null;
        switch (currentPage) {
            case PAGE_CUSTOM:
                first = buildCustom();
                break;
            case PAGE_TIMEUP:
                first = buildTimeUp(s);
                break;
            case PAGE_WAITING:
                first = buildWaiting();
                break;
            case PAGE_PIN:
                first = buildPin();
                break;
            case PAGE_SET_PIN:
                first = buildSetPin();
                break;
            case PAGE_PAIRING:
                first = buildPairing();
                break;
            case PAGE_MESSAGE:
                first = buildMessage(s);
                break;
            case PAGE_START:
            default:
                first = buildStart(s);
                break;
        }
        final View f = first;
        if (f != null) {
            root.post(new Runnable() {
                @Override
                public void run() {
                    f.requestFocus();
                }
            });
        }
    }

    // ================= pages =================

    private View buildStart(TimerEngine.Snapshot s) {
        addTitle(L.t(ctx, "應用程式使用計時", "App Usage Timer"));
        addSub(L.t(ctx, "今天已看 ", "Watched ") + minutesOf(s.todayMs) + L.t(ctx, " 分鐘", " min today"));
        if (deniedNote) {
            addNote(L.t(ctx, "上一個請求已被爸媽拒絕。", "Your last request was denied by a parent."));
            deniedNote = false;
        }
        if (sendFailedNote) {
            addNote(L.t(ctx, "⚠ 請求送出失敗，請再試一次。", "⚠ Couldn't send the request. Please try again."));
            sendFailedNote = false;
        }
        View first = null;
        if (s.sessionActive) {
            Button resume = Ui.bigButton(ctx, L.t(ctx, "▶ 繼續上次：剩 ", "▶ Resume: ")
                    + (s.unlimited ? L.t(ctx, "不限制", "unlimited") : minutesOf(s.remainingMs) + L.t(ctx, " 分鐘", " min left")));
            resume.setTag("resume");
            resume.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (engine.gateResumeSession() == TimerEngine.GATE_STARTED) {
                        hide();
                    }
                }
            });
            content.addView(resume, Ui.rowParams(ctx));
            first = resume;
            addSub(L.t(ctx, "或是重新選一個時段：", "Or pick a new session:"));
        } else {
            addSub(L.t(ctx, "要看多久？", "How long do you want to watch?"));
        }
        if (s.capReached && !s.sessionActive) {
            addNote(L.t(ctx, "今天已達上限 " + Prefs.getDailyCapMin(ctx) + " 分鐘，開局需要爸媽核准。", "Today's " + Prefs.getDailyCapMin(ctx) + "-minute limit is reached. A parent must approve a new session."));
        }
        LinearLayout row = hRow();
        first = addDurationButton(row, L.t(ctx, "30 分鐘", "30 min"), 30, first);
        addDurationButton(row, L.t(ctx, "45 分鐘", "45 min"), 45, first);
        addDurationButton(row, L.t(ctx, "60 分鐘", "60 min"), 60, first);
        content.addView(row, Ui.rowParams(ctx));

        LinearLayout row2 = hRow();
        Button custom = Ui.bigButton(ctx, L.t(ctx, "自訂", "Custom"));
        custom.setTag("custom");
        custom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                customMinutes = 45;
                showPage(PAGE_CUSTOM, 0, false);
            }
        });
        row2.addView(custom, Ui.cellParams(ctx, 1));
        Button unlim = Ui.bigButton(ctx, L.t(ctx, "不限制（需爸媽核准）", "Unlimited (parent approval needed)"));
        unlim.setTag("unlim");
        unlim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.gateRequestUnlimited();
                showPage(PAGE_WAITING, 0, false);
            }
        });
        row2.addView(unlim, Ui.cellParams(ctx, 1));
        content.addView(row2, Ui.rowParams(ctx));

        Button parent = Ui.bigButton(ctx, L.t(ctx, "家長模式", "Parent mode"));
        parent.setTag("parent");
        parent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinPurpose = PURPOSE_PARENT;
                pinReturnPage = PAGE_START;
                showPage(PAGE_PIN, 0, false);
            }
        });
        content.addView(parent, Ui.rowParams(ctx));
        return first;
    }

    private View addDurationButton(LinearLayout row, String label, final int minutes, View first) {
        Button b = Ui.bigButton(ctx, label);
        b.setTag("d" + minutes);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseDuration(minutes);
            }
        });
        row.addView(b, Ui.cellParams(ctx, 1));
        return first == null ? b : first;
    }

    private void chooseDuration(int minutes) {
        if (engine.gateStartSession(minutes) == TimerEngine.GATE_STARTED) {
            hide();
        } else {
            showPage(PAGE_WAITING, 0, false);
        }
    }

    private View buildCustom() {
        addTitle(L.t(ctx, "自訂時間", "Custom time"));
        customValue = Ui.text(ctx, customMinutes + L.t(ctx, " 分鐘", " min"), 44, Ui.COLOR_TEXT);
        content.addView(customValue, Ui.rowParams(ctx));
        addSub(L.t(ctx, "遙控器左右鍵或下方按鈕調整（5～180 分鐘）", "Use the remote's ◀ ▶ keys or the buttons below (5–180 min)"));
        LinearLayout row = hRow();
        Button minus = Ui.bigButton(ctx, L.t(ctx, "－ 5 分", "－ 5 min"));
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustCustom(-5);
            }
        });
        row.addView(minus, Ui.cellParams(ctx, 1));
        Button plus = Ui.bigButton(ctx, L.t(ctx, "＋ 5 分", "＋ 5 min"));
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustCustom(5);
            }
        });
        row.addView(plus, Ui.cellParams(ctx, 1));
        content.addView(row, Ui.rowParams(ctx));

        LinearLayout row2 = hRow();
        Button ok = Ui.bigButton(ctx, L.t(ctx, "確定", "OK"));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseDuration(customMinutes);
            }
        });
        row2.addView(ok, Ui.cellParams(ctx, 1));
        Button cancel = Ui.bigButton(ctx, L.t(ctx, "取消", "Cancel"));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(PAGE_START, 0, false);
            }
        });
        row2.addView(cancel, Ui.cellParams(ctx, 1));
        content.addView(row2, Ui.rowParams(ctx));
        return ok;
    }

    private void adjustCustom(int delta) {
        customMinutes = Math.max(5, Math.min(180, customMinutes + delta));
        if (customValue != null) {
            customValue.setText(customMinutes + L.t(ctx, " 分鐘", " min"));
        }
    }

    private View buildTimeUp(TimerEngine.Snapshot s) {
        addTitle(L.t(ctx, "時間到了", "Time's up"));
        addSub(L.t(ctx, "今天已看 ", "Watched ") + minutesOf(s.todayMs) + L.t(ctx, " 分鐘", " min today"));
        if (deniedNote) {
            addNote(L.t(ctx, "爸媽沒有核准加時。", "A parent didn't approve more time."));
            deniedNote = false;
        }
        if (sendFailedNote) {
            addNote(L.t(ctx, "⚠ 請求送出失敗，請再試一次。", "⚠ Couldn't send the request. Please try again."));
            sendFailedNote = false;
        }
        Button more = Ui.bigButton(ctx, L.t(ctx, "請爸媽加時", "Ask a parent for more time"));
        more.setTag("more");
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.gateRequestExtension();
                showPage(PAGE_WAITING, 0, false);
            }
        });
        content.addView(more, Ui.rowParams(ctx));
        LinearLayout row = hRow();
        Button parent = Ui.bigButton(ctx, L.t(ctx, "家長模式", "Parent mode"));
        parent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinPurpose = PURPOSE_PARENT;
                pinReturnPage = PAGE_TIMEUP;
                showPage(PAGE_PIN, 0, false);
            }
        });
        row.addView(parent, Ui.cellParams(ctx, 1));
        Button leave = Ui.bigButton(ctx, L.t(ctx, "離開", "Leave"));
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissByUser();
            }
        });
        row.addView(leave, Ui.cellParams(ctx, 1));
        content.addView(row, Ui.rowParams(ctx));
        return more;
    }

    private View buildWaiting() {
        addTitle(L.t(ctx, "等待爸媽核准中…", "Waiting for a parent…"));
        addSub(L.t(ctx, "請求已送到 Telegram，核准後會自動開始。", "Request sent to Telegram. It starts automatically once approved."));
        Button cancel = Ui.bigButton(ctx, L.t(ctx, "先離開", "Leave for now"));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissByUser();
            }
        });
        content.addView(cancel, Ui.rowParams(ctx));
        return cancel;
    }

    private View buildPin() {
        addTitle(pinPurpose == PURPOSE_SETTINGS ? L.t(ctx, "家長設定", "Parent settings") : L.t(ctx, "家長模式", "Parent mode"));
        addSub(L.t(ctx, "請輸入 4 位數 PIN", "Enter your 4-digit PIN"));
        pinDots = Ui.text(ctx, "", 40, Ui.COLOR_ACCENT);
        content.addView(pinDots, Ui.rowParams(ctx));
        updatePinDots();
        pinError = Ui.text(ctx, "", 20, Color.rgb(239, 154, 154));
        content.addView(pinError, Ui.rowParams(ctx));
        if (pinPageError != null) {
            pinError.setText(pinPageError);
            pinPageError = null;
        } else if (Prefs.pinLockoutRemainingMs(ctx) > 0) {
            pinError.setText(lockoutText());
        }

        View first = addPinGrid();
        Button tg = Ui.bigButton(ctx, L.t(ctx, "改用 Telegram 核准", "Use Telegram approval instead"));
        tg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.gateRequestParentApproval(pinPurpose);
                showPage(PAGE_WAITING, 0, false);
            }
        });
        content.addView(tg, Ui.rowParams(ctx));
        return first;
    }

    /** 強制設定新 PIN 頁（SEC-02）：兩段式輸入（新 PIN＋再輸入一次確認），設定完成前不得離開。 */
    private View buildSetPin() {
        addTitle(L.t(ctx, "設定新 PIN", "Set a new PIN"));
        if (setPinStage == 0) {
            addSub(L.t(ctx, "PIN 仍為預設值 0000，請設定新的 4 位數 PIN", "PIN is still the default 0000. Set a new 4-digit PIN."));
            addSub(L.t(ctx, "設定完成前，無法使用家長模式與設定", "Parent mode and settings stay unavailable until this is done."));
        } else {
            addSub(L.t(ctx, "請再輸入一次新 PIN 以確認", "Enter the new PIN again to confirm"));
        }
        pinDots = Ui.text(ctx, "", 40, Ui.COLOR_ACCENT);
        content.addView(pinDots, Ui.rowParams(ctx));
        updatePinDots();
        pinError = Ui.text(ctx, "", 20, Color.rgb(239, 154, 154));
        content.addView(pinError, Ui.rowParams(ctx));
        if (pinPageError != null) {
            pinError.setText(pinPageError);
            pinPageError = null;
        }
        return addPinGrid();
    }

    /** 配對確認頁（SEC-01）：顯示請求配對的群組名稱，由家長在 TV 端確認或拒絕。 */
    private View buildPairing() {
        addTitle(L.t(ctx, "群組配對請求", "Group pairing request"));
        long pairId = Prefs.pendingPairingId(ctx);
        if (pairId == 0) {
            addSub(L.t(ctx, "這個配對請求已過期，請在群組再發一句話重新請求。", "This pairing request has expired. Send another message in the group to request again."));
            Button ok = Ui.bigButton(ctx, L.t(ctx, "確定", "OK"));
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissByUser();
                }
            });
            content.addView(ok, Ui.rowParams(ctx));
            return ok;
        }
        String title = Prefs.getPairTitle(ctx);
        addSub(L.t(ctx, "有群組請求配對：『", "Pairing request from group '") + (title.isEmpty() ? L.t(ctx, "（未命名群組）", "(unnamed group)") : title) + L.t(ctx, "』", "'"));
        addNote(L.t(ctx, "確認是你們家的雙親群組再綁定；綁定後回報與核准都會送到該群組。", "Make sure it's your family's parents' group before pairing. Reports and approvals will go to that group."));
        LinearLayout row = hRow();
        Button confirm = Ui.bigButton(ctx, L.t(ctx, "確認綁定", "Confirm pairing"));
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onPairingConfirmed();
            }
        });
        row.addView(confirm, Ui.cellParams(ctx, 1));
        Button reject = Ui.bigButton(ctx, L.t(ctx, "拒絕", "Deny"));
        reject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onPairingRejected();
            }
        });
        row.addView(reject, Ui.cellParams(ctx, 1));
        content.addView(row, Ui.rowParams(ctx));
        return confirm;
    }

    /** PIN／改 PIN 頁共用的螢幕數字鍵盤；按鍵依目前頁面分派。回傳第一個按鈕（初始焦點）。 */
    private View addPinGrid() {
        // v6：按鍵標籤同時用於顯示與分派，必須以同一組（依語言選出的）字串比對
        final String clearLabel = L.t(ctx, "清除", "Clear");
        final String deleteLabel = L.t(ctx, "刪除", "Delete");
        String[][] grid = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {clearLabel, "0", deleteLabel},
        };
        View first = null;
        for (String[] rowLabels : grid) {
            LinearLayout row = hRow();
            for (final String label : rowLabels) {
                Button b = Ui.bigButton(ctx, label);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (clearLabel.equals(label)) {
                            pinBuffer.setLength(0);
                            updatePinDots();
                        } else if (deleteLabel.equals(label)) {
                            if (pinBuffer.length() > 0) {
                                pinBuffer.deleteCharAt(pinBuffer.length() - 1);
                                updatePinDots();
                            }
                        } else if (currentPage == PAGE_SET_PIN) {
                            appendSetPinDigit(Integer.parseInt(label));
                        } else {
                            appendPinDigit(Integer.parseInt(label));
                        }
                    }
                });
                row.addView(b, Ui.cellParams(ctx, 1));
                if (first == null) {
                    first = b;
                }
            }
            content.addView(row, Ui.rowParams(ctx));
        }
        return first;
    }

    private String lockoutText() {
        long remainMs = Prefs.pinLockoutRemainingMs(ctx);
        long minutes = Math.max(1, (long) Math.ceil(remainMs / 60000.0));
        return L.t(ctx, "PIN 輸入錯誤太多次，已鎖定，請等 ", "Too many wrong PIN entries. Locked — try again in ") + minutes + L.t(ctx, " 分鐘後再試", " min");
    }

    private void appendPinDigit(int d) {
        if (Prefs.pinLockoutRemainingMs(ctx) > 0) {
            pinBuffer.setLength(0);
            if (pinError != null) {
                pinError.setText(lockoutText());
            }
            updatePinDots();
            return;
        }
        if (pinBuffer.length() >= 4) {
            return;
        }
        pinBuffer.append(d);
        updatePinDots();
        if (pinBuffer.length() == 4) {
            String pin = pinBuffer.toString();
            pinBuffer.setLength(0);
            int result = engine.gateSubmitPin(pin, pinPurpose);
            if (result == TimerEngine.PIN_OK) {
                hide();
            } else if (result == TimerEngine.PIN_MUST_CHANGE) {
                // engine 已把 gate 導向強制設定新 PIN 頁，這裡不做任何事
            } else if (pinError != null) {
                pinError.setText(result == TimerEngine.PIN_LOCKED
                        ? lockoutText() : L.t(ctx, "PIN 不正確，請再試一次", "Incorrect PIN. Try again."));
                updatePinDots();
            }
        }
    }

    private void appendSetPinDigit(int d) {
        if (pinBuffer.length() >= 4) {
            return;
        }
        pinBuffer.append(d);
        updatePinDots();
        if (pinBuffer.length() < 4) {
            return;
        }
        String pin = pinBuffer.toString();
        pinBuffer.setLength(0);
        if (setPinStage == 0) {
            if (Prefs.DEFAULT_PIN.equals(pin)) {
                if (pinError != null) {
                    pinError.setText(L.t(ctx, "新 PIN 不能使用預設值 0000，請換一組", "New PIN can't be the default 0000. Pick another one."));
                }
                updatePinDots();
                return;
            }
            setPinFirst = pin;
            setPinStage = 1;
            rebuild();
        } else {
            if (pin.equals(setPinFirst)) {
                if (!engine.gateSetNewPin(pin)) {
                    setPinStage = 0;
                    setPinFirst = null;
                    pinPageError = L.t(ctx, "新 PIN 無法使用，請重新設定", "That PIN can't be used. Set it again.");
                    rebuild();
                }
                // 成功時 engine 會開啟家長模式並隱藏 gate
            } else {
                setPinStage = 0;
                setPinFirst = null;
                pinPageError = L.t(ctx, "兩次輸入不一致，請重新設定", "The two entries don't match. Set it again.");
                rebuild();
            }
        }
    }

    private void updatePinDots() {
        if (pinDots == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(i < pinBuffer.length() ? "●" : "○");
            if (i < 3) {
                sb.append(' ');
            }
        }
        pinDots.setText(sb.toString());
    }

    private View buildMessage(TimerEngine.Snapshot s) {
        addTitle(L.t(ctx, "已鎖定", "Locked"));
        String msg = s.messageText != null && !s.messageText.isEmpty()
                ? s.messageText : L.t(ctx, "這個功能已鎖定。", "This feature is locked.");
        addSub(msg);
        LinearLayout row = hRow();
        Button parent = Ui.bigButton(ctx, L.t(ctx, "家長模式", "Parent mode"));
        parent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinPurpose = PURPOSE_PARENT;
                pinReturnPage = PAGE_MESSAGE;
                showPage(PAGE_PIN, 0, false);
            }
        });
        row.addView(parent, Ui.cellParams(ctx, 1));
        Button ok = Ui.bigButton(ctx, L.t(ctx, "確定", "OK"));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissByUser();
            }
        });
        row.addView(ok, Ui.cellParams(ctx, 1));
        content.addView(row, Ui.rowParams(ctx));
        return ok;
    }

    // ================= key handling / helpers =================

    private boolean handleBack() {
        switch (currentPage) {
            case PAGE_CUSTOM:
                showPage(PAGE_START, 0, false);
                return true;
            case PAGE_PIN:
                showPage(pinReturnPage, 0, false);
                return true;
            case PAGE_SET_PIN:
                // 強制改 PIN（SEC-02）：設定完成前返回鍵不離開本頁
                return true;
            case PAGE_START:
            case PAGE_TIMEUP:
            case PAGE_WAITING:
            case PAGE_MESSAGE:
            case PAGE_PAIRING:
                dismissByUser();
                return true;
            default:
                return true; // gate 開著時一律攔截返回鍵
        }
    }

    private void dismissByUser() {
        hide();
        engine.gateDismissed();
    }

    private LinearLayout hRow() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private void addTitle(String s) {
        TextView t = Ui.text(ctx, s, 38, Ui.COLOR_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(t, Ui.rowParams(ctx));
    }

    private void addSub(String s) {
        content.addView(Ui.text(ctx, s, 22, Ui.COLOR_TEXT_DIM), Ui.rowParams(ctx));
    }

    private void addNote(String s) {
        content.addView(Ui.text(ctx, s, 22, Color.rgb(239, 154, 154)), Ui.rowParams(ctx));
    }

    private static int minutesOf(long ms) {
        return (int) Math.round(ms / 60000.0);
    }

    private static View findByTag(View v, String tag) {
        if (tag.equals(v.getTag())) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByTag(g.getChildAt(i), tag);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
