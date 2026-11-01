# TV Timer 詳細安裝與設定教學

一步一步帶你完成：建立 Telegram 機器人 → 取得 token → 把 APK 裝上電視 → 傳入 token →
開通三項權限 → 設定 PIN → 建立家長群組並配對。第一次設定約需 20–30 分鐘。

> English guide below the Chinese section.

## 事前準備

- Chromecast with Google TV 4K（插在電視上、已連上家裡 Wi-Fi）
- 一台 Windows 電腦，與電視連同一個 Wi-Fi 網段
- 爸爸、媽媽各自的手機（已裝 Telegram、各自有帳號）
- 一點耐心：電視的無線偵錯連線埠會變動，下文有對應解法

---

## 步驟一：用 BotFather 建立你的 Telegram 機器人

1. 在手機 Telegram 搜尋 **@BotFather**（認明官方藍色勾勾帳號），開啟對話。
2. 輸入 `/newbot`。
3. 依提示輸入機器人的**顯示名稱**（例如：我家電視計時）——可中文、之後可改。
4. 再輸入機器人的**帳號（username）**：必須是英數字、且以 `bot` 結尾（例如 `mytv_timer_bot`），
   全世界唯一，被用過就換一個。
5. BotFather 會回覆一串 **token**，長得像這樣：
   `1234567890:AAExxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`
   **這串 token 就是步驟五要用的東西，先複製保存好（見步驟二）。**
6. 接著關閉機器人的群組隱私模式（不關的話，機器人在群組裡看不到訊息，配對和指令都會失靈）：
   在 BotFather 輸入 `/mybots` → 點你的機器人 → **Bot Settings** → **Group Privacy** → **Turn off**。

## 步驟二：token 是什麼、在哪裡拿

- token 是機器人的「鑰匙」，誰拿到它就能操控你的機器人。**不要截圖外流、不要貼到公開地方。**
- 之後想再查看：找 BotFather 輸入 `/mybots` → 選你的機器人 → **API Token**，就會顯示目前的 token。
- 若 token 不慎外洩：在同一個畫面按 **Revoke current token** 換發新 token；
  舊的會立即失效，電視端要依步驟五重新匯入新 token。

## 步驟三：下載 APK 與 ADB 工具（電腦上）

1. 到本專案的 Releases 頁下載最新版 APK：
   https://github.com/trevor-web-cloud/tvtimer/releases
   （檔名類似 `tvtimer-v10.apk`）
2. 下載 Google 官方的 platform-tools（裡面有 `adb.exe`）：
   https://developer.android.com/tools/releases/platform-tools
   選 Windows 版，解壓縮到一個固定位置，例如 `D:\platform-tools`。
3. 把 APK 也放到 `D:\platform-tools` 資料夾裡，步驟四會用到。

## 步驟四：讓電腦連上電視（無線偵錯）

1. 電視上先開啟「開發人員選項」：
   **設定 → 系統 → 關於 →** 在「Android TV 作業系統版本／版號」上連續點 7 次，
   直到跳出「你已是開發人員」。
2. 回到 **設定 → 系統 → 開發人員選項 → 無線偵錯**，把它開啟。
3. 點「**使用配對碼配對裝置**」，畫面會顯示一組 `IP:連接埠` 和一組 6 位數配對碼，先不要離開這個畫面。
4. 電腦開啟 PowerShell，切到 platform-tools 資料夾，執行配對（IP 與埠用畫面上的）：
   ```powershell
   cd D:\platform-tools
   .\adb pair 192.168.1.50:37123
   ```
   它會要你輸入配對碼，輸入電視上的 6 位數，看到 `Successfully paired` 即配對成功。
   （配對只需做一次；電視重開機後不用重新配對，但要重新執行下一步的 connect。）
