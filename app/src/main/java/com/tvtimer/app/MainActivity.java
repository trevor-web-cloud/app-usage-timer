package com.tvtimer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 狀態／設定畫面（leanback 可啟動）。
 * - 狀態區：token、配對、權限、今日累計、目前狀態，每 2 秒刷新。
 * - 設定區（變更 PIN、觀看清單、清除配對、家長模式開關）需先以 PIN 解鎖；
 *   家長模式進行中時自動視為已解鎖。
 * 鎖定狀態下小孩打開本畫面會被看門狗攔下並要求 PIN（見 TimerEngine.isBlockedApp）。
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean settingsUnlocked;
    private TextView statusView;
    private TextView permView;
    private Button permRestrictedBtn;
    private Button permA11yBtn;
    private Button permUsageBtn;
    private Button permOverlayBtn;
    private Button permBatteryBtn;
    private LinearLayout settingsBox;
    /** WL-1：觀看清單專用容器（自訂 App 增刪後可單獨重建，不必重開畫面）。 */
    private LinearLayout watchBox;
    private LinearLayout changePinBox;
    private TextView pinChangeMsg;
    /** 已以預設 PIN 解鎖、被授權執行強制改 PIN（SEC-02）。 */
    private boolean pinChangeArmed;
    private LinearLayout pairingBox;
    private TextView pairingText;
    /** WEB-1（v14）：手機網頁輸入 token 的一次性區網伺服器與其對話框。 */
    private TokenWebServer webServer;
    private AlertDialog webDialog;
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 2_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.importTokenIfNeeded(this);
        WatchdogService.start(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresher);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // WEB-1：離開畫面即停服，不讓一次性設定頁在背景殘留
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        super.onDestroy();
    }

    // ================= UI =================

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.COLOR_BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 48), Ui.dp(this, 32), Ui.dp(this, 48), Ui.dp(this, 32));
        scroll.addView(col);

        TextView title = Ui.text(this, L.t(this, "應用程式使用計時", "App Usage Timer"), 34, Ui.COLOR_TEXT);
        title.setGravity(android.view.Gravity.LEFT);
        col.addView(title, Ui.rowParams(this));

        statusView = Ui.text(this, "", 22, Ui.COLOR_TEXT);
        statusView.setGravity(android.view.Gravity.LEFT);
        col.addView(statusView, Ui.rowParams(this));

        permView = Ui.text(this, "", 20, Ui.COLOR_TEXT_DIM);
        permView.setGravity(android.view.Gravity.LEFT);
        col.addView(permView, Ui.rowParams(this));

        // --- PERM-4（v13）：權限開啟順序編號清單（常駐可用，不受設定鎖定影響） ---
        // 順序有硬相依：①受限制的設定未開，②無障礙會直接遭拒（2026-11-01 平板實證）。
        LinearLayout permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permRestrictedBtn = Ui.bigButton(this, "");
        permRestrictedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppDetails();
            }
        });
        permBox.addView(permRestrictedBtn, Ui.rowParams(this));
        permA11yBtn = Ui.bigButton(this, "");
        permA11yBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsIntent(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        permBox.addView(permA11yBtn, Ui.rowParams(this));
        permUsageBtn = Ui.bigButton(this, "");
        permUsageBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsIntent(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        permBox.addView(permUsageBtn, Ui.rowParams(this));
        permOverlayBtn = Ui.bigButton(this, "");
        permOverlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsIntent(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        permBox.addView(permOverlayBtn, Ui.rowParams(this));
        permBatteryBtn = Ui.bigButton(this, "");
        permBatteryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        permBox.addView(permBatteryBtn, Ui.rowParams(this));
        TextView permHeader = Ui.text(this, L.t(this, "權限設定（依編號順序開啟）", "Permissions (open in numbered order)"), 20, Ui.COLOR_TEXT);
        permHeader.setGravity(android.view.Gravity.LEFT);
        col.addView(permHeader, Ui.rowParams(this));
        col.addView(permBox, Ui.rowParams(this));

        // --- PIN 解鎖 ---
        LinearLayout pinRow = new LinearLayout(this);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText pinInput = pinEditText(L.t(this, "輸入 PIN 解鎖設定", "Enter PIN to unlock settings"));
        pinRow.addView(pinInput, Ui.cellParams(this, 2));
        Button unlock = Ui.bigButton(this, L.t(this, "解鎖設定", "Unlock settings"));
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 與 gate 共用同一組節流計數（SEC-03）
                long lockedMs = Prefs.pinLockoutRemainingMs(MainActivity.this);
                if (lockedMs > 0) {
                    pinInput.setText("");
                    pinInput.setHint(L.t(MainActivity.this, "PIN 已鎖定，請等 ", "PIN locked. Wait ")
                            + Math.max(1, (long) Math.ceil(lockedMs / 60000.0)) + L.t(MainActivity.this, " 分鐘", " min"));
                    return;
                }
                String pin = pinInput.getText().toString().trim();
                if (pin.equals(Prefs.getPin(MainActivity.this))) {
                    Prefs.resetPinFailures(MainActivity.this);
                    pinInput.setText("");
                    if (Prefs.DEFAULT_PIN.equals(Prefs.getPin(MainActivity.this))) {
                        // 仍是預設 PIN：先完成下方強制設定新 PIN，才開放設定（SEC-02）
                        pinChangeArmed = true;
                        pinInput.setHint(L.t(MainActivity.this, "請先在下方設定新 PIN", "Set a new PIN below first"));
                    } else {
                        settingsUnlocked = true;
                        pinInput.setHint(L.t(MainActivity.this, "已解鎖", "Unlocked"));
                    }
                    applyUnlockState();
                } else {
                    long remain = Prefs.recordPinFailure(MainActivity.this);
                    pinInput.setText("");
                    pinInput.setHint(remain > 0
                            ? L.t(MainActivity.this, "錯誤太多次，PIN 已鎖定 ", "Too many wrong tries. PIN locked for ")
                                    + Math.max(1, (long) Math.ceil(remain / 60000.0)) + L.t(MainActivity.this, " 分鐘", " min")
                            : L.t(MainActivity.this, "PIN 不正確，請重試", "Incorrect PIN, try again"));
                }
            }
        });
        pinRow.addView(unlock, Ui.cellParams(this, 1));
        col.addView(pinRow, Ui.rowParams(this));

        // --- 強制設定新 PIN（SEC-02）：PIN 仍為預設值時顯示，位於設定區之外 ---
        changePinBox = new LinearLayout(this);
        changePinBox.setOrientation(LinearLayout.VERTICAL);
        TextView changePinTitle = Ui.text(this,
                L.t(this, "⚠ PIN 仍為預設值 0000，必須先設定新 PIN 才能使用設定", "⚠ PIN is still the default 0000. Set a new PIN to use settings."), 22, Ui.COLOR_TEXT);
        changePinTitle.setGravity(android.view.Gravity.LEFT);
        changePinBox.addView(changePinTitle, Ui.rowParams(this));
        LinearLayout setPinRow = new LinearLayout(this);
        setPinRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText setPinInput = pinEditText(L.t(this, "新 PIN（4 位數字）", "New PIN (4 digits)"));
        setPinRow.addView(setPinInput, Ui.cellParams(this, 1));
        final EditText setPinConfirm = pinEditText(L.t(this, "再輸入一次新 PIN", "Enter the new PIN again"));
        setPinRow.addView(setPinConfirm, Ui.cellParams(this, 1));
        Button setPinBtn = Ui.bigButton(this, L.t(this, "設定新 PIN", "Set new PIN"));
        setPinBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = setPinInput.getText().toString().trim();
                String q = setPinConfirm.getText().toString().trim();
                if (p.length() != 4 || !p.matches("[0-9]{4}")) {
                    pinChangeMsg.setText(L.t(MainActivity.this, "新 PIN 必須是 4 位數字", "New PIN must be 4 digits"));
                } else if (Prefs.DEFAULT_PIN.equals(p)) {
                    pinChangeMsg.setText(L.t(MainActivity.this, "新 PIN 不能使用預設值 0000，請換一組", "New PIN can't be the default 0000. Pick another one."));
                } else if (!p.equals(q)) {
                    pinChangeMsg.setText(L.t(MainActivity.this, "兩次輸入不一致，請重新輸入", "The two entries don't match. Enter them again."));
                } else {
                    Prefs.setPin(MainActivity.this, p);
                    setPinInput.setText("");
                    setPinConfirm.setText("");
                    pinChangeArmed = false;
                    settingsUnlocked = true;
                    pinChangeMsg.setText("");
                    applyUnlockState();
                    refreshStatus();
                }
            }
        });
        setPinRow.addView(setPinBtn, Ui.cellParams(this, 1));
        changePinBox.addView(setPinRow, Ui.rowParams(this));
        pinChangeMsg = Ui.text(this, "", 20, Ui.COLOR_TEXT_DIM);
        pinChangeMsg.setGravity(android.view.Gravity.LEFT);
        changePinBox.addView(pinChangeMsg, Ui.rowParams(this));
        col.addView(changePinBox, Ui.rowParams(this));

        // --- 待確認配對（SEC-01）：有群組請求配對時顯示 ---
        pairingBox = new LinearLayout(this);
        pairingBox.setOrientation(LinearLayout.VERTICAL);
        pairingText = Ui.text(this, "", 22, Ui.COLOR_TEXT);
        pairingText.setGravity(android.view.Gravity.LEFT);
        pairingBox.addView(pairingText, Ui.rowParams(this));
        LinearLayout pairRow = new LinearLayout(this);
        pairRow.setOrientation(LinearLayout.HORIZONTAL);
        Button pairConfirm = Ui.bigButton(this, L.t(this, "確認綁定", "Confirm pairing"));
        pairConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WatchdogService.confirmPairingFromUi(MainActivity.this);
                refreshStatus();
            }
        });
        pairRow.addView(pairConfirm, Ui.cellParams(this, 1));
        Button pairReject = Ui.bigButton(this, L.t(this, "拒絕", "Deny"));
        pairReject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WatchdogService.rejectPairingFromUi(MainActivity.this);
                refreshStatus();
            }
        });
        pairRow.addView(pairReject, Ui.cellParams(this, 1));
        pairingBox.addView(pairRow, Ui.rowParams(this));
        col.addView(pairingBox, Ui.rowParams(this));

        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(settingsBox, Ui.rowParams(this));

        // --- GRP-1（v13）：設定頁群組化，依初次設定進行順序排列（項目稍後移入） ---
        final LinearLayout grpToken = settingsGroup("步驟一：Bot Token", "Step 1: Bot token");
        final LinearLayout grpPair = settingsGroup("步驟二：Telegram 配對", "Step 2: Telegram pairing");
        final LinearLayout grpWatch = settingsGroup("步驟三：限制的 App", "Step 3: Restricted apps");
        final LinearLayout grpParent = settingsGroup("家長模式", "Parent mode");
        final LinearLayout grpOther = settingsGroup("其他設定", "Other settings");

        // --- 變更 PIN ---
        LinearLayout newPinRow = new LinearLayout(this);
        newPinRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText newPinInput = pinEditText(L.t(this, "新 PIN（4 位數字）", "New PIN (4 digits)"));
        newPinRow.addView(newPinInput, Ui.cellParams(this, 2));
        Button changePin = Ui.bigButton(this, L.t(this, "變更 PIN", "Change PIN"));
        changePin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = newPinInput.getText().toString().trim();
                if (p.length() == 4 && p.matches("[0-9]{4}")) {
                    Prefs.setPin(MainActivity.this, p);
                    newPinInput.setText("");
                    newPinInput.setHint(L.t(MainActivity.this, "PIN 已變更", "PIN changed"));
                } else {
                    newPinInput.setText("");
                    newPinInput.setHint(L.t(MainActivity.this, "請輸入 4 位數字", "Enter 4 digits"));
                }
            }
        });
        newPinRow.addView(changePin, Ui.cellParams(this, 1));
        settingsBox.addView(newPinRow, Ui.rowParams(this));

        // --- 觀看清單開關（WL-1：內容由 rebuildWatchList 動態產生） ---
        watchBox = new LinearLayout(this);
        watchBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.addView(watchBox, Ui.rowParams(this));
        rebuildWatchList();

        // --- 家長模式 ---
        LinearLayout parentRow = new LinearLayout(this);
        parentRow.setOrientation(LinearLayout.HORIZONTAL);
        Button parentOn = Ui.bigButton(this, L.t(this, "進入家長模式", "Start parent mode"));
        parentOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendServiceAction(WatchdogService.ACTION_PARENT_ON);
            }
        });
        parentRow.addView(parentOn, Ui.cellParams(this, 1));
        Button parentOff = Ui.bigButton(this, L.t(this, "結束家長模式", "End parent mode"));
        parentOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendServiceAction(WatchdogService.ACTION_PARENT_OFF);
                // PAD-1（v11）：結束家長模式後自動回主畫面並關閉設定頁，
                // 避免畫面停留在已解鎖的設定頁讓小孩接手操作
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(home);
                } catch (Exception ignored) {
                    // 找不到 HOME 時至少關閉本頁
                }
                finish();
            }
        });
        parentRow.addView(parentOff, Ui.cellParams(this, 1));
        settingsBox.addView(parentRow, Ui.rowParams(this));

        // --- 配對 / token ---
        Button clearPairing = Ui.bigButton(this, L.t(this, "清除 Telegram 配對", "Clear Telegram pairing"));
        clearPairing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TOK-2（v12）：動作要有明確反饋，不再默默執行
                boolean wasBound = Prefs.getChatId(MainActivity.this) != 0
                        || Prefs.pendingPairingId(MainActivity.this) != 0;
                Prefs.setChatId(MainActivity.this, 0);
                Prefs.setChatTitle(MainActivity.this, "");
                Prefs.clearPendingPairing(MainActivity.this);
                toast(wasBound
                        ? L.t(MainActivity.this, "已清除配對", "Pairing cleared")
                        : L.t(MainActivity.this, "目前尚未配對，沒有可清除的綁定", "Not paired — nothing to clear"));
                refreshStatus();
            }
        });
        settingsBox.addView(clearPairing, Ui.rowParams(this));

        Button rereadToken = Ui.bigButton(this, L.t(this, "重新讀取 token.txt", "Reload token.txt"));
        rereadToken.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TOK-1（v12）：改為可覆寫＋三態反饋（TOK-2）。
                // v11 以前已有 token 就跳過匯入，匯錯 token 只能清除資料解套。
                String fileToken = Prefs.readTokenFile(MainActivity.this);
                String current = Prefs.getToken(MainActivity.this);
                if (fileToken == null) {
                    toast(L.t(MainActivity.this, "找不到 token.txt 或內容不正確", "token.txt not found or invalid"));
                } else if (fileToken.equals(current)) {
                    toast(L.t(MainActivity.this, "檔案內容與目前 token 相同，未變更", "File matches the current token — unchanged"));
                } else {
                    Prefs.applyTokenChange(MainActivity.this, fileToken);
                    WatchdogService.start(MainActivity.this);
                    toast(current.isEmpty()
                            ? L.t(MainActivity.this, "已匯入 token，請在群組發訊息配對", "Token imported — send a message in the group to pair")
                            : L.t(MainActivity.this, "已換上新 token，配對已清除，請重新配對", "Token replaced and pairing cleared — please pair again"));
                }
                refreshStatus();
            }
        });
        settingsBox.addView(rereadToken, Ui.rowParams(this));

        // --- TOK-1（v12）：清除 token（連同綁定與 offset 一併重置） ---
        Button clearToken = Ui.bigButton(this, L.t(this, "清除 Bot Token", "Clear bot token"));
        clearToken.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Prefs.getToken(MainActivity.this).isEmpty()) {
                    toast(L.t(MainActivity.this, "目前沒有設定 token", "No token is set"));
                } else {
                    Prefs.applyTokenChange(MainActivity.this, "");
                    WatchdogService.start(MainActivity.this);
                    toast(L.t(MainActivity.this, "已清除 token 與配對", "Token and pairing cleared"));
                }
                refreshStatus();
            }
        });
        settingsBox.addView(clearToken, Ui.rowParams(this));

        // --- PAD-2（v11）：直達「所有檔案存取權」設定頁（平板裝機時此頁深埋難找） ---
        Button fileAccess = Ui.bigButton(this, L.t(this, "開啟檔案存取設定（匯入 token 用）", "Open file-access settings (for token import)"));
        fileAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ignored) {
                        // 此裝置無對應設定頁時靜默（電視端以 ADB appops 給權限）
                    }
                }
            }
        });
        settingsBox.addView(fileAccess, Ui.rowParams(this));

        // --- TOK-3（v12）：貼上 token（平板上建 token.txt 太困難，改以輸入窗貼上） ---
        Button pasteToken = Ui.bigButton(this, L.t(this, "貼上 Bot Token", "Paste bot token"));
        pasteToken.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(MainActivity.this);
                input.setHint(L.t(MainActivity.this, "貼上 BotFather 給的 token", "Paste the token from BotFather"));
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(L.t(MainActivity.this, "貼上 Bot Token", "Paste bot token"))
                        .setView(input)
                        .setPositiveButton(L.t(MainActivity.this, "驗證並儲存", "Verify & save"), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final String candidate = input.getText().toString().trim();
                                if (candidate.isEmpty()) {
                                    toast(L.t(MainActivity.this, "沒有輸入內容", "Nothing was entered"));
                                    return;
                                }
                                if (candidate.equals(Prefs.getToken(MainActivity.this))) {
                                    toast(L.t(MainActivity.this, "與目前 token 相同，未變更", "Same as the current token — unchanged"));
                                    return;
                                }
                                toast(L.t(MainActivity.this, "驗證中…", "Verifying…"));
                                // 先以 getMe 驗證並回顯 bot 名稱核對，成功才存檔（不盲存）
                                TelegramClient.validateTokenAsync(candidate, new TelegramClient.ValidationCallback() {
                                    @Override
                                    public void onResult(final boolean ok, final String username) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (ok) {
                                                    boolean hadToken = !Prefs.getToken(MainActivity.this).isEmpty();
                                                    Prefs.applyTokenChange(MainActivity.this, candidate);
                                                    WatchdogService.start(MainActivity.this);
                                                    toast(hadToken
                                                            ? L.t(MainActivity.this, "已換上新 token（@" + username + "），請重新配對", "Token replaced (@" + username + ") — please pair again")
                                                            : L.t(MainActivity.this, "已儲存 token（@" + username + "），請在群組發訊息配對", "Token saved (@" + username + ") — send a message in the group to pair"));
                                                } else {
                                                    toast(L.t(MainActivity.this, "驗證失敗，token 不正確，未變更", "Verification failed — token not changed"));
                                                }
                                                refreshStatus();
                                            }
                                        });
                                    }
                                });
                            }
                        })
                        .setNegativeButton(L.t(MainActivity.this, "取消", "Cancel"), null)
                        .show();
            }
        });
        settingsBox.addView(pasteToken, Ui.rowParams(this));

        // --- WEB-1（v14）：用手機設定 token（電視遙控器打 45 字元不切實際；
        // 改開一次性區網頁面讓家長用手機貼上，成功/逾時/取消即關閉） ---
        Button webToken = Ui.bigButton(this, L.t(this, "用手機設定 Token（電視推薦）", "Set token from phone (recommended for TV)"));
        webToken.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startWebTokenSetup();
            }
        });
        settingsBox.addView(webToken, Ui.rowParams(this));

        // --- v6：介面語言切換（按鈕文字雙語並列，不經 L.t） ---
        Button langBtn = Ui.bigButton(this, "介面語言 / Language：中文 ⇄ English");
        langBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setLang(MainActivity.this, L.isEn(MainActivity.this) ? "zh" : "en");
                recreate();
            }
        });
        settingsBox.addView(langBtn, Ui.rowParams(this));

        // --- CAP-CUSTOM（v10）：每日上限自訂（預設 120 分） ---
        Button capBtn = Ui.bigButton(this, capButtonText());
        capBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCapDialog();
            }
        });
        settingsBox.addView(capBtn, Ui.rowParams(this));

        // --- GRP-1（v13）：把設定項移入群組（建構順序不動，只改歸屬與顯示順序） ---
        moveToGroup(grpToken, webToken, fileAccess, pasteToken, rereadToken, clearToken);
        moveToGroup(grpPair, clearPairing);
        moveToGroup(grpWatch, watchBox);
        moveToGroup(grpParent, parentRow);
        moveToGroup(grpOther, capBtn, langBtn, newPinRow);

        setContentView(scroll);
        applyUnlockState();
        refreshStatus();
    }

    /** CAP-CUSTOM（v10）：每日上限按鈕文字（顯示目前值）。 */
    private String capButtonText() {
        return L.t(this, "每日上限：", "Daily limit: ") + Prefs.getDailyCapMin(this)
                + L.t(this, " 分鐘（點擊更改）", " min (tap to change)");
    }

    /** CAP-CUSTOM（v10）：每日上限選擇（預設值清單＋自訂）。 */
    private void showCapDialog() {
        final int[] presets = {60, 90, 120, 150, 180, 240};
        String[] items = new String[presets.length + 1];
        for (int i = 0; i < presets.length; i++) {
            items[i] = presets[i] + L.t(this, " 分鐘", " min");
        }
        items[presets.length] = L.t(this, "自訂…", "Custom…");
        new AlertDialog.Builder(this)
                .setTitle(L.t(this, "每日上限", "Daily limit"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which < presets.length) {
                            Prefs.setDailyCapMin(MainActivity.this, presets[which]);
                            recreate();
                        } else {
                            showCapCustomDialog();
                        }
                    }
                })
                .show();
    }

    /** CAP-CUSTOM（v10）：自訂每日上限（數字輸入，Prefs 端夾限 10–600）。 */
    private void showCapCustomDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Prefs.getDailyCapMin(this)));
        input.setTextSize(20);
        input.setTextColor(Ui.COLOR_TEXT);
        input.setBackgroundColor(Ui.COLOR_PANEL);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(L.t(this, "自訂每日上限（10–600 分鐘）", "Custom daily limit (10–600 min)"))
                .setView(input)
                .setPositiveButton(L.t(this, "確定", "OK"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            Prefs.setDailyCapMin(MainActivity.this,
                                    Integer.parseInt(input.getText().toString().trim()));
                        } catch (NumberFormatException ignored) {
                            // 輸入無效時不更改
                        }
                        recreate();
                    }
                })
                .setNegativeButton(L.t(this, "取消", "Cancel"), null)
                .show();
    }

    private EditText pinEditText(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(20);
        e.setTextColor(Ui.COLOR_TEXT);
        e.setHintTextColor(Ui.COLOR_TEXT_DIM);
        e.setBackgroundColor(Ui.COLOR_PANEL);
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setSingleLine(true);
        return e;
    }

    private String watchLabel(String pkg) {
        boolean on = Prefs.getWatchEnabled(this, pkg);
        return TimerEngine.appName(this, pkg) + L.t(this, "：", ": ") + (on ? L.t(this, "開啟", "On") : L.t(this, "關閉", "Off"));
    }

    private Button watchToggleButton(final String pkg) {
        final Button toggle = Ui.bigButton(this, "");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !Prefs.getWatchEnabled(MainActivity.this, pkg);
                Prefs.setWatchEnabled(MainActivity.this, pkg, now);
                toggle.setText(watchLabel(pkg));
            }
        });
        toggle.setText(watchLabel(pkg));
        toggle.setTag("watch:" + pkg);
        return toggle;
    }

    /** WL-1：重建觀看清單（預設清單＋自訂清單＋新增按鈕）。 */
    /** WL-3：一列清單（開關＋移除）。preset=true 時「移除」是加入移除集合（可加回），否則刪自訂條目。 */
    private LinearLayout watchRow(final String pkg, final boolean preset) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(watchToggleButton(pkg), Ui.cellParams(this, 3));
        Button remove = Ui.bigButton(this, L.t(this, "移除", "Remove"));
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (preset) {
                    Prefs.setPresetRemoved(MainActivity.this, pkg, true);
                } else {
                    Prefs.removeCustomWatch(MainActivity.this, pkg);
                }
                rebuildWatchList();
            }
        });
        row.addView(remove, Ui.cellParams(this, 1));
        return row;
    }

    private void rebuildWatchList() {
        if (watchBox == null) {
            return;
        }
        watchBox.removeAllViews();
        TextView watchTitle = Ui.text(this, L.t(this, "觀看清單（開啟的才會被計時）", "Watch list (only enabled apps are timed)"), 22, Ui.COLOR_TEXT);
        watchTitle.setGravity(android.view.Gravity.LEFT);
        watchBox.addView(watchTitle, Ui.rowParams(this));
        java.util.Set<String> removedPresets = Prefs.getRemovedPresets(this);
        for (final String pkg : TimerEngine.WATCH_PKGS) {
            if (removedPresets.contains(pkg)) {
                continue; // WL-3：已移除的預設不顯示（可從新增選單加回）
            }
            watchBox.addView(watchRow(pkg, true), Ui.rowParams(this));
        }
        for (final String pkg : Prefs.getCustomWatch(this).keySet()) {
            watchBox.addView(watchRow(pkg, false), Ui.rowParams(this));
        }
        Button add = Ui.bigButton(this, L.t(this, "＋ 新增要限制的 App", "＋ Add an app to restrict"));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppPicker();
            }
        });
        watchBox.addView(add, Ui.rowParams(this));
    }

    /** WL-1：列出電視上可開啟的 App（桌面／Leanback 入口），點選即加入觀看清單。 */
    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        java.util.Map<String, String> found = new java.util.HashMap<>();
        Intent[] queries = new Intent[2];
        queries[0] = new Intent(Intent.ACTION_MAIN);
        queries[0].addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        queries[1] = new Intent(Intent.ACTION_MAIN);
        queries[1].addCategory(Intent.CATEGORY_LAUNCHER);
        for (Intent q : queries) {
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (found.containsKey(pkg)) {
                    continue;
                }
                CharSequence label = ri.loadLabel(pm);
                found.put(pkg, label != null ? label.toString() : pkg);
            }
        }
        // 排除：自己、預設清單、已加入的自訂清單、固定封鎖的系統 App
        found.remove(getPackageName());
        // WL-3：只排除「目前在清單上」的預設；已移除的預設要能被加回
        java.util.Set<String> removedPresets = Prefs.getRemovedPresets(this);
        for (String p : TimerEngine.WATCH_PKGS) {
            if (!removedPresets.contains(p)) {
                found.remove(p);
            }
        }
        // WL-3：已移除但未安裝的預設（查不到入口，如未安裝的 Disney+）手工補入供加回
        for (String p : TimerEngine.WATCH_PKGS) {
            if (removedPresets.contains(p) && !found.containsKey(p)) {
                found.put(p, TimerEngine.appName(p));
            }
        }
        for (String p : Prefs.getCustomWatch(this).keySet()) {
            found.remove(p);
        }
        found.remove("com.android.tv.settings");
        found.remove("com.android.settings");
        found.remove("com.android.vending");
        found.remove("com.android.packageinstaller");
        found.remove("com.google.android.packageinstaller");
        // OPT-2（v9 自測優化）：排除主畫面本身——把 launcher 加入計時清單會讓主畫面也被 gate 住
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo homeRi = pm.resolveActivity(homeIntent, 0);
        if (homeRi != null && homeRi.activityInfo != null
                && homeRi.activityInfo.packageName != null) {
            found.remove(homeRi.activityInfo.packageName);
        }
        final java.util.List<String> pkgs = new java.util.ArrayList<>(found.keySet());
        // 依 App 顯示名稱排序，方便遙控器選取
        java.util.Collections.sort(pkgs, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return String.CASE_INSENSITIVE_ORDER.compare(found.get(a), found.get(b));
            }
        });
        if (pkgs.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(L.t(this, "新增要限制的 App", "Add an app to restrict"))
                    .setMessage(L.t(this, "找不到其他可加入的 App。", "No other apps available to add."))
                    .setPositiveButton(L.t(this, "好", "OK"), null)
                    .show();
            return;
        }
        CharSequence[] items = new CharSequence[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            items[i] = found.get(pkgs.get(i)) + L.t(this, "（", " (") + pkgs.get(i) + L.t(this, "）", ")");
        }
        final java.util.Map<String, String> byPkg = found;
        new AlertDialog.Builder(this)
                .setTitle(L.t(this, "選擇要限制的 App", "Choose an app to restrict"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pkg = pkgs.get(which);
                        if (Prefs.getRemovedPresets(MainActivity.this).contains(pkg)) {
                            // WL-3：加回預設 = 解除移除（回預設區，原開關狀態保留）
                            Prefs.setPresetRemoved(MainActivity.this, pkg, false);
                        } else {
                            Prefs.addCustomWatch(MainActivity.this, pkg, byPkg.get(pkg));
                        }
                        rebuildWatchList();
                    }
                })
                .setNegativeButton(L.t(this, "取消", "Cancel"), null)
                .show();
    }

    private void sendServiceAction(String action) {
        try {
            Intent i = new Intent(this, WatchdogService.class);
            i.setAction(action);
            startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    private void applyUnlockState() {
        boolean parentActive = Prefs.getParentDeadlineWall(this) > System.currentTimeMillis();
        boolean pinDefault = Prefs.DEFAULT_PIN.equals(Prefs.getPin(this));
        // PIN 仍為預設值時（SEC-02）：設定一律不開放；改 PIN 區只在「已以預設 PIN
        // 解鎖」或家長模式中可操作，家長完成設定新 PIN 後才回到一般解鎖邏輯。
        if (changePinBox != null) {
            changePinBox.setVisibility(pinDefault ? View.VISIBLE : View.GONE);
            setChildrenEnabled(changePinBox, pinChangeArmed || parentActive);
        }
        boolean enabled = !pinDefault && (settingsUnlocked || parentActive);
        setChildrenEnabled(settingsBox, enabled);
    }

    private static void setChildrenEnabled(LinearLayout box, boolean enabled) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1.0f : 0.45f);
            if (v instanceof LinearLayout) {
                setChildrenEnabled((LinearLayout) v, enabled);
            }
        }
    }

    // ================= status =================

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        String token = Prefs.getToken(this);
        long chatId = Prefs.getChatId(this);
        StringBuilder sb = new StringBuilder();
        if (token.isEmpty()) {
            sb.append(L.t(this, "安裝狀態：等待安裝設定（尚未讀到 bot token）\n", "Setup: waiting (bot token not loaded yet)\n"));
            sb.append(L.t(this, "請依 README 將 token.txt 傳入電視後，回到本頁或重開 App。", "Follow the README to copy token.txt to the TV, then return here or reopen the app."));
        } else {
            sb.append(L.t(this, "Bot token：已設定\n", "Bot token: set\n"));
            if (chatId != 0) {
                String title = Prefs.getChatTitle(this);
                sb.append(L.t(this, "Telegram 配對：已綁定群組", "Telegram pairing: paired with group"));
                if (!title.isEmpty()) {
                    sb.append(L.t(this, "：", ": ")).append(title);
                }
                sb.append("\n");
            } else if (Prefs.pendingPairingId(this) != 0) {
                sb.append(L.t(this, "Telegram 配對：有群組請求配對，待確認（見下方）\n", "Telegram pairing: a group is requesting to pair (see below)\n"));
            } else {
                sb.append(L.t(this, "Telegram 配對：尚未配對（請在雙親群組發一句話，再回電視確認）\n", "Telegram pairing: not paired (send a message in the parents' group, then confirm on the TV)\n"));
            }
            TimerEngine.Snapshot s = WatchdogService.liveSnapshot();
            if (s != null) {
                sb.append(L.t(this, "今日累計：", "Today: ")).append(Math.round(s.todayMs / 60000.0)).append(L.t(this, " 分鐘（上限 ", " min (limit ")).append(Prefs.getDailyCapMin(this)).append(L.t(this, " 分鐘）\n", " min)\n"));
                if (s.parentMode) {
                    sb.append(L.t(this, "目前狀態：家長模式", "Status: parent mode"));
                } else if (s.sessionActive) {
                    sb.append(L.t(this, "目前狀態：", "Status: ")).append(s.counting ? L.t(this, "計時中", "timing") : L.t(this, "時段暫停中", "paused"));
                    if (!s.unlimited) {
                        sb.append(L.t(this, "，剩 ", ", ")).append(Math.max(0, (int) Math.ceil(s.remainingMs / 60000.0)))
                                .append(L.t(this, " 分鐘", " min left"));
                    } else {
                        sb.append(L.t(this, "（不限制）", " (unlimited)"));
                    }
                } else {
                    sb.append(L.t(this, "目前狀態：閒置（鎖定）", "Status: idle (locked)"));
                }
            } else {
                long todayMs = Prefs.getTodayMs(this);
                sb.append(L.t(this, "今日累計：", "Today: ")).append(Math.round(todayMs / 60000.0)).append(L.t(this, " 分鐘（上限 ", " min (limit ")).append(Prefs.getDailyCapMin(this)).append(L.t(this, " 分鐘）\n", " min)\n"));
                sb.append(L.t(this, "目前狀態：看門狗服務未運行（開啟 App 後會自動啟動）", "Status: watchdog service not running (it starts automatically when the app opens)"));
            }
        }
        statusView.setText(sb.toString());

        boolean hasOverlay = WatchdogService.hasOverlayPermission(this);
        boolean hasA11y = isAccessibilityEnabled();
        boolean hasUsage = hasUsageAccess();
        StringBuilder pb = new StringBuilder(L.t(this, "權限：", "Permissions: "));
        pb.append(L.t(this, "懸浮窗 ", "Overlay ")).append(hasOverlay ? "✓" : "✗");
        pb.append(L.t(this, "／無障礙 ", " / Accessibility ")).append(hasA11y ? "✓" : "✗");
        pb.append(L.t(this, "／使用統計 ", " / Usage stats ")).append(hasUsage ? "✓" : "✗");
        permView.setText(pb.toString());
        // PERM-4：編號按鈕同步顯示狀態（①為導引鈕，系統無狀態可讀，不顯示 ✓／✗）
        if (permRestrictedBtn != null) {
            permRestrictedBtn.setText(L.t(this, "1. 允許受限制的設定（App 資訊頁最下方）", "1. Allow restricted settings (bottom of App info)"));
            permA11yBtn.setText(L.t(this, "2. 無障礙 ", "2. Accessibility ") + (hasA11y ? "✓" : "✗"));
            permUsageBtn.setText(L.t(this, "3. 使用統計 ", "3. Usage stats ") + (hasUsage ? "✓" : "✗"));
            permOverlayBtn.setText(L.t(this, "4. 懸浮窗 ", "4. Overlay ") + (hasOverlay ? "✓" : "✗"));
            permBatteryBtn.setText(L.t(this, "5. 省電策略 ", "5. Battery policy ") + (isBatteryUnrestricted() ? "✓" : "✗"));
        }

        // 待確認配對區塊（SEC-01）：未綁定且有未過期的配對請求時才顯示
        if (pairingBox != null) {
            long pairId = chatId == 0 ? Prefs.pendingPairingId(this) : 0;
            if (pairId != 0) {
                String t = Prefs.getPairTitle(this);
                pairingText.setText(L.t(this, "有群組請求配對：『", "Pairing request from group '")
                        + (t.isEmpty() ? L.t(this, "（未命名群組）", "(unnamed group)") : t)
                        + L.t(this, "』— 確認是你們家的群組再綁定", "' — make sure it's your family's group before pairing"));
                pairingBox.setVisibility(View.VISIBLE);
            } else {
                pairingBox.setVisibility(View.GONE);
            }
        }

        // 家長模式可能剛開始／結束，解鎖狀態跟著更新（不重建畫面，只切 enabled）
        applyUnlockState();
    }

    /** GRP-1（v13）：在 settingsBox 內建一個帶標題的群組容器並回傳。 */
    private LinearLayout settingsGroup(String zh, String en) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        TextView h = Ui.text(this, L.t(this, zh, en), 20, Ui.COLOR_TEXT);
        h.setGravity(android.view.Gravity.LEFT);
        h.setPadding(0, 36, 0, 6);
        g.addView(h, Ui.rowParams(this));
        settingsBox.addView(g, Ui.rowParams(this));
        return g;
    }

    /** GRP-1（v13）：把已建好的 View 從原父容器移入指定群組（保留原點擊邏輯）。 */
    private void moveToGroup(LinearLayout group, View... views) {
        for (View v : views) {
            if (v.getParent() instanceof LinearLayout) {
                ((LinearLayout) v.getParent()).removeView(v);
            }
            group.addView(v, Ui.rowParams(this));
        }
    }

    /** TOK-2（v12）：設定頁動作的統一反饋出口。 */
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** WEB-1（v14）：開啟一次性區網設定頁，讓家長用手機貼上 token。 */
    private boolean webSaveDone;

    private void startWebTokenSetup() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        webSaveDone = false;
        // Listener 在建構期間就會被引用，須經 holder 間接指向自己（避免 definite-assignment 錯誤）
        final TokenWebServer[] ref = new TokenWebServer[1];
        ref[0] = new TokenWebServer(this, new TokenWebServer.Listener() {
            @Override
            public void onTokenSaved(final String botUsername, final boolean replaced, final boolean same) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webSaveDone = true;
                        if (!same) {
                            WatchdogService.start(MainActivity.this);
                        }
                        toast(same
                                ? L.t(MainActivity.this, "與目前 token 相同，未變更", "Same as the current token — unchanged")
                                : replaced
                                        ? L.t(MainActivity.this, "已換上新 token（@" + botUsername + "），請重新配對", "Token replaced (@" + botUsername + ") — please pair again")
                                        : L.t(MainActivity.this, "已儲存 token（@" + botUsername + "），請在群組發訊息配對", "Token saved (@" + botUsername + ") — send a message in the group to pair"));
                        if (webDialog != null && webDialog.isShowing()) {
                            webDialog.dismiss();
                        }
                        refreshStatus();
                    }
                });
            }

            @Override
            public void onClosed() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (webServer == ref[0]) {
                            webServer = null;
                        }
                        if (!webSaveDone && webDialog != null && webDialog.isShowing()) {
                            webDialog.dismiss();
                            toast(L.t(MainActivity.this, "手機設定頁面已關閉（逾時或代碼錯誤過多）", "The phone setup page has closed (timed out or too many wrong codes)"));
                        }
                    }
                });
            }
        });
        final TokenWebServer server = ref[0];
        if (!server.start()) {
            toast(L.t(this, "找不到區網位址，請先連上 Wi-Fi／網路線", "No LAN address found — connect Wi-Fi or Ethernet first"));
            return;
        }
        webServer = server;
        String msg = L.t(this, "用手機瀏覽器開啟（手機需連同一個 Wi-Fi）：\n", "Open this in your phone's browser (same Wi-Fi):\n")
                + server.getUrl() + "\n"
                + L.t(this, "一次性代碼：", "One-time code: ") + server.getCode() + "\n"
                + L.t(this, "頁面 5 分鐘內有效；儲存成功、逾時或關閉本視窗後自動失效。",
                        "Valid for 5 minutes. It closes itself after a successful save, on timeout, or when you close this dialog.");
        webDialog = new AlertDialog.Builder(this)
                .setTitle(L.t(this, "用手機設定 Token", "Set token from phone"))
                .setMessage(msg)
                .setNegativeButton(L.t(this, "關閉頁面", "Close page"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        server.stop();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        server.stop();
                        if (webServer == server) {
                            webServer = null;
                        }
                        webDialog = null;
                    }
                })
                .show();
    }

    /** PERM-2（v13）：直達本 App 資訊頁（「允許受限制的設定」與「省電策略」列都在這頁）。 */
    private void openAppDetails() {
        openSettingsIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    /** PERM-3（v13）：省電策略入口——先請求電池最佳化豁免，逐層 fallback 到 App 資訊頁。 */
    private void openBatterySettings() {
        if (isBatteryUnrestricted()) {
            // 已豁免：仍帶到 App 資訊頁，讓家長核對 HyperOS 自己的省電策略列
            openAppDetails();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                openAppDetails();
            }
        }
    }

    /** 系統電池最佳化豁免狀態（HyperOS 另有自家省電策略，以 App 資訊頁該列為最終準據）。 */
    private boolean isBatteryUnrestricted() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** PERM-1／PAD-2 共用：開啟系統設定頁，裝置無此頁時給反饋而不是靜默。 */
    private void openSettingsIntent(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast(L.t(this, "此裝置沒有對應的設定頁", "This device has no such settings page"));
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            ComponentName me = new ComponentName(this, TvAccessibilityService.class);
            return enabled.contains(me.flattenToString())
                    || enabled.contains(me.flattenToShortString());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }
}
