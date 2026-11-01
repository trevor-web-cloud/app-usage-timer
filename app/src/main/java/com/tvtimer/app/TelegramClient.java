package com.tvtimer.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telegram Bot API client（HttpURLConnection，無外部函式庫）。
 *
 * - getUpdates 長輪詢（timeout 25s）在專屬執行緒上跑。
 * - 所有 send/edit/answer 都丟到單執行緒 executor，呼叫端永遠不卡住。
 * - 配對邏輯在本類別之外：本類別只把「群組訊息」與「按鈕回覆」往上送，
 *   由 WatchdogService 決定綁定哪個 chat、忽略哪些 chat。
 * - first-approval-wins 的狀態保存在 Prefs（TimerEngine 處理）；
 *   本類別另外以 callback_query id 去重，擋 Telegram 的重複投遞。
 */
public class TelegramClient {

    private static final String TAG = "TvTimerTelegram";
    private static final String API_BASE = "https://api.telegram.org/bot";

    public interface Listener {
        /** 群組／超級群組裡的任何訊息（配對與 /status 用）。text 可能為空字串；chatTitle 為群組名稱（配對確認顯示用）。 */
        void onGroupMessage(long chatId, String chatType, String chatTitle, String fromName, String text);

        void onCallback(String callbackId, long chatId, long messageId, String data, String fromName);

        /** 群組升級為 supergroup 導致 chat id 變更（SEC-06）；由上層決定是否更新綁定。 */
        void onChatMigrated(long oldChatId, long newChatId);
    }

    public interface SentCallback {
        void onSent(long messageId);
    }

    private final Context ctx;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<String> processedCallbacks =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private volatile boolean running;
    private volatile String token = "";
    private Thread poller;