5. 回到電視的「無線偵錯」**主頁面**，上面另有一組 `IP:連接埠`（跟配對埠不同，不要搞混），
   在電腦執行連線：
   ```powershell
   .\adb connect 192.168.1.50:40215
   .\adb devices
   ```
   清單裡出現該裝置且狀態是 `device` 就成功了。
   **注意：只要離開無線偵錯主頁面，連接埠就可能改變；連不上時，回到主頁看新的埠號再 connect 一次。
   電視重開機後無線偵錯會自動關閉，要重新開啟再 connect（不用重新 pair）。**

## 步驟五：安裝 APK 並傳入 token

1. 安裝（還連著線的 PowerShell）：
   ```powershell
   .\adb install -r tvtimer-v10.apk
   ```
   看到 `Success` 完成。此時電視的應用程式清單會出現「看電視計時」（碼表圖示）。
2. 在電腦用記事本建立 `token.txt`：內容**只有 token 一行**，不要加空白、引號或其他文字，存到 `D:\platform-tools`。
3. 把 token 檔推上電視並開啟 App：
   ```powershell
   .\adb shell mkdir -p /sdcard/tvtimer
   .\adb push token.txt /sdcard/tvtimer/token.txt
   .\adb shell am start -n com.tvtimer.app/.MainActivity
   ```
4. App 第一次啟動會自動讀取 token、存入自己的私有儲存，**並把 /sdcard 上的明文 token.txt 刪掉**。
   在 App 狀態區確認顯示「Bot token：已設定」；驗證刪除：
   ```powershell
   .\adb shell ls /sdcard/tvtimer/
   ```
   應該看不到 token.txt。若還在或狀態未更新，按 App 裡的「重新讀取 token.txt」再試一次。
   平板（非電視）直接在裝置上匯入時，App 需要「所有檔案存取權」才能讀取該檔案：
   v11 起 App 內有「開啟檔案存取設定（匯入 token 用）」按鈕可直達該設定頁，開啟後再按「重新讀取 token.txt」。
   v12 起：在平板上可改用 App 內的「貼上 Bot Token」直接貼上（會先驗證並顯示 bot 名稱，成功才儲存）；
   「重新讀取 token.txt」已改為可覆寫既有 token（換 token 時配對會一併清除，需重新配對）；
   狀態區下方有懸浮窗／無障礙／使用統計三顆按鈕可直接跳到各自的系統設定頁。

## 步驟六：在 App 內依編號開通權限

打開「看電視計時」，狀態區下方有「權限設定」編號清單（v13 起），照編號 1 到 5 依序開通：

1. **允許受限制的設定**：按鈕直達 App 資訊頁，在小米／HyperOS 平板上要捲到最下方「進階設定」開啟
   （這步沒開，第 2 步的無障礙會直接遭拒；電視與原生 Android 可能不需要此步，找不到就跳過）。
2. **無障礙服務**：在「下載的應用程式／已安裝的應用程式」裡找到 TV Timer，開啟並確認 10 秒倒數警告。
3. **使用情況存取權**：允許 TV Timer 存取使用情況。
4. **在其他應用程式上層顯示（懸浮窗）**：允許 TV Timer。
5. **省電策略**：設為無限制（小米／HyperOS 另在 App 資訊頁的「省電策略」列確認顯示無限制），
   避免看門狗被系統凍結。

2–5 都顯示 ✓ 才算完成。

## 步驟七：設定家長 PIN

1. 在 App 裡進入家長模式入口，輸入預設 PIN `0000`。
2. 系統會**強制你先設定一組新 PIN** 才開放設定；請設一組只有爸媽知道的 4 位數字
   （不要用生日、不要和門鎖密碼相同）。
3. PIN 連續輸入錯誤 5 次會鎖定 5 分鐘（重開機也一樣），不要拿來試玩。

## 步驟八：建立家長群組並配對

1. 在 Telegram 建立一個**群組**，把爸爸、媽媽都加進去，再把步驟一的機器人也加進群組。
2. 在群組裡隨便傳一則訊息（例如打「配對」兩個字）。
3. 回到電視的「看電視計時」，會跳出**配對確認**，上面顯示這個群組的名稱——
   確認是你們自己的群組後，在電視上按「確認配對」。
   （這是刻意的安全設計：綁定必須在電視本機確認，陌生群組沒辦法自己綁進來。
   配對確認有 **10 分鐘時限**，逾時只要在群組再傳一則訊息即可重新發起。）
