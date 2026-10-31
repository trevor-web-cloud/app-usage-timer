# 看電視計時 App（com.tvtimer.app）— 建置與安裝手冊

依規格 v1（2026-10-29 核准）實作的 Android TV 看門狗 App。
純 Java、只用 Android framework API（**無 AndroidX、無任何外部函式庫**），
compileSdk 34 / minSdk 30 / targetSdk 34，目標裝置為 Chromecast with Google TV 4K（sabrina，Android TV OS 14）。

> 📌 **一般使用者請勿照本文件安裝**：直接到 GitHub Releases 下載已建置好的 APK，
> 並照 SETUP.md 的詳細教學操作即可（免編譯）：
> https://github.com/trevor-web-cloud/tvtimer/blob/main/SETUP.md
> 本文件是**開發者用**的建置、授權與設計手冊。

---

## 專案結構

```
tvtimer/                        ← Gradle 專案根目錄
├── settings.gradle
├── build.gradle                （僅註解；plugin 版本在 app 模組宣告）
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties   （指向 Gradle 8.9；jar 與啟動腳本需另行複製，見建置步驟）
├── README-BUILD.md             （本文件）
├── SETUP.md                    （一般使用者詳細安裝教學，僅 GitHub repo 版）
├── docs/                       （專案網頁，僅 GitHub repo 版）
└── app/
    ├── build.gradle            （AGP 8.5.2、namespace/applicationId = com.tvtimer.app）
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/strings.xml、values-en/strings.xml
        │   ├── drawable-nodpi/banner.png  （TV 首頁橫幅 1280×720，v7 起）
        │   ├── mipmap-*/ic_launcher.png   （碼表 App 圖示，五種密度，v7 起）
        │   ├── drawable/ic_stat_timer.xml （前台服務通知圖示）
        │   └── xml/accessibility_service_config.xml
        └── java/com/tvtimer/app/
            ├── TimerEngine.java          狀態機本體（計時、每日上限、核准請求、家長模式）
            ├── WatchdogService.java      前台服務：持有 engine/gate/Telegram，主迴圈＋UsageStats 交叉驗證
            ├── TvAccessibilityService.java  前台 App 偵測（視窗狀態事件）
            ├── ForegroundTracker.java    前台訊號的共用暫存
            ├── L.java                    中英雙語字串切換（v6 起）
            ├── GateController.java       全屏攔截頁（懸浮窗）：開局／續看／自訂／時間到／等待核准／PIN／訊息
            ├── TelegramClient.java       Bot API：長輪詢、回報、inline 按鈕、核准去重
            ├── MainActivity.java         狀態／設定畫面（今日累計、PIN、觀看清單、配對狀態）
            ├── BootReceiver.java         開機自啟看門狗
            ├── Prefs.java                全部持久化狀態＋token 匯入
            └── Ui.java                   TV UI 小工具（大按鈕、焦點樣式）
```

## 設計決策（規格未指定、由實作決定者）

### 1. 攔截頁用「服務持有的懸浮窗」，不用 Activity
- Android 10 起、Android 14 更嚴格：背景 App 原則上**不能啟動 Activity**（BAL 限制）。
  看門狗恰恰是在背景偵測到違規前台 App 的那一刻要出面，用 Activity 會有被系統擋下的風險。
- 懸浮窗（`TYPE_APPLICATION_OVERLAY`）由前台服務直接 `addView`，不涉及 Activity 啟動，路徑確定；
  視窗可取得焦點，遙控器方向鍵／OK／返回都能操作，並以自訂 `dispatchKeyEvent` 攔截返回鍵與數字鍵。
- 攔截當下同時以無障礙服務執行 `GLOBAL_ACTION_HOME` 把觀看 App 送到背景（確實停止播放），
  gate 就浮在主畫面之上；核准／開局後再以 launch intent 把目標 App叫回前台。
  萬一叫回被系統擋下也不致命：時段已開始，而倒數只在 App 真正回到前台時才走，小孩自己點開 App 即可。

