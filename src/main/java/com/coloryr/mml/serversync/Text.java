package com.coloryr.mml.serversync;

import java.util.Locale;

/**
 * 文案出口：按键取当前语言的成品。
 *
 * 语言按系统语言自动选：`zh*` 用中文，其余一律英文；可用 `-Dmml.serverSync.lang=zh|en`
 * 或环境变量 `MML_SERVER_SYNC_LANG` 覆盖。文案本身在 {@link Msg} 里，中英各一份。
 */
final class Text {
    /** 是否用中文 */
    private static final boolean ZH = detectChinese();

    private Text() {}

    /** 当前是否中文 */
    static boolean chinese() {
        return ZH;
    }

    /**
     * 取一条文案
     *
     * - `msg`: 文案键
     * - `args`: 格式化参数，对应格式串里的 %s；没有参数时原样返回
     *
     * # 返回值
     *
     * 当前语言下格式化好的文案
     */
    static String of(Msg msg, Object... args) {
        String pattern = ZH ? msg.zh() : msg.en();
        return args.length == 0 ? pattern : String.format(pattern, args);
    }

    private static boolean detectChinese() {
        String override = System.getProperty("mml.serverSync.lang", "").trim();
        if (override.isEmpty()) {
            String env = System.getenv("MML_SERVER_SYNC_LANG");
            override = env == null ? "" : env.trim();
        }
        if (!override.isEmpty()) {
            return override.toLowerCase(Locale.ROOT).startsWith("zh");
        }
        return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }
}