4. 機器人會在群組回覆綁定完成。之後所有回報與核准都在這個群組進行；
   第二位家長只要在同一個群組裡即可，不需要重新配對。

## 日常使用速覽

- **開局**：小孩開 YouTube 等觀看 App 會跳出全屏頁面，選 30／45／60 分或自訂分鐘數即可開始；
  若設了「需家長核准」，家長在群組按核准。
- **查狀態**：在群組輸入 `/status`。
- **遠端操作**：時段進行中，家長可在群組的回報訊息上按 **+15 分／+30 分／現在結束**；
  小孩在電視上按「請爸媽加時」時，群組會收到核准請求，任一位家長按下即生效並顯示核准人。
- **家長模式**：在電視輸入 PIN，或在群組核准，即可暫停看門狗 2 小時（或到電視待機），
  期間可自由進出系統設定。

## 疑難排解

| 症況 | 處理 |
|---|---|
| `adb connect` 連不上 | 回電視「無線偵錯」主頁確認埠號有沒有變；電視重開過的話先重新開啟無線偵錯；確認電腦和電視在同一個 Wi-Fi |
| 機器人在群組沒反應 | 檢查步驟一第 6 點（Group Privacy 有沒有 Turn off）、機器人是否真的在群組裡、token 是否匯入成功 |
| token 沒匯入 | 確認檔案路徑是 `/sdcard/tvtimer/token.txt`、內容只有一行；按 App 的「重新讀取 token.txt」；必要時重推一次 |
| 爸媽自己也被設定擋住 | 先進「家長模式」再去系統設定，這是刻意的（小孩模式下設定被鎖定） |
| 要移除 TV Timer | 先進家長模式，再到 設定 → 應用程式 → 看電視計時 → 解除安裝 |
| 用手機投放看影片沒計時 | 已知限制：投放（Cast）不計時，本 App 計的是在電視上開 App 觀看的時間 |

## 安全提醒

- token 等同機器人的密碼：只存在你的電腦與電視裡，不要放上網路、不要給外人。
- APK 只從本專案的 Releases 頁下載，來路不明的 APK 不要裝。
- PIN 不要用生日或連號。

## 移除 App

不再使用時可完整移除。本 App 不使用裝置管理員權限，不需先解除任何設定；移除後，App 內的 token、PIN 與設定會一併刪除。

**方法一：在電視上移除**

1. 開 TV Timer，先進**家長模式**（輸入 PIN，或在 Telegram 群組核准）。這一步不能省：小孩模式下設定被 App 擋住，是設計行為。
2. 回主畫面 → **設定 → 應用程式 → TV Timer → 解除安裝**。

**方法二：用電腦 ADB 移除**（不需進家長模式）

```
.\adb connect <電視IP>:<無線偵錯主頁的埠號>
.\adb uninstall com.tvtimer.app
```

---

# TV Timer — Detailed Setup Guide (English)

Step by step: create a Telegram bot → get its token → install the APK on the TV →
import the token → grant three permissions → set a PIN → create the parents' group and pair.
First-time setup takes about 20–30 minutes.

## What you need

- A Chromecast with Google TV 4K on your home Wi-Fi
- A Windows PC on the same Wi-Fi network
- A phone with Telegram for each parent

## Step 1 — Create your Telegram bot with BotFather

1. In Telegram, open a chat with **@BotFather** (the official, verified account).
2. Send `/newbot`.
3. Enter a **display name** (e.g., "Family TV Timer") — can be changed later.
4. Enter a **username**: letters/digits/underscores, must end in `bot` (e.g., `mytv_timer_bot`), and globally unique.
5. BotFather replies with your **token**, which looks like
   `1234567890:AAExxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`. Save it for Step 5.
6. Turn off group privacy (otherwise the bot can't see group messages and pairing/commands won't work):
   send `/mybots` → your bot → **Bot Settings** → **Group Privacy** → **Turn off**.

