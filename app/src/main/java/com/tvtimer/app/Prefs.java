package com.tvtimer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * All persistent state lives in one private SharedPreferences file.
 * The bot token is NEVER compiled in: at install time it is pushed to
 * /sdcard/tvtimer/token.txt, imported here into private prefs on first
 * run, and the external file is deleted immediately afterwards.
 */
public final class Prefs {

    private static final String TAG = "TvTimerPrefs";
    private static final String FILE = "tvtimer";

    // ---- keys ----
    private static final String K_TOKEN = "bot_token";
    private static final String K_CHAT_ID = "chat_id";
    private static final String K_CHAT_TITLE = "chat_title";
    private static final String K_PAIR_CHAT_ID = "pair_chat_id";
    private static final String K_PAIR_TITLE = "pair_title";
    private static final String K_PAIR_CREATED_WALL = "pair_created_wall";
    private static final String K_UPDATE_OFFSET = "update_offset";
    private static final String K_PIN = "parent_pin";
    private static final String K_PIN_FAIL_COUNT = "pin_fail_count";
    private static final String K_PIN_LOCKOUT_WALL = "pin_lockout_wall";
    private static final String K_PIN_LOCKOUT_ELAPSED = "pin_lockout_elapsed";
    private static final String K_PIN_LOCKOUT_BOOT = "pin_lockout_boot";
    private static final String K_SESSION_ACTIVE = "session_active";
    private static final String K_SESSION_UNLIMITED = "session_unlimited";
    private static final String K_SESSION_REMAINING = "session_remaining_ms";
    private static final String K_SESSION_WATCHED = "session_watched_ms";
    private static final String K_SESSION_PKG = "session_pkg";
    /** MID-1：時段所屬日期（yyyy-MM-dd）；與今日日期不同代表時段跨過午夜，須結清。 */
    private static final String K_SESSION_DATE = "session_date";
    private static final String K_SESSION_WARNED = "session_warned";
    private static final String K_TODAY_DATE = "today_date";
    private static final String K_TODAY_MS = "today_ms";
    /** RB-1：最後一次寫入今日累計時的 wall-clock 毫秒（供換日判定檢查時鐘是否倒退）。 */
    private static final String K_TODAY_WALL_MS = "today_wall_ms";
    /** WL-1：家長自訂觀看清單，每行 "pkg\tlabel"。 */
    private static final String K_WATCH_CUSTOM = "watch_custom";
    /** v6：介面語言，"zh"（預設）或 "en"。 */
    private static final String K_LANG = "app_lang";
    private static final String K_CAP_NOTIFIED_DATE = "cap_notified_date";
    private static final String K_PARENT_DEADLINE_WALL = "parent_deadline_wall";
    private static final String K_REQ_SEQ = "req_seq";
    private static final String K_PEND_ID = "pend_id";
    private static final String K_PEND_KIND = "pend_kind";
    private static final String K_PEND_MINUTES = "pend_minutes";
    private static final String K_PEND_PKG = "pend_pkg";
    private static final String K_PEND_PURPOSE = "pend_purpose";
    private static final String K_PEND_MSG_ID = "pend_msg_id";
    private static final String K_PEND_RESOLVED = "pend_resolved";
    private static final String K_PEND_APPROVER = "pend_approver";
    private static final String K_PEND_CREATED_WALL = "pend_created_wall";
    private static final String K_WATCH_PREFIX = "watch_";

    public static final String DEFAULT_PIN = "0000";

    // ---- PIN 暴力嘗試節流（SEC-03）：連續錯誤達上限即鎖定一段時間 ----
    public static final int MAX_PIN_FAILURES = 5;
    public static final long PIN_LOCKOUT_MS = 5L * 60_000L;

    // ---- 待確認配對的有效期限（SEC-01） ----
    public static final long PAIRING_TTL_MS = 10L * 60_000L;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- bot token / pairing ----

    public static String getToken(Context c) {
        return sp(c).getString(K_TOKEN, "");
    }

