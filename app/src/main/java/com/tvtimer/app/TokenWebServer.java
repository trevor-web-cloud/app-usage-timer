package com.tvtimer.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WEB-1（v14）：電視端 token 便捷輸入——一次性區網設定頁。
 *
 * 家長解鎖設定後按「用手機設定 Token」才啟動：綁定區網、本機產生一次性
 * 4 位碼，手機連同一個 Wi-Fi 開頁面輸碼＋貼上 token。送出後先以 getMe
 * 驗證（重用 TelegramClient.validateTokenAsync，在本伺服器執行緒上以
 * latch 同步等待），成功才比照 TOK-3 經 Prefs.applyTokenChange 存檔。
 *
 * 安全邊界：僅開啟期間存在；成功、代碼錯 5 次、逾 5 分鐘或家長手動
 * 關閉，任一成立即停服。token 不寫入 log、不回顯到結果頁。
 * 零第三方相依：手寫最小 HTTP（只認 GET / 與 POST /submit）。
 */
public final class TokenWebServer {

    private static final String TAG = "TvTimerWebSetup";
    public static final long TTL_MS = 5L * 60_000L;
    public static final int MAX_WRONG_CODE = 5;

    public interface Listener {
        /** 驗證成功且已存檔（replaced=換掉舊 token；same=與現行 token 相同未變更）。 */
        void onTokenSaved(String botUsername, boolean replaced, boolean same);

        /** 伺服器已停止（成功／逾時／錯誤過多／手動關閉）。 */
        void onClosed();
    }

    private final Context ctx;
    private final Listener listener;
    private final String code;
    private volatile boolean stopped;
    private volatile long deadlineMs;
    private volatile int wrongCodeCount;
    private ServerSocket serverSocket;
    private Thread thread;
    private String url = "";

