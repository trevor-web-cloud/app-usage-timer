# 應用程式使用計時 App Usage Timer

為 Chromecast with Google TV 4K 自製的兒童看電視計時系統：一個常駐背景的「看門狗」App，
監看觀看類 App 的使用時間；家長用自己的 Telegram bot 在手機上收回報、遠端加時與核准。

**目前版本：v14（versionCode 16 / 1.12-v14，修訂版三）** ｜ 專案網頁：https://trevor-web-cloud.github.io/app-usage-timer/

📥 **直接下載 APK 安裝**：[Releases 頁](https://github.com/trevor-web-cloud/app-usage-timer/releases)（不需自行編譯）
📖 **詳細安裝教學**（建機器人、取得 token、配對、權限、疑難排解）：[SETUP.md](SETUP.md)

> English version below.

## 功能

- **計時規則**：單次 30／45／60 分鐘或自訂（5–180 分）；每日上限可自訂（預設 120 分鐘），看滿即硬停；午夜 12 點自動結清當日時段
- **看門狗監看**：無障礙服務＋UsageStats 雙訊號判定前台 App，離開觀看 App 自動暫停、回來可續看剩餘時間
- **攔截**：沒有時段就開觀看 App → 全屏攔截頁，可直接開局或請爸媽核准；剩 1 分鐘跳警告、時間到自動回主畫面鎖定
- **系統封鎖**：小孩狀態下「設定」與 Play 商店被強制鎖定（週期性重申驅離，防搶回前台繞過），避免改設定或移除本 App
- **家長模式**：PIN 或 Telegram 核准進入（2 小時或待機結束），家長模式中看門狗休眠
- **Telegram 整合**：專用 bot 回報開始／結束／超時；家長在群組一鍵核准、遠端 +15／+30 分鐘或立即結束；爸媽兩人皆可核准並註明核准人
- **觀看清單可自訂**：預設 YouTube、YouTube Kids、Netflix、Disney+、Prime Video，可增減任意已安裝 App（主畫面本身除外）
- **手機網頁輸入 token（v14）**：電視端免建 token.txt、免 ADB 傳檔——解鎖設定後開啟一次性區網頁面，用手機貼上 token（一次性代碼＋Telegram 驗證，成功後自動關閉）
- **雙語介面**：繁體中文／English 可切換

## 運作方式

純 Android framework API、零第三方函式庫。前台服務常駐監看，計時狀態全部持久化，
重開機自動還原（含今日累計，具 NTP 校時延遲防護）。Bot token 不寫在程式碼裡：
由家長在本機產生 token 檔、經 ADB 推入裝置，App 首次啟動匯入私有儲存後即刪除原檔；v14 起電視也可改用「用手機設定 Token」的一次性區網頁面輸入（見 SETUP 步驟五）。

## 安裝（一般使用者）

不需自行編譯：到 [Releases](https://github.com/trevor-web-cloud/app-usage-timer/releases) 下載最新版 APK，
再照 [詳細安裝教學 SETUP.md](SETUP.md) 一步步完成——建立 Telegram 機器人、取得 token、
ADB 無線連線、傳入 token、授予懸浮窗／無障礙／使用情況存取三項權限、設定 PIN、群組配對，
全程約 20–30 分鐘（Chromecast with Google TV 4K、Android TV OS 14 實測）。

## 從原始碼建置（開發者）

- 需求：JDK 21、Gradle 8.9、Android SDK（compileSdk/targetSdk 34、minSdk 30）
- 本 repo 只附 `gradle-wrapper.properties`（未附 wrapper 啟動腳本與 jar）：
  請用系統安裝的 Gradle 8.9 執行 `gradle assembleRelease`，
  或先以 `gradle wrapper --gradle-version 8.9` 產生 wrapper 再建置
  （release 以 debug key 簽署，供自家側載）
- 建置與設計細節見 [README-BUILD.md](README-BUILD.md)

## 已知限制（誠實揭露）

- 看門狗路線保留原廠首頁與手機投放；**投放（Cast）不計時**
- 若看門狗程序被系統終止，採 fail-open（短暫空窗），非常駐 root 等級的強制方案
- 僅針對 Android TV／Google TV 設計；手機版 Android 未測試

## 變更紀錄

各版修正與驗證過程見 repo 內的 `修正紀錄-v*.md`、`計時修正紀錄-v*.md`、`資安修正紀錄-v2.md`。

---

# App Usage Timer

A homemade screen-time system for kids on Chromecast with Google TV 4K: a resident
"watchdog" app that times viewing apps, while parents receive reports and grant
approvals from their phones through their own Telegram bot.

**Current version: v14 (versionCode 16 / 1.12-v14, revision 3)** ｜ Project page: https://trevor-web-cloud.github.io/app-usage-timer/

📥 **Download the ready-made APK**: [Releases](https://github.com/trevor-web-cloud/app-usage-timer/releases) (no build needed)
📖 **Detailed setup guide** (bot creation, token, pairing, permissions, troubleshooting): [SETUP.md](SETUP.md)

## Features

- **Timing rules**: 30/45/60-minute sessions or custom (5–180 min); a customizable daily cap (default 120 minutes), enforced as a hard stop; any running session is closed out at midnight
- **Watchdog monitoring**: Accessibility events + UsageStats jointly track the foreground app; leaving a viewing app auto-pauses, and the remaining time can be resumed later
- **Gating**: opening a viewing app with no active session shows a full-screen gate — start a session, or ask a parent to approve; 1-minute warning, then auto-lock at time-up
- **System lockdown**: in kid mode, Settings and the Play Store are force-locked (re-asserted periodically so they can't be kept in front), preventing tampering or uninstalling this app
- **Parent mode**: entered by PIN or Telegram approval (2 hours or until standby); the watchdog sleeps while it's on
- **Telegram integration**: a dedicated bot reports session start/end/timeout; parents approve in a group chat, add +15/+30 minutes remotely, or end the session; either parent can approve, and approvals are attributed
- **Custom watch list**: YouTube, YouTube Kids, Netflix, Disney+ and Prime Video by default; any installed app can be added or removed (except the home launcher itself)
- **Phone web token setup (v14)**: no token.txt or ADB file push on the TV — unlock settings, open the one-time local web page, and paste the token from your phone (one-time code + Telegram verification; closes itself after saving)
- **Bilingual UI**: Traditional Chinese / English switcher

## How it works

Pure Android framework APIs, zero third-party libraries. A foreground service keeps
watch; all timer state is persisted and restored across reboots (including the daily
total, hardened against NTP clock-sync delay at boot). The bot token is never in the
source: a parent generates a token file on their PC, pushes it to the device via ADB,
and the app imports it into private storage on first launch and deletes the file. Since v14, a TV can instead use the one-time local web page ("Set token from phone"; see SETUP Step 5).

## Install (end users)

No build needed: download the latest APK from
[Releases](https://github.com/trevor-web-cloud/app-usage-timer/releases) and follow the
[detailed setup guide (SETUP.md)](SETUP.md) — create a Telegram bot, get its token,
connect via wireless ADB, import the token, grant the overlay / accessibility /
usage-access permissions, set a PIN, and pair the parents' group
(about 20–30 minutes; tested on Chromecast with Google TV 4K, Android TV OS 14).

## Build from source (developers)

- Requires: JDK 21, Gradle 8.9, Android SDK (compileSdk/targetSdk 34, minSdk 30)
- This repo ships only `gradle-wrapper.properties` (no wrapper scripts/jar):
  run `gradle assembleRelease` with a system Gradle 8.9, or generate the wrapper
  first with `gradle wrapper --gradle-version 8.9`
  (release is signed with the debug key for home sideloading)
- Build and design details: [README-BUILD.md](README-BUILD.md)

## Known limitations (honest disclosure)

- The watchdog design keeps the stock home screen and phone casting; **casting is not timed**
- If the watchdog process is killed by the system it fails open (a brief gap); this is not a root-level enforcer
- Designed for Android TV / Google TV only; phone Android is untested

## Changelog

Per-version fixes and verification notes (in Chinese) live in this repo as
`修正紀錄-v*.md`, `計時修正紀錄-v*.md` and `資安修正紀錄-v2.md`.
