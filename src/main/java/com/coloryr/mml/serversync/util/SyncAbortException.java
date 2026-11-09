package com.coloryr.mml.serversync.util;

/** 用户在二次确认里选择「结束游戏」时抛出。 */
public final class SyncAbortException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SyncAbortException(String message) {
        super(message);
    }
}
