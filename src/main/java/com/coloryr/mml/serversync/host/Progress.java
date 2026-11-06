package com.coloryr.mml.serversync.host;

/**
 * 同步进度回调，宿主与更新包共享的接口。
 *
 * 自更新会把 update 目录里的新 jar 用独立类加载器装进来，入口类必须能引用到**同一个**
 * 接口实例，所以它单独放在 host 包：类加载器对这个包一律走父加载器，不加载 jar 里的副本。
 *
 * 界面模式由宿主侧窗口实现，命令行、无图形环境与 --no-gui 用 {@link #NONE}。
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
     * - `text`: 说明文字
     */
    void title(String text);

    /**
     * 当前阶段，例如「检查文件」「解压配置」
     *
     * - `text`: 阶段文字
     */
    void stage(String text);

    /**
     * 总步骤数，进度条按它分段
     *
     * - `total`: 步骤总数；小于等于 0 时进度条转为不确定状态
     */
    void total(int total);

    /**
     * 完成一步
     *
     * - `done`: 已完成的步骤数
     * - `detail`: 细节文字，例如正在处理的文件名
     */
    void step(int done, String detail);

    /**
     * 结束并关闭窗口
     *
     * - `ok`: 是否成功
     * - `message`: 结果说明；失败时是原因
     */
    void finish(boolean ok, String message);
}
