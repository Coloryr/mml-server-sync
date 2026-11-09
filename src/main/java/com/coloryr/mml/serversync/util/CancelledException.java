package com.coloryr.mml.serversync.util;

/** 用户在进度窗上选择「跳过更新」时抛出。 */
public final class CancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CancelledException(String message) {
        super(message);
    }
}
