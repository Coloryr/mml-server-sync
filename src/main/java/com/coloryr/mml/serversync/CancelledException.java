package com.coloryr.mml.serversync;

/**
 * 用户在进度窗上选择「跳过更新」时抛出。
 *
 * 与普通失败区分开：它按退出码 0 收尾，让游戏照常启动；
 * 已经写进去的文件不回收，下次启动按清单补齐。
 */
final class CancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CancelledException(String message) {
        super(message);
    }
}