    public TelegramClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    /** 確保 poller 以正確的 token 運行；token 為空時停下來。 */
    public synchronized void ensureRunning(String newToken) {
        if (newToken == null) {
            newToken = "";
        }
        if (newToken.isEmpty()) {
            stopLocked();
            token = "";
            return;
        }
        if (running && newToken.equals(token)) {
            return;
        }
        stopLocked();
        token = newToken;
        running = true;
        poller = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "tvtimer-tg-poll");
        poller.start();
    }

    public synchronized void stop() {
        stopLocked();
        token = "";
    }

    private void stopLocked() {
        running = false;
        if (poller != null) {
            poller.interrupt();
            poller = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ================= token validation (TOK-3, v12) =================

    /** 貼上 token 時的驗證結果回呼（在背景執行緒觸發，UI 自行切回主執行緒）。 */
    public interface ValidationCallback {
        void onResult(boolean ok, String username);
    }

    /**
     * 以候選 token 呼叫 getMe 驗證真偽，不影響目前 poller 使用的 token。
     * 成功時回傳 bot 的 username 供家長核對（避免把 A bot 的 token 存成 B bot）。
     */
    public static void validateTokenAsync(final String candidate, final ValidationCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String username = "";
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(API_BASE + candidate + "/getMe");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(15_000);
                    int code = conn.getResponseCode();
                    java.io.InputStream is = code >= 200 && code < 300
                            ? conn.getInputStream() : conn.getErrorStream();
                    if (is != null) {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(is, StandardCharsets.UTF_8));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line);
                        }
                        r.close();
                        JSONObject resp = new JSONObject(sb.toString());
                        if (resp.optBoolean("ok")) {
                            JSONObject result = resp.optJSONObject("result");
                            if (result != null) {
                                username = result.optString("username", "");
                                ok = !username.isEmpty();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "validateToken failed", e);
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
                if (cb != null) {
                    cb.onResult(ok, username);
                }
            }
        }, "tvtimer-tg-validate").start();
    }

    // ================= sending =================

    public void sendTextAsync(final long chatId, final String text) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                post("sendMessage", baseParams(chatId, text), 15_000);
            }
        });
    }

    /** 時段相關回報，附 [+15][+30][現在結束] 按鈕（家長可隨時直接操作）。 */
    public void sendSessionMessageAsync(final long chatId, final String text) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject p = baseParams(chatId, text);
                    p.put("reply_markup", sessionKeyboard());
                    post("sendMessage", p, 15_000);
                } catch (Exception e) {
                    Log.w(TAG, "sendSessionMessage failed", e);
                }
            }
        });
    }

    /** 核准請求訊息，附依 kind 組裝的核准按鈕；送出後回報 message_id。 */
    public void sendApprovalRequest(final long chatId, final String reqId, final int kind,
                                    final String text, final SentCallback cb) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                long msgId = 0;
                try {
                    JSONObject p = baseParams(chatId, text);
                    p.put("reply_markup", requestKeyboard(reqId, kind));
                    JSONObject resp = post("sendMessage", p, 15_000);
                    if (resp != null && resp.optBoolean("ok")) {
                        msgId = resp.optJSONObject("result") != null
                                ? resp.getJSONObject("result").optLong("message_id", 0) : 0;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "sendApprovalRequest failed", e);
                }
                if (cb != null) {
                    cb.onSent(msgId);
                }
            }
        });
    }

    public void editTextAsync(final long chatId, final long messageId, final String text) {
        if (messageId <= 0) {
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject p = baseParams(chatId, text);
                    p.put("message_id", messageId);
                    post("editMessageText", p, 15_000);
                } catch (Exception e) {
                    Log.w(TAG, "editMessageText failed", e);
                }
            }
        });
    }

    public void answerCallbackAsync(final String callbackId, final String text) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject p = new JSONObject();
                    p.put("callback_query_id", callbackId);
                    if (text != null && !text.isEmpty()) {
                        p.put("text", text);
                    }
                    post("answerCallbackQuery", p, 10_000);
                } catch (Exception e) {
                    Log.w(TAG, "answerCallbackQuery failed", e);
                }
            }
        });
    }

    // ================= polling =================

    private void pollLoop() {
        int backoffMs = 3_000;
        while (running) {
            try {
                long offset = Prefs.getUpdateOffset(ctx);
                JSONObject p = new JSONObject();
                p.put("offset", offset);
                p.put("timeout", 25);
                JSONArray allowed = new JSONArray();
                allowed.put("message");
                allowed.put("callback_query");
                p.put("allowed_updates", allowed);
                JSONObject resp = post("getUpdates", p, 40_000);
                if (resp != null && resp.optBoolean("ok")) {
                    backoffMs = 3_000;
                    JSONArray result = resp.optJSONArray("result");
                    if (result != null) {
                        for (int i = 0; i < result.length(); i++) {
                            JSONObject update = result.optJSONObject(i);
                            if (update == null) {
                                continue;
                            }
                            long updateId = update.optLong("update_id", 0);
                            Prefs.setUpdateOffset(ctx, updateId + 1);
                            handleUpdate(update);
                        }
                    }
                } else {
                    sleepQuiet(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "poll error", e);
                    sleepQuiet(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 30_000);
                }
            }
        }
    }

    private void handleUpdate(JSONObject update) {
        try {
            JSONObject msg = update.optJSONObject("message");
            if (msg != null) {
                JSONObject chat = msg.optJSONObject("chat");
                if (chat == null) {
                    return;
                }
                long chatId = chat.optLong("id", 0);
                String type = chat.optString("type", "");
                // 群組升級為 supergroup 的服務訊息（SEC-06）：舊群組收到
                // migrate_to_chat_id、新 supergroup 收到 migrate_from_chat_id，
                // 兩者都轉成同一個遷移事件交上層處理，不當一般訊息上送。
                if (msg.has("migrate_to_chat_id")) {
                    long newId = msg.optLong("migrate_to_chat_id", 0);
                    if (newId != 0 && chatId != 0) {
                        listener.onChatMigrated(chatId, newId);
                    }
                    return;
                }
                if (msg.has("migrate_from_chat_id")) {
                    long oldId = msg.optLong("migrate_from_chat_id", 0);
                    if (oldId != 0 && chatId != 0) {
                        listener.onChatMigrated(oldId, chatId);
                    }
                    return;
                }
                if ("group".equals(type) || "supergroup".equals(type)) {
                    String text = msg.optString("text", "");
                    String title = chat.optString("title", "");
                    listener.onGroupMessage(chatId, type, title,
                            fromName(msg.optJSONObject("from")), text);
                }
                return;
            }
            JSONObject cq = update.optJSONObject("callback_query");
            if (cq != null) {
                String cqId = cq.optString("id", "");
                if (cqId.isEmpty() || !processedCallbacks.add(cqId)) {
                    return; // 重複投遞，去重
                }
                trimCallbackSet();
                // 先消掉按鈕上的轉圈，再慢慢處理
                answerCallbackAsync(cqId, "");
                JSONObject m = cq.optJSONObject("message");
                long chatId = 0;
                long messageId = 0;
                if (m != null) {
                    JSONObject chat = m.optJSONObject("chat");
                    if (chat != null) {
                        chatId = chat.optLong("id", 0);
                    }
                    messageId = m.optLong("message_id", 0);
                }
                String data = cq.optString("data", "");
                listener.onCallback(cqId, chatId, messageId, data,
                        fromName(cq.optJSONObject("from")));
            }
        } catch (Exception e) {
            Log.w(TAG, "handleUpdate failed", e);
        }
    }

    private void trimCallbackSet() {
        synchronized (processedCallbacks) {
            while (processedCallbacks.size() > 200) {
                String first = processedCallbacks.iterator().next();
                processedCallbacks.remove(first);
            }
        }
    }

    private String fromName(JSONObject from) {
        if (from == null) {
            return L.t(ctx, "家長", "A parent");
        }
        String first = from.optString("first_name", "");
        String last = from.optString("last_name", "");
        String name = (first + " " + last).trim();
        if (name.isEmpty()) {
            name = from.optString("username", "");
        }
        return name.isEmpty() ? L.t(ctx, "家長", "A parent") : name;
    }

    // ================= keyboards =================

    private JSONObject sessionKeyboard() throws Exception {
        JSONArray rows = new JSONArray();
        rows.put(new JSONArray()
                .put(btn(L.t(ctx, "+15 分", "+15 min"), "ss:m15"))
                .put(btn(L.t(ctx, "+30 分", "+30 min"), "ss:m30")));
        rows.put(new JSONArray()
                .put(btn(L.t(ctx, "現在結束", "End now"), "ss:end")));
        JSONObject kb = new JSONObject();
        kb.put("inline_keyboard", rows);
        return kb;
    }

    private JSONObject requestKeyboard(String reqId, int kind) throws Exception {
        JSONArray rows = new JSONArray();
        switch (kind) {
            case TimerEngine.REQ_EXTEND:
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "+15 分", "+15 min"), "rq:" + reqId + ":m15"))
                        .put(btn(L.t(ctx, "+30 分", "+30 min"), "rq:" + reqId + ":m30")));
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "核准不限制", "Approve unlimited"), "rq:" + reqId + ":unlim"))
                        .put(btn(L.t(ctx, "拒絕", "Deny"), "rq:" + reqId + ":deny")));
                break;
            case TimerEngine.REQ_NEW_SESSION:
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "核准開局", "Approve session"), "rq:" + reqId + ":ok")));
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "+30 分", "+30 min"), "rq:" + reqId + ":m30"))
                        .put(btn(L.t(ctx, "核准不限制", "Approve unlimited"), "rq:" + reqId + ":unlim")));
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "拒絕", "Deny"), "rq:" + reqId + ":deny")));
                break;
            case TimerEngine.REQ_UNLIMITED:
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "核准不限制", "Approve unlimited"), "rq:" + reqId + ":unlim"))
                        .put(btn(L.t(ctx, "拒絕", "Deny"), "rq:" + reqId + ":deny")));
                break;
            case TimerEngine.REQ_PARENT:
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "核准家長模式", "Approve parent mode"), "rq:" + reqId + ":ok"))
                        .put(btn(L.t(ctx, "拒絕", "Deny"), "rq:" + reqId + ":deny")));
                break;
            default:
                rows.put(new JSONArray()
                        .put(btn(L.t(ctx, "拒絕", "Deny"), "rq:" + reqId + ":deny")));
                break;
        }
        JSONObject kb = new JSONObject();
        kb.put("inline_keyboard", rows);
        return kb;
    }

    private JSONObject btn(String text, String data) throws Exception {
        JSONObject b = new JSONObject();
        b.put("text", text);
        b.put("callback_data", data);
        return b;
    }

    // ================= HTTP =================

    private JSONObject baseParams(long chatId, String text) {
        try {
            JSONObject p = new JSONObject();
            p.put("chat_id", chatId);
            p.put("text", text);
            return p;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 同步 POST（只能在背景執行緒呼叫）。失敗回傳 null。 */
    private JSONObject post(String method, JSONObject params, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + token + "/" + method);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = params.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            JSONObject resp = new JSONObject(sb.toString());
            // 送訊息到已升級的群組時，API 以錯誤回應夾帶 parameters.migrate_to_chat_id
            // （SEC-06）；偵測到就通知上層更新綁定，後續送訊息改用新 id，不會無限重試舊 id。
            if (!resp.optBoolean("ok")) {
                JSONObject errParams = resp.optJSONObject("parameters");
                if (errParams != null && errParams.has("migrate_to_chat_id")) {
                    long newId = errParams.optLong("migrate_to_chat_id", 0);
                    long oldId = params.optLong("chat_id", 0);
                    if (newId != 0 && oldId != 0 && listener != null) {
                        listener.onChatMigrated(oldId, newId);
                    }
                }
            }
            return resp;
        } catch (Exception e) {
            Log.w(TAG, "POST " + method + " failed", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