### 2. 前台偵測雙訊號
無障礙服務的視窗事件為主訊號；服務主迴圈每 500ms 再以 UsageStatsManager 的
`ACTIVITY_RESUMED` 事件交叉驗證（取 5 秒內最新者），任一訊號改變都會觸發 engine 重新評估，
補漏接事件的情況。systemui（音量條）與輸入法視窗在來源端就被忽略，不會誤觸暫停。

### 3. 續看要按「繼續」
時段進行中一旦離開觀看 App 就自動暫停並保留剩餘；再次開啟觀看 App 會先跳出 gate，
按「繼續上次」才恢復倒數（與規格的續看流程一致）。重開機後亦同（時段還原為待確認續看）。

### 4. Token 採執行期匯入，不寫死在程式裡（與規格前置作業第 2 點的差異）
原始碼與 APK 內**沒有任何 token**。安裝時把 token 放進 `/sdcard/tvtimer/token.txt` 推入電視，
App 第一次啟動時匯入自己的私有 SharedPreferences 並立即刪除該明文檔。
日後換 bot 只需重推 token.txt 並在設定頁按「重新讀取 token.txt」，不必重新編譯。

### 5. 家長模式的計時基準
家長模式以 wall-clock 截止時間持久化（2 小時），電視重開機後以剩餘時間重建；
電視待機（螢幕關閉事件）一到立即結束家長模式 — 先到者為準，符合規格。

### 6. 前台服務類型
Android 14 要求宣告 FGS 類型；本 App 使用 `specialUse`（家長監控屬未歸類用途）
並附 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 說明。若裝置韌體拒絕此類型，
服務會記錄 log 並降級繼續運行（onCreate 有 try/catch），屆時再改類型處理。

---

## v2 安全修正（2026-10-29，依資安檢查報告 v1 的 Top 5）

- **SEC-02／SEC-03 PIN**：PIN 仍為預設值 `0000` 時，以預設 PIN 驗證通過不會直接
  開放家長模式／設定，而是強制先設定新 PIN（gate 兩段式設定頁；設定頁有專用改 PIN 區）。
  PIN 連續錯誤 5 次鎖定 5 分鐘，gate 與設定頁共用同一組持久化計數；鎖定截止同時存
  wall-clock（跨重開機有效）與 elapsedRealtime（以 BOOT_COUNT 限定同一次開機內有效），
  PIN 頁面會顯示剩餘鎖定時間。
- **SEC-04 核准請求防卡死**：pending 現在記錄建立時間，30 分鐘未獲回覆自動失效
  （gate 若停在等待頁會回到可重試頁面，過期 pending 也會被新請求直接取代）；
  送出失敗（Telegram 回報失敗／例外，或尚未配對無從送出）時立即清除 pending，
  gate 回到可重試頁面並顯示「請求送出失敗，請再試一次」。
- **SEC-01 兩步配對**：第一則群組訊息只登記為待確認配對（10 分鐘有效），須在 TV 端
  確認才綁定，避免公開 bot 名稱被陌生人搶先綁定；已綁定群組的名稱會存在設定頁供核對，
  「清除 Telegram 配對」會一併清除群組名稱與待確認紀錄。
- **SEC-06 supergroup 遷移**：送訊息的錯誤回應或更新串流中的遷移服務訊息若帶有
  `migrate_to_chat_id`，且目標正是已綁定群組，App 會自動把綁定更新為新 chat id
  （群組名稱保留）並在新群組發一則通知，核准通道不因群組升級而靜默失效。
- **SEC-10 release 建置**：正式建置改用 `assembleRelease`（以 debug key 簽署供側載），
  且 debug 變體明確設 `debuggable false`，交付的 APK 一律非 debuggable。

## 建置步驟（開發者工具鏈：JDK 21／Gradle 8.9／Android SDK 34）

1. **Gradle 取得方式（三擇一）**：本專案只附 `gradle-wrapper.properties`（Gradle 8.9），
   未附 wrapper 啟動腳本與 jar。可用系統安裝的 Gradle 8.9 直接建置（把步驟 3 的
   `.\gradlew.bat` 換成 `gradle`）；或先執行 `gradle wrapper --gradle-version 8.9`
   產生 wrapper；或從任一 Gradle 8.9 專案複製 `gradlew`、`gradlew.bat`、
   `gradle\wrapper\gradle-wrapper.jar` 三樣到本專案根目錄。
