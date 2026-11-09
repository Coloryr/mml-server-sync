package com.coloryr.mml.serversync.util;

/** 取消与暂停状态：进度窗把选择写进系统属性，主流程在耗时循环里轮询。 */
public final class Cancels {
    /** 值：skip / end；没有表示没取消 */
    public static final String KEY = "mml.serverSync.cancel";
    /** 值 "true" 表示已暂停：取消确认框打开期间置位，下载线程在检查点停住 */
    public static final String PAUSE_KEY = "mml.serverSync.paused";

    private Cancels() {}

/** 暂停同步；下载线程在下一个字节检查点停住，弹窗期间不再继续下载 */
    public static void pause() {
        System.setProperty(PAUSE_KEY, "true");
    }

/** 解除暂停；取消状态另外由 {@link #KEY} 控制 */
    public static void resume() {
        System.clearProperty(PAUSE_KEY);
    }

/** 当前是否暂停 */
    public static boolean paused() {
        return "true".equalsIgnoreCase(System.getProperty(PAUSE_KEY, ""));
    }

/** 有取消就立刻抛出去；暂停时先在这里等玩家选择 */
    public static void check() {
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
    public static void clear() {
        System.clearProperty(KEY);
        System.clearProperty(PAUSE_KEY);
    }
}