    public TokenWebServer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.code = String.format("%04d", new SecureRandom().nextInt(10_000));
    }

    public String getCode() {
        return code;
    }

    public String getUrl() {
        return url;
    }

    public boolean isStopped() {
        return stopped;
    }

    /** 找區網 IPv4 位址（手機要連同一個區網）；找不到回傳 null。 */
    public static String lanIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "lanIpv4 failed", e);
        }
        return null;
    }

    /** 啟動伺服器；成功回傳 true 並可由 getUrl() 取得網址。 */
    public synchronized boolean start() {
        String ip = lanIpv4();
        if (ip == null) {
            return false;
        }
        try {
            serverSocket = new ServerSocket(0);
            serverSocket.setSoTimeout(1_000);
        } catch (Exception e) {
            Log.w(TAG, "bind failed", e);
            return false;
        }
        url = "http://" + ip + ":" + serverSocket.getLocalPort() + "/";
        deadlineMs = System.currentTimeMillis() + TTL_MS;
        stopped = false;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "tvtimer-web-setup");
        thread.start();
        return true;
    }

    public synchronized void stop() {
        stopped = true;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
    }

    private void acceptLoop() {
        try {
            while (!stopped) {
                if (System.currentTimeMillis() > deadlineMs) {
                    break; // 逾時自動關閉
                }
                try {
                    Socket s = serverSocket.accept();
                    handle(s);
                } catch (java.net.SocketTimeoutException e) {
                    // 每秒醒來檢查逾時／停止旗標
                } catch (Exception e) {
                    if (!stopped) {
                        Log.w(TAG, "accept failed", e);
                    }
                }
            }
        } finally {
            stop();
            if (listener != null) {
                listener.onClosed();
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15_000);
            InputStream in = s.getInputStream();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String requestLine = r.readLine();
            if (requestLine == null) {
                s.close();
                return;
            }
            int contentLength = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(
                                line.substring("content-length:".length()).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            OutputStream out = s.getOutputStream();
            if ("GET".equals(method) && ("/".equals(path) || path.startsWith("/?"))) {
                respond(out, 200, formPage(null));
            } else if ("POST".equals(method) && "/submit".equals(path)) {
                String body = readBody(r, contentLength);
                handleSubmit(out, parseForm(body));
            } else {
                respond(out, 404, simplePage(t("找不到頁面", "Not found"), ""));
            }
            out.flush();
            s.close();
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String readBody(BufferedReader r, int len) throws Exception {
        if (len <= 0 || len > 16_384) {
            return "";
        }
        char[] buf = new char[len];
        int off = 0;
        while (off < len) {
            int n = r.read(buf, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return new String(buf, 0, off);
    }

    private void handleSubmit(OutputStream out, Map<String, String> form) throws Exception {
        if (System.currentTimeMillis() > deadlineMs) {
            respond(out, 410, simplePage(
                    t("頁面已逾時關閉，請回電視重新開啟", "This page has expired — reopen it on the TV"), ""));
            stop();
            return;
        }
        String given = form.getOrDefault("code", "").trim();
        if (!code.equals(given)) {
            wrongCodeCount++;
            if (wrongCodeCount >= MAX_WRONG_CODE) {
                respond(out, 403, simplePage(
                        t("代碼錯誤次數過多，頁面已關閉，請回電視重新開啟",
                                "Too many wrong codes — this page is closed. Reopen it on the TV"), ""));
                stop();
            } else {
                respond(out, 403, formPage(
                        t("代碼不正確，請對照電視畫面重新輸入", "Wrong code — check the TV screen and try again")));
            }
            return;
        }
        final String candidate = form.getOrDefault("token", "").trim();
        if (candidate.length() < 20) {
            respond(out, 400, formPage(
                    t("token 格式不正確（長度不足），未存檔", "That token looks too short — nothing was saved")));
            return;
        }
        // 以 getMe 驗證（在伺服器執行緒上同步等待；不盲存）
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] okBox = {false};
        final String[] userBox = {""};
        TelegramClient.validateTokenAsync(candidate, new TelegramClient.ValidationCallback() {
            @Override
            public void onResult(boolean ok, String username) {
                okBox[0] = ok;
                userBox[0] = username;
                latch.countDown();
            }
        });
        boolean finished;
        try {
            finished = latch.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            finished = false;
        }
        if (!finished || !okBox[0]) {
            respond(out, 400, formPage(
                    t("Telegram 驗證失敗，token 不正確，未存檔，請確認後重試",
                            "Telegram could not verify that token — nothing was saved. Check it and try again")));
            return;
        }
        boolean same = candidate.equals(Prefs.getToken(ctx));
        boolean replaced = !same && !Prefs.getToken(ctx).isEmpty();
        if (!same) {
            Prefs.applyTokenChange(ctx, candidate);
        }
        respond(out, 200, simplePage(
                t("✅ 已儲存，bot：@", "✅ Saved. Bot: @") + escapeHtml(userBox[0]),
                t("可以關閉這個頁面，回到電視繼續配對。", "You can close this page and continue pairing on the TV.")));
        stop();
        if (listener != null) {
            listener.onTokenSaved(userBox[0], replaced, same);
        }
    }

    // ================= pages =================

    private String t(String zh, String en) {
        return L.t(ctx, zh, en);
    }

    private String formPage(String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escapeHtml(t("應用程式使用計時", "App Usage Timer"))).append("</title>")
                .append("<style>body{font-family:sans-serif;margin:24px;font-size:18px}")
                .append("input{width:100%;padding:12px;font-size:18px;margin:8px 0;box-sizing:border-box}")
                .append("button{width:100%;padding:14px;font-size:19px;background:#f5a623;border:0;border-radius:8px}")
                .append(".err{color:#c00}</style></head><body>");
        sb.append("<h2>").append(escapeHtml(t("設定 Bot Token", "Set bot token"))).append("</h2>");
        if (error != null) {
            sb.append("<p class=\"err\">").append(escapeHtml(error)).append("</p>");
        }
        sb.append("<p>").append(escapeHtml(t(
                "輸入電視畫面上的一次性代碼，並貼上 BotFather 給的 token。",
                "Enter the one-time code shown on the TV, and paste the token from BotFather."))).append("</p>");
        sb.append("<form method=\"POST\" action=\"/submit\">")
                .append("<label>").append(escapeHtml(t("一次性代碼（4 位）", "One-time code (4 digits)"))).append("</label>")
                .append("<input name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" required>")
                .append("<label>Bot Token</label>")
                .append("<input name=\"token\" type=\"password\" autocomplete=\"off\" required>")
                .append("<button type=\"submit\">").append(escapeHtml(t("驗證並儲存", "Verify & save"))).append("</button>")
                .append("</form></body></html>");
        return sb.toString();
    }

    private String simplePage(String heading, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escapeHtml(t("應用程式使用計時", "App Usage Timer")) + "</title>"
                + "<style>body{font-family:sans-serif;margin:24px;font-size:18px}</style></head><body>"
                + "<h2>" + heading + "</h2><p>" + escapeHtml(body) + "</p></body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '+') {
                    bytes.write(' ');
                } else if (ch == '%' && i + 2 < s.length()) {
                    bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    bytes.write((byte) ch);
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private void respond(OutputStream out, int status, String html) throws Exception {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Gone";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }
}