2. 在 PowerShell 設定 JDK 21（路徑代入你自己的 JDK 21 安裝位置）：
   ```powershell
   $env:JAVA_HOME = "<JDK 21 安裝路徑>"
   ```
3. 編譯（在專案根目錄）：
   ```powershell
   cd <tvtimer 專案根目錄>
   .\gradlew.bat assembleRelease
   ```
   產出：`app\build\outputs\apk\release\app-release.apk`
   （release 變體以內建 debug key 簽署供側載安裝，且 release／debug 兩種建置皆為
   非 debuggable，避免 ADB `run-as` 直讀 App 私有資料，見下方「v2 安全修正」SEC-10。）

## 安裝與授權（ADB，逐項照做）

先決條件：電視已開無線偵錯、PC 與電視同網段，且 adb 已連線——無線偵錯的連接埠是
電視「無線偵錯」主頁面上顯示的**動態埠**（不是固定的 5555），配對與連線步驟見
SETUP.md 步驟四。以下假設 adb 已連上電視：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

授權（一次做完，全部必要）：
```powershell
# 懸浮窗（攔截頁與警告橫幅）
adb shell appops set com.tvtimer.app SYSTEM_ALERT_WINDOW allow
# 無障礙服務（前台偵測）— 以 adb 寫入設定可繞過 Android 14 對側載 App 的「受限設定」UI 限制
adb shell settings put secure enabled_accessibility_services com.tvtimer.app/com.tvtimer.app.TvAccessibilityService
adb shell settings put secure accessibility_enabled 1
# 使用統計（前台偵測交叉驗證）
adb shell appops set com.tvtimer.app GET_USAGE_STATS allow
# 讀取 token.txt 所需的全檔案存取（匯入後即不再需要，可事後收回）
adb shell appops set --uid com.tvtimer.app MANAGE_EXTERNAL_STORAGE allow
# 通知（前台服務通知顯示；選配）
adb shell pm grant com.tvtimer.app android.permission.POST_NOTIFICATIONS
```
注意：`enabled_accessibility_services` 是**覆寫**語法；若電視上已有其他無障礙服務在用，
要以冒號串接保留（先 `adb shell settings get secure enabled_accessibility_services` 看現值）。

驗證授權：
```powershell
adb shell settings get secure enabled_accessibility_services
adb shell appops get com.tvtimer.app
```
App 設定頁（主畫面上的「看電視計時」）也會即時顯示三項權限的 ✓／✗。

## Bot token 傳入（provisioning）

1. 在 PC 上建立 `token.txt`，內容**只有 token 一行**（BotFather 給的那串）。
2. 推入電視並啟動 App：
   ```powershell
   adb shell mkdir /sdcard/tvtimer
   adb push token.txt /sdcard/tvtimer/token.txt
   adb shell am start -n com.tvtimer.app/.MainActivity
   ```
3. App 啟動時會自動匯入 token、**刪除** `/sdcard/tvtimer/token.txt`，設定頁顯示「Bot token：已設定」。
   （匯入失敗先檢查上一節的全檔案存取授權；也可按設定頁的「重新讀取 token.txt」重試。）

## Telegram 配對

1. 以 BotFather 建好專用 bot（完整步驟見 SETUP.md 步驟一）。
2. 把 bot 的隱私模式關閉（BotFather `/setprivacy` → 選該 bot → Disable）。
3. 建雙親群組、把 bot 加入，**在群組裡發任意一句話**。
4. **兩步配對（v2）**：第一則群組訊息只會被登記為「待確認配對」（10 分鐘內有效），
   還不會綁定。請回電視端確認：gate 若正顯示，會跳出配對確認頁顯示群組名稱；
   或打開 App 設定頁，在「有群組請求配對」區塊按「確認綁定」。
   確認後 App 才真正綁定並在群組回覆「✅ 配對成功」；按「拒絕」或超過 10 分鐘未確認即作廢，
   在群組再發一句話即可重新請求。設定頁會顯示「已綁定群組：<名稱>」供隨時核對。
   綁定後其他 chat 的訊息與按鈕一律忽略。家長之後加入同一群組即可核准，不需重新配對。