## Step 2 — The token: where to find it again

- The token is the bot's master key: anyone who has it controls your bot.
  **Never share it, never post a screenshot of it.**
- To view it again: BotFather → `/mybots` → your bot → **API Token**.
- If it ever leaks: in the same screen choose **Revoke current token** to issue a new one;
  the old token stops working immediately, and you must re-import the new one on the TV (Step 5).

## Step 3 — Download the APK and ADB tools (on your PC)

1. Download the latest APK from this project's Releases page:
   https://github.com/trevor-web-cloud/tvtimer/releases (file name like `tvtimer-v10.apk`)
2. Download Google's official platform-tools (contains `adb.exe`):
   https://developer.android.com/tools/releases/platform-tools
   Get the Windows version and unzip it to a fixed place, e.g. `D:\platform-tools`.
3. Put the APK file into `D:\platform-tools` as well — Step 5 uses it from there.

## Step 4 — Connect your PC to the TV (wireless debugging)

1. Enable Developer options on the TV:
   **Settings → System → About →** tap the **Android TV OS build** entry 7 times
   until it says you are a developer.
2. Go to **Settings → System → Developer options → Wireless debugging** and turn it on.
3. Tap **Pair device with pairing code**. The screen shows an `IP:port` and a 6-digit code.
   Stay on that screen.
4. On the PC, open PowerShell in the platform-tools folder and pair
   (use the IP:port from the TV screen):
   ```powershell
   cd D:\platform-tools
   .\adb pair 192.168.1.50:37123
   ```
   Enter the 6-digit code when asked; `Successfully paired` means done.
   (Pairing is one-time. After a TV reboot you do NOT pair again — just redo the connect below.)
5. Go back to the **Wireless debugging main page** on the TV. It shows a *different*
   `IP:port` — that is the connection port. Connect:
   ```powershell
   .\adb connect 192.168.1.50:40215
   .\adb devices
   ```
   The device listed with status `device` means you're connected.
   **Notes: the pairing port and the connection port are different numbers. The connection
   port can change whenever you leave the wireless-debugging page — if connect fails,
   look at the main page again and use the new port. Wireless debugging turns itself OFF
   when the TV reboots; turn it back on and reconnect (no re-pairing needed).**

## Step 5 — Install the APK and import the token

1. Install (same PowerShell):
   ```powershell
   .\adb install -r tvtimer-v10.apk
   ```
   `Success` means installed. "TV Timer" (stopwatch icon) now appears in the TV's app list.
2. Create `token.txt` in Notepad: the **token only, one line**, no spaces or quotes.
   Save it in `D:\platform-tools`.
3. Push it to the TV and open the app:
   ```powershell
   .\adb shell mkdir -p /sdcard/tvtimer
   .\adb push token.txt /sdcard/tvtimer/token.txt
   .\adb shell am start -n com.tvtimer.app/.MainActivity
   ```
4. On first launch the app reads the token into its private storage and **deletes the
   plaintext token.txt** from /sdcard. Confirm the app's status area shows the bot token
   as set, and verify the deletion:
   ```powershell
   .\adb shell ls /sdcard/tvtimer/
   ```
   token.txt should be gone. If it is still there, use the app's "Reload token.txt" button.
   On a tablet (not a TV), importing on-device requires the "All files access"
   permission so the app can read the file: since v11 the app has an in-app
   "Open file-access settings (for token import)" button that jumps straight to
   that settings page — enable it, then tap "Reload token.txt".
   Since v12, on a tablet you can instead use "Paste bot token" in the app (it verifies the
   token and shows the bot name before saving); "Reload token.txt" now overwrites an existing
   token (pairing is cleared when the token changes — pair again afterwards); and three buttons
   under the status area jump straight to the Overlay / Accessibility / Usage-stats settings.

## Step 6 — Grant the permissions in the app, in numbered order

Open TV Timer. Below the status area there is a numbered "Permissions" list (since v13).
Work through it in order, 1 to 5:

