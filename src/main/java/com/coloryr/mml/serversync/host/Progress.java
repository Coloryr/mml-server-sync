package com.coloryr.mml.serversync.host;

/**
 * 同步进度回调。
 *
 * 放在 host 包，保证宿主与更新包拿到同一个接口类。
 */
public interface Progress {
    /** 什么都不做的实现 */
    Progress NONE = new Progress() {
        @Override
        public void title(String text) {
        }

        @Override
        public void stage(String text) {
        }

        @Override
        public void total(int total) {
        }

        @Override
        public void step(int done, String detail) {
        }

        @Override
        public void finish(boolean ok, String message) {
        }
    };

    /**
     * 窗口顶部的说明，一般是包名与版本
     *
     * @param text 说明文字
     */
    void title(String text);

    /**
     * 当前阶段，例如「检查文件」「解压配置」
     *
     * @param text 阶段文字
     */
    void stage(String text);

    /**
     * 总步骤数，进度条按它分段
     *
     * @param total 步骤总数；小于等于 0 时进度条转为不确定状态
     */
    void total(int total);

    /**
     * 完成一步
     *
     * @param done 已完成的步骤数
     * @param detail 细节文字，例如正在处理的文件名
     */
    void step(int done, String detail);

    /**
     * 结束并关闭窗口
     *
     * @param ok 是否成功
     * @param message 结果说明；失败时是原因
     */
    void finish(boolean ok, String message);
}