5. 在群組傳 `/status` 可隨時查目前狀態。

## 操作速覽

- 小孩開觀看 App（YouTube／YouTube Kids／Netflix／Disney+／Prime Video，可在設定頁開關）
  → 無時段：跳攔截頁選 30／45／60／自訂，或請求不限制（需核准）。
- 離開 App 自動暫停；回來按「繼續上次」續看。剩 1 分鐘跳上方警告橫幅（不中斷播放）。
- 時間到：攔截頁可按「請爸媽加時」；家長在 Telegram 按 +15／+30／不限制／拒絕，
  **先按者為準**，結果會註明核准者並同步編輯原請求訊息。
- 每日累計達 120 分鐘後，TV 端選時長會自動改送核准請求。
- v7 起新增：時段進行中、今日累計跨越 120 分鐘的當下即硬停結束（CAP-1）；
  午夜 12 點進行中的時段直接結清（MID-1；家長核准的不限制時段可跨午夜、亦不受 CAP-1 影響）。
- v8 起：鎖定狀態下對「設定／Play 商店／安裝器」的封鎖改為每秒檢查、週期性重申驅離
  （BLK-1；舊版只在前台變化瞬間觸發一次，曾被設定搶回前台繞過，logcat 實證後修正）。
- 觀看清單：預設 App 與自訂 App 皆可開關、新增、移除（WL-1／WL-3，設定頁 PIN 解鎖後操作）。
- 家長模式：gate 或設定頁輸入 PIN 或 Telegram 核准，有效 2 小時或至電視待機。
  家長模式中不計時、不攔截。PIN 預設 `0000`，但**首次以預設 PIN 驗證通過時，會強制
  先設定新 PIN**（gate 導向設定新 PIN 頁、設定頁顯示改 PIN 區），完成前不開放家長
  模式與設定；PIN 連續錯誤 5 次會鎖定 5 分鐘（gate 與設定頁共用同一組計數）。
- 鎖定狀態下「設定」與 Play 商店會被攔截（防小孩關無障礙服務或自行裝 App）；
  本 App 的設定頁在鎖定時也要先過 gate 的 PIN。

## 驗收測試清單（對應規格 v1）

- [ ] 無時段開 YouTube → 被攔截；開局 30 分 → 倒數正確（離開 App 數字不走）。
- [ ] 中途回首頁 → 自動暫停（Telegram 收到暫停回報）；再開 → 續看剩餘正確。
- [ ] 剩 1 分警告橫幅出現且不中斷播放；到時攔截；「請爸媽加時」全流程：
      雙親任一核准生效、先按者為準、訊息註明核准者、第二人再按得到「已處理」。
- [ ] 語音助理直接開 App → 仍被攔截。
- [ ] 設定／Play 商店於鎖定狀態下被擋；以 PIN 過 gate 後可開設定頁。
- [ ] 重開機 Chromecast → 看門狗自啟；進行中時段（待續看）與今日累計不遺失。
- [ ] 跨日後今日累計歸零。
- [ ] 家長模式：PIN 進出正常；2 小時到期或電視待機後自動回復鎖定。
- [ ] 每日達 120 分後開局 → 自動改走 Telegram 核准；核准後正常開局。
- [ ] Telegram `/status` 回覆正確；時段訊息上的 +15／+30／現在結束有效。
- [ ] token.txt 在匯入後已自電視刪除；MainActivity 三項權限顯示 ✓。

## 已知限制

- Fail-open：看門狗行程若死亡，電視成為開放狀態（已以前台服務＋開機自啟＋鎖定設定降低機率）；
  服務被殺後，下一個無障礙事件或重開機會把它拉回。
- 安全模式、恢復原廠可繞過（任何軟體方案皆然）。
- 管轄僅限 Chromecast 訊源；電視其他 HDMI 訊源不在管轄內。
- 電視時鐘若被手動改到跨日，今日累計會依新日期歸零（倒數本身用 elapsedRealtime，不受影響）。