    public static void setToken(Context c, String token) {
        sp(c).edit().putString(K_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /**
     * First-run provisioning: if no token is stored yet, try to read it
     * from /sdcard/tvtimer/token.txt (placed there by the installer via
     * adb push), import it into private prefs and delete the plain-text
     * file. Returns true when a token is available afterwards.
     * Reading shared storage needs the all-files-access appop grant that
     * the install steps apply (see README-BUILD.md); any failure simply
     * leaves the app in its "waiting for install setup" state.
     */
    public static boolean importTokenIfNeeded(Context c) {
        if (!getToken(c).isEmpty()) {
            return true;
        }
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "tvtimer/token.txt");
            if (f.isFile()) {
                String line = null;
                BufferedReader r = new BufferedReader(new FileReader(f));
                try {
                    String s;
                    while ((s = r.readLine()) != null) {
                        if (!s.trim().isEmpty()) {
                            line = s.trim();
                            break;
                        }
                    }
                } finally {
                    r.close();
                }
                if (line != null && line.length() >= 20) {
                    setToken(c, line);
                    // Best effort: remove the plain-text token from shared storage.
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    Log.i(TAG, "Bot token imported from token.txt");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "token.txt import failed", e);
        }
        return false;
    }

    public static long getChatId(Context c) {
        return sp(c).getLong(K_CHAT_ID, 0L);
    }

    public static void setChatId(Context c, long chatId) {
        sp(c).edit().putLong(K_CHAT_ID, chatId).apply();
    }

    /** 已綁定群組的名稱（供設定頁顯示，讓家長能核對綁定對象，SEC-01）。 */
    public static String getChatTitle(Context c) {
        return sp(c).getString(K_CHAT_TITLE, "");
    }

    public static void setChatTitle(Context c, String title) {
        sp(c).edit().putString(K_CHAT_TITLE, title == null ? "" : title).apply();
    }

    // ---- 待確認配對（SEC-01：改為兩步配對，先登記、TV 端確認後才綁定） ----

    public static void savePendingPairing(Context c, long chatId, String title) {
        sp(c).edit()
                .putLong(K_PAIR_CHAT_ID, chatId)
                .putString(K_PAIR_TITLE, title == null ? "" : title)
                .putLong(K_PAIR_CREATED_WALL, System.currentTimeMillis())
                .apply();
    }

    public static String getPairTitle(Context c) {
        return sp(c).getString(K_PAIR_TITLE, "");
    }

    public static void clearPendingPairing(Context c) {
        sp(c).edit()
                .putLong(K_PAIR_CHAT_ID, 0L)
                .putString(K_PAIR_TITLE, "")
                .putLong(K_PAIR_CREATED_WALL, 0L)
                .apply();
    }

    /**
     * 回傳仍在有效期限（{@link #PAIRING_TTL_MS}）內的待確認配對 chat id；
     * 沒有或已過期時回傳 0（過期者順手清除）。消費端一律經由此方法取值，
     * 確保過期配對不會被確認綁定。
     */
    public static long pendingPairingId(Context c) {
        long id = sp(c).getLong(K_PAIR_CHAT_ID, 0L);
        if (id == 0) {
            return 0;
        }
        long created = sp(c).getLong(K_PAIR_CREATED_WALL, 0L);
        if (created == 0
                || System.currentTimeMillis() - created > PAIRING_TTL_MS) {
            clearPendingPairing(c);
            return 0;
        }
        return id;
    }

    public static long getUpdateOffset(Context c) {
        return sp(c).getLong(K_UPDATE_OFFSET, 0L);
    }

    public static void setUpdateOffset(Context c, long offset) {
        sp(c).edit().putLong(K_UPDATE_OFFSET, offset).apply();
    }

    // ---- parent PIN ----

    public static String getPin(Context c) {
        return sp(c).getString(K_PIN, DEFAULT_PIN);
    }

    public static void setPin(Context c, String pin) {
        sp(c).edit().putString(K_PIN, pin).apply();
    }

    // ---- PIN 嘗試節流（SEC-03；gate 與 MainActivity 共用同一組持久化計數） ----

    /** 目前開機次數（Settings.Global.BOOT_COUNT）；讀不到時回傳 -1。 */
    private static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * PIN 鎖定剩餘毫秒（0 = 未鎖定）。
     * 截止時間同時存 wall-clock 與 elapsedRealtime 兩份：
     * wall-clock 份跨重開機有效、是主要依據；elapsedRealtime 份只在
     * 「同一次開機」內有效（以 BOOT_COUNT 把關，避免重開機後誤判），
     * 用來擋同一次開機內把系統時間往回調的繞法。兩者取較大剩餘值。
     */
    public static long pinLockoutRemainingMs(Context c) {
        long remain = 0;
        long wallDeadline = sp(c).getLong(K_PIN_LOCKOUT_WALL, 0L);
        if (wallDeadline > 0) {
            remain = Math.max(remain, wallDeadline - System.currentTimeMillis());
        }
        long elapsedDeadline = sp(c).getLong(K_PIN_LOCKOUT_ELAPSED, 0L);
        int bootStored = sp(c).getInt(K_PIN_LOCKOUT_BOOT, -1);
        if (elapsedDeadline > 0 && bootStored >= 0 && bootCount(c) == bootStored) {
            remain = Math.max(remain, elapsedDeadline - SystemClock.elapsedRealtime());
        }
        return Math.max(0, remain);
    }

    /**
     * 記錄一次 PIN 輸入錯誤；連續達 {@link #MAX_PIN_FAILURES} 次即進入
     * {@link #PIN_LOCKOUT_MS} 鎖定並把連續錯誤數歸零（鎖定期滿後重新起算）。
     * 回傳記錄後鎖定剩餘毫秒（0 = 尚未鎖定）。
     */
    public static long recordPinFailure(Context c) {
        long locked = pinLockoutRemainingMs(c);
        if (locked > 0) {
            return locked;
        }
        int n = sp(c).getInt(K_PIN_FAIL_COUNT, 0) + 1;
        SharedPreferences.Editor e = sp(c).edit().putInt(K_PIN_FAIL_COUNT, n);
        if (n >= MAX_PIN_FAILURES) {
            e.putLong(K_PIN_LOCKOUT_WALL, System.currentTimeMillis() + PIN_LOCKOUT_MS)
                    .putLong(K_PIN_LOCKOUT_ELAPSED,
                            SystemClock.elapsedRealtime() + PIN_LOCKOUT_MS)
                    .putInt(K_PIN_LOCKOUT_BOOT, bootCount(c))
                    .putInt(K_PIN_FAIL_COUNT, 0);
        }
        e.apply();
        return pinLockoutRemainingMs(c);
    }

    /** PIN 驗證成功：清除連續錯誤計數與鎖定狀態。 */
    public static void resetPinFailures(Context c) {
        sp(c).edit()
                .putInt(K_PIN_FAIL_COUNT, 0)
                .putLong(K_PIN_LOCKOUT_WALL, 0L)
                .putLong(K_PIN_LOCKOUT_ELAPSED, 0L)
                .putInt(K_PIN_LOCKOUT_BOOT, -1)
                .apply();
    }

    // ---- watching-app toggles ----

    public static boolean getWatchEnabled(Context c, String pkg) {
        return sp(c).getBoolean(K_WATCH_PREFIX + pkg, true);
    }

    public static void setWatchEnabled(Context c, String pkg, boolean enabled) {
        sp(c).edit().putBoolean(K_WATCH_PREFIX + pkg, enabled).apply();
    }

    // ---- v6：介面語言 ----

    public static String getLang(Context c) {
        return sp(c).getString(K_LANG, "zh");
    }

    public static void setLang(Context c, String lang) {
        sp(c).edit().putString(K_LANG, lang).apply();
    }

    // ---- WL-1：家長自訂觀看清單（pkg → 顯示名稱，保持加入順序） ----

    // OPT-1（v9 自測優化）：同 getRemovedPresets 的解析快取（原始字串為鍵、寫入端先複製）。
    private static String customCacheRaw = null;
    private static java.util.LinkedHashMap<String, String> customCache = null;

    public static java.util.LinkedHashMap<String, String> getCustomWatch(Context c) {
        String raw = sp(c).getString(K_WATCH_CUSTOM, "");
        if (raw == null) {
            raw = "";
        }
        if (customCache != null && raw.equals(customCacheRaw)) {
            return customCache;
        }
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (!raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    out.put(line.substring(0, tab), line.substring(tab + 1));
                } else if (!line.isEmpty()) {
                    out.put(line, line);
                }
            }
        }
        customCacheRaw = raw;
        customCache = out;
        return out;
    }

    public static void addCustomWatch(Context c, String pkg, String label) {
        java.util.LinkedHashMap<String, String> m =
                new java.util.LinkedHashMap<>(getCustomWatch(c));
        if (!m.containsKey(pkg)) {
            m.put(pkg, (label == null || label.isEmpty()) ? pkg : label);
            saveCustomWatch(c, m);
        }
    }

    public static void removeCustomWatch(Context c, String pkg) {
        java.util.LinkedHashMap<String, String> m =
                new java.util.LinkedHashMap<>(getCustomWatch(c));
        if (m.remove(pkg) != null) {
            saveCustomWatch(c, m);
        }
        sp(c).edit().remove(K_WATCH_PREFIX + pkg).apply();
    }

    private static void saveCustomWatch(Context c, java.util.Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        sp(c).edit().putString(K_WATCH_CUSTOM, sb.toString()).apply();
    }

    // ---- WL-3：被家長移除的預設清單（pkg 集合，換行分隔） ----

    private static final String K_WATCH_REMOVED = "watch_removed";

    // OPT-1（v9 自測優化）：解析快取——isWatchingApp 在 tick／心跳熱路徑每秒多次呼叫，
    // 避免每次重新 split。快取以原始字串為鍵；寫入端一律先複製再改（見 setPresetRemoved），不污染快取。
    private static String removedCacheRaw = null;
    private static java.util.Set<String> removedCache = null;

    public static java.util.Set<String> getRemovedPresets(Context c) {
        String raw = sp(c).getString(K_WATCH_REMOVED, "");
        if (raw == null) {
            raw = "";
        }
        if (removedCache != null && raw.equals(removedCacheRaw)) {
            return removedCache;
        }
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!raw.isEmpty()) {
            for (String p : raw.split("\n")) {
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        removedCacheRaw = raw;
        removedCache = out;
        return out;
    }

    public static void setPresetRemoved(Context c, String pkg, boolean removed) {
        java.util.Set<String> s = new java.util.HashSet<>(getRemovedPresets(c));
        if (removed) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : s) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p);
        }
        sp(c).edit().putString(K_WATCH_REMOVED, sb.toString()).apply();
    }

    // ---- session state (written on EVERY transition by TimerEngine) ----

    public static boolean getSessionActive(Context c) {
        return sp(c).getBoolean(K_SESSION_ACTIVE, false);
    }

    public static void setSessionActive(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SESSION_ACTIVE, v).apply();
    }

    public static boolean getSessionUnlimited(Context c) {
        return sp(c).getBoolean(K_SESSION_UNLIMITED, false);
    }

    public static void setSessionUnlimited(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SESSION_UNLIMITED, v).apply();
    }

    public static long getSessionRemainingMs(Context c) {
        return sp(c).getLong(K_SESSION_REMAINING, 0L);
    }

    public static void setSessionRemainingMs(Context c, long v) {
        sp(c).edit().putLong(K_SESSION_REMAINING, v).apply();
    }

    public static long getSessionWatchedMs(Context c) {
        return sp(c).getLong(K_SESSION_WATCHED, 0L);
    }

    public static void setSessionWatchedMs(Context c, long v) {
        sp(c).edit().putLong(K_SESSION_WATCHED, v).apply();
    }

    public static String getSessionPkg(Context c) {
        return sp(c).getString(K_SESSION_PKG, "");
    }

    public static void setSessionPkg(Context c, String v) {
        sp(c).edit().putString(K_SESSION_PKG, v == null ? "" : v).apply();
    }

    public static String getSessionDate(Context c) {
        return sp(c).getString(K_SESSION_DATE, "");
    }

    public static void setSessionDate(Context c, String v) {
        sp(c).edit().putString(K_SESSION_DATE, v == null ? "" : v).apply();
    }

    public static boolean getSessionWarned(Context c) {
        return sp(c).getBoolean(K_SESSION_WARNED, false);
    }

    public static void setSessionWarned(Context c, boolean v) {
        sp(c).edit().putBoolean(K_SESSION_WARNED, v).apply();
    }

    // ---- daily totals ----

    public static String getTodayDate(Context c) {
        return sp(c).getString(K_TODAY_DATE, "");
    }

    public static void setTodayDate(Context c, String v) {
        sp(c).edit().putString(K_TODAY_DATE, v).apply();
    }

    public static long getTodayMs(Context c) {
        return sp(c).getLong(K_TODAY_MS, 0L);
    }

    public static void setTodayMs(Context c, long v) {
        sp(c).edit().putLong(K_TODAY_MS, v).apply();
    }

    public static long getTodayWallMs(Context c) {
        return sp(c).getLong(K_TODAY_WALL_MS, 0L);
    }

    public static void setTodayWallMs(Context c, long v) {
        sp(c).edit().putLong(K_TODAY_WALL_MS, v).apply();
    }

    public static String getCapNotifiedDate(Context c) {
        return sp(c).getString(K_CAP_NOTIFIED_DATE, "");
    }

    public static void setCapNotifiedDate(Context c, String v) {
        sp(c).edit().putString(K_CAP_NOTIFIED_DATE, v).apply();
    }

    // ---- parent mode ----

    /** Wall-clock deadline (ms since epoch); 0 = parent mode off. */
    public static long getParentDeadlineWall(Context c) {
        return sp(c).getLong(K_PARENT_DEADLINE_WALL, 0L);
    }

    public static void setParentDeadlineWall(Context c, long v) {
        sp(c).edit().putLong(K_PARENT_DEADLINE_WALL, v).apply();
    }

    // ---- pending Telegram approval request (at most one at a time) ----

    public static int nextReqSeq(Context c) {
        int seq = sp(c).getInt(K_REQ_SEQ, 0) + 1;
        sp(c).edit().putInt(K_REQ_SEQ, seq).apply();
        return seq;
    }

    public static void savePending(Context c, String id, int kind, int minutes,
                                   String pkg, int purpose) {
        sp(c).edit()
                .putString(K_PEND_ID, id)
                .putInt(K_PEND_KIND, kind)
                .putInt(K_PEND_MINUTES, minutes)
                .putString(K_PEND_PKG, pkg == null ? "" : pkg)
                .putInt(K_PEND_PURPOSE, purpose)
                .putLong(K_PEND_MSG_ID, 0L)
                .putBoolean(K_PEND_RESOLVED, false)
                .putString(K_PEND_APPROVER, "")
                .putLong(K_PEND_CREATED_WALL, System.currentTimeMillis())
                .apply();
    }

    /** pending 建立時間（wall-clock ms；0 = 無資料，SEC-04 逾時判斷用）。 */
    public static long getPendCreatedWall(Context c) {
        return sp(c).getLong(K_PEND_CREATED_WALL, 0L);
    }

    public static String getPendId(Context c) {
        return sp(c).getString(K_PEND_ID, "");
    }

    public static int getPendKind(Context c) {
        return sp(c).getInt(K_PEND_KIND, 0);
    }

    public static int getPendMinutes(Context c) {
        return sp(c).getInt(K_PEND_MINUTES, 0);
    }

    public static String getPendPkg(Context c) {
        return sp(c).getString(K_PEND_PKG, "");
    }

    public static int getPendPurpose(Context c) {
        return sp(c).getInt(K_PEND_PURPOSE, 0);
    }

    public static long getPendMsgId(Context c) {
        return sp(c).getLong(K_PEND_MSG_ID, 0L);
    }

    public static void setPendMsgId(Context c, long v) {
        sp(c).edit().putLong(K_PEND_MSG_ID, v).apply();
    }

    public static boolean getPendResolved(Context c) {
        return sp(c).getBoolean(K_PEND_RESOLVED, false);
    }

    public static void setPendResolved(Context c, boolean v, String approver) {
        sp(c).edit()
                .putBoolean(K_PEND_RESOLVED, v)
                .putString(K_PEND_APPROVER, approver == null ? "" : approver)
                .apply();
    }

    public static String getPendApprover(Context c) {
        return sp(c).getString(K_PEND_APPROVER, "");
    }

    public static void clearPending(Context c) {
        sp(c).edit()
                .putString(K_PEND_ID, "")
                .putInt(K_PEND_KIND, 0)
                .putInt(K_PEND_MINUTES, 0)
                .putString(K_PEND_PKG, "")
                .putInt(K_PEND_PURPOSE, 0)
                .putBoolean(K_PEND_RESOLVED, false)
                .putString(K_PEND_APPROVER, "")
                .putLong(K_PEND_MSG_ID, 0L)
                .putLong(K_PEND_CREATED_WALL, 0L)
                .apply();
    }
}