1. **Allow restricted settings** — the button jumps to the App info page; on Xiaomi/HyperOS
   tablets scroll to the bottom ("Advanced settings") and turn it on. Without this step,
   step 2 is rejected outright. (TVs and stock Android may not need it — skip if absent.)
2. **Accessibility service** — under "Downloaded apps / Installed apps", find TV Timer,
   turn it on and accept the 10-second countdown warning.
3. **Usage access** — allow TV Timer.
4. **Display over other apps (overlay)** — allow TV Timer.
5. **Battery policy** — set to unrestricted (on Xiaomi/HyperOS also confirm the "Battery
   saver" row on the App info page shows No restrictions), so the watchdog is not frozen.

Steps 2–5 should all show ✓.

## Step 7 — Set the parent PIN

1. Open the parent-mode entry in the app and enter the default PIN `0000`.
2. The app **forces you to set a new PIN** before opening settings. Pick a 4-digit PIN
   only the parents know (not a birthday, not your door code).
3. Five wrong entries in a row lock the PIN for 5 minutes (the lock survives reboots) —
   don't let kids "try it out".

## Step 8 — Create the parents' group and pair

1. In Telegram, create a **group** with both parents, and add your bot (Step 1) to it.
2. Send any message in the group (e.g., "pair").
3. On the TV, TV Timer shows a **pairing confirmation** with that group's name — confirm it
   on the TV if it's yours. (By design, binding must be confirmed on the TV itself, so a
   stranger's group can't bind itself to your TV. The confirmation expires after
   **10 minutes** — just send another message in the group to restart it.)
4. The bot replies in the group that pairing is complete. All reports and approvals
   happen in this group from now on; the second parent only needs to be in the group —
   no re-pairing.

## Everyday use

- **Start a session**: when a kid opens YouTube etc., a full-screen page offers
  30/45/60 minutes or a custom length; if approval is required, a parent taps Approve in the group.
- **Check status**: send `/status` in the group.
- **Remote control**: during a session, parents can tap **+15 min / +30 min / End now**
  on the bot's report messages; when the kid taps "Ask parents for more time" on the TV,
  an approval request appears in the group — either parent can approve, and the approval
  is attributed by name.
- **Parent mode**: enter the PIN on the TV, or get approval in the group, to suspend the
  watchdog for 2 hours (or until the TV goes to standby) and use system settings freely.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `adb connect` fails | Check the current port on the TV's Wireless debugging main page; if the TV rebooted, turn Wireless debugging back on; make sure PC and TV are on the same Wi-Fi |
| Bot doesn't react in the group | Check Step 1 item 6 (Group Privacy turned off), that the bot is really in the group, and that the token was imported |
| Token not imported | File must be at `/sdcard/tvtimer/token.txt` with exactly one line; use the app's "Reload token.txt"; re-push if needed |
| Parents are blocked by Settings too | Enter parent mode first, then open system settings — the lock in kid mode is intentional |
| Uninstalling TV Timer | Enter parent mode first, then Settings → Apps → TV Timer → Uninstall |
| Casting from a phone isn't timed | Known limitation: casting is not timed; the app times viewing apps opened on the TV itself |

## Security notes

- The token is your bot's password: keep it only on your PC and TV. Never online, never shared.
- Download the APK only from this project's Releases page.
- Don't use a birthday or a repeating sequence as the PIN.

## Uninstalling the app

You can remove the app completely at any time. It does not use device-admin rights, so there is nothing to deactivate first; uninstalling also deletes the token, PIN, and settings stored in the app.

**Option 1 — On the TV**

1. Open TV Timer and enter **Parent Mode** first (PIN, or approval in the Telegram group). This step is required: in child state the app blocks Settings by design.
2. Go back to the home screen → **Settings → Apps → TV Timer → Uninstall**.

**Option 2 — From your PC with ADB** (no Parent Mode needed)

```
.\adb connect <TV_IP>:<port shown on the Wireless debugging main page>
.\adb uninstall com.tvtimer.app
```
