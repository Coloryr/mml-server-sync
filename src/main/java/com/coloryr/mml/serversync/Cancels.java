package com.coloryr.mml.serversync;

/**
 * 取消状态：进度窗把选择写进系统属性，主流程在耗时循环里轮询。
 *
 * 用系统属性而不是对象引用，是因为更新包可能跑在旧宿主上，两边类加载器不同；
 * 属性是 JVM 全局的，跨版本都读得到。
 */
final class Cancels {
    /** 值：skip / end；没有表示没取消 */
    static final String KEY = "mml.serverSync.cancel";

    private Cancels() {}

    /**
     * 有取消就立刻抛出去
     *
     * - skip: 抛 {@link CancelledException}，按退出码 0 收尾
     * - end: 抛 {@link SyncAbortException}，按退出码 1 收尾
     */
    static void check() {
        String state = System.getProperty(KEY, "");
        if ("end".equalsIgnoreCase(state)) {
            throw new SyncAbortException(Text.of(Msg.CANCEL_AFTER_ENDED));
        }
        if ("skip".equalsIgnoreCase(state)) {
            throw new CancelledException(Text.of(Msg.UPDATE_CANCELLED_MIDWAY));
        }
    }

    /** 清掉取消状态，一次运行结束后调用 */
    static void clear() {
        System.clearProperty(KEY);
    }
}
