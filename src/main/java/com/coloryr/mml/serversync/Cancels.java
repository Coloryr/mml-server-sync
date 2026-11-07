package com.coloryr.mml.serversync;

/**
 * 取消与暂停状态：进度窗把选择写进系统属性，主流程在耗时循环里轮询。
 *
 * 用系统属性而不是对象引用，是因为更新包可能跑在旧宿主上，两边类加载器不同；
 * 属性是 JVM 全局的，跨版本都读得到。
 */
final class Cancels {
    /** 值：skip / end；没有表示没取消 */
    static final String KEY = "mml.serverSync.cancel";
    /** 值 "true" 表示已暂停：取消确认框打开期间置位，下载线程在检查点停住 */
    static final String PAUSE_KEY = "mml.serverSync.paused";

    private Cancels() {}

    /** 暂停同步；下载线程在下一个字节检查点停住，弹窗期间不再继续下载 */
    static void pause() {
        System.setProperty(PAUSE_KEY, "true");
    }

    /** 解除暂停；取消状态另外由 {@link #KEY} 控制 */
    static void resume() {
        System.clearProperty(PAUSE_KEY);
    }

    /** 当前是否暂停 */
    static boolean paused() {
        return "true".equalsIgnoreCase(System.getProperty(PAUSE_KEY, ""));
    }

    /**
     * 有取消就立刻抛出去；暂停时先在这里等玩家选择
     *
     * - skip: 抛 {@link CancelledException}，按退出码 0 收尾
     * - end: 抛 {@link SyncAbortException}，按退出码 1 收尾
     */
    static void check() {
        awaitResume();
        String state = System.getProperty(KEY, "");
        if ("end".equalsIgnoreCase(state)) {
            throw new SyncAbortException(Text.of(Msg.CANCEL_AFTER_ENDED));
        }
        if ("skip".equalsIgnoreCase(state)) {
            throw new CancelledException(Text.of(Msg.UPDATE_CANCELLED_MIDWAY));
        }
    }

    /** 暂停时在检查点停住；出现取消状态时立刻放行，让上面的取消分支执行 */
    private static void awaitResume() {
        while (paused() && System.getProperty(KEY, "").isEmpty()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 清掉暂停与取消状态，一次运行结束后调用 */
    static void clear() {
        System.clearProperty(KEY);
        System.clearProperty(PAUSE_KEY);
    }
}
