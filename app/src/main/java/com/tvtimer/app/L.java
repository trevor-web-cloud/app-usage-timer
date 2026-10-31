package com.tvtimer.app;

import android.content.Context;

/**
 * v6：介面語言小工具。所有使用者可見字串都透過 t() 在中文（預設）與英文之間選擇；
 * 中文原文即 v5 以前寫死的字串，zh 模式下畫面與舊版完全相同。
 */
public final class L {

    private L() {
    }

    public static boolean isEn(Context c) {
        return "en".equals(Prefs.getLang(c));
    }

    /** en 模式回傳 en，否則回傳 zh。 */
    public static String t(Context c, String zh, String en) {
        return isEn(c) ? en : zh;
    }
}
