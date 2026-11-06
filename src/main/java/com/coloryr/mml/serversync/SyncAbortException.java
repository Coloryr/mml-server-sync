package com.coloryr.mml.serversync;

/**
 * 用户在二次确认里选择「结束游戏」时抛出。
 *
 * 与普通同步失败区分开：它不打印「同步失败」，直接以退出码 1 收尾，
 * 让 agent / 启动前脚本据此中止游戏启动。
 */
final class SyncAbortException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SyncAbortException(String message) {
        super(message);
    }
}
