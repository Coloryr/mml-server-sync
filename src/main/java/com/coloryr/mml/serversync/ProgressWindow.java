package com.coloryr.mml.serversync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.coloryr.mml.serversync.host.Progress;

/**
 * 同步进度窗口。
 *
 * 同步经常跑在游戏进程的 premain 里：窗口要在游戏启动前出现、同步一结束就消失。
 * 除了画阶段与步骤，还带一个「取消更新」：点它先问一句跳过本次更新还是结束游戏，
 * 真正的停止由主流程在安全点检查系统属性后执行。
 *
 * 所有更新方法都可以从任意线程调用，内部统一排到 Swing 事件线程。
 */
final class ProgressWindow implements Progress {
    /** 窗口标题 */
    private static final String TITLE = Text.of(Msg.MML_SERVER_INSTANCE_SYNC);

    /** 取消状态写在系统属性里，主流程按它决定停不停；值：skip / end */
    static final String CANCEL_KEY = "mml.serverSync.cancel";

    private final JFrame frame;
    private final JLabel titleLabel;
    private final JLabel stageLabel;
    private final JLabel detailLabel;
    private final JProgressBar bar;
    private final JButton cancelButton;
    private final JButton optionalButton;
    /** 已经点过取消，别重复问 */
    private boolean cancelRequested;
    /** 用户点了「自选模组」，等同步线程到安全点处理 */
    private volatile boolean optionalRequested;
    /** 结束之后不再更新界面 */
    private final AtomicBoolean finished = new AtomicBoolean();
    /** 结束后窗口停留的时间，单位毫秒；0 表示立刻关掉 */
    private final int lingerMillis;

    private ProgressWindow(int lingerMillis) {
        this.lingerMillis = lingerMillis;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 系统外观不可用时用默认外观，不影响功能
        }

        frame = new JFrame(TITLE);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestCancel();
            }
        });
        frame.setResizable(false);
        frame.setAlwaysOnTop(true);

        titleLabel = new JLabel(Text.of(Msg.SYNCING_SERVER_INSTANCE));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        stageLabel = new JLabel(Text.of(Msg.PREPARING));
        detailLabel = new JLabel(" ");
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 11f));

        bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setValue(0);
        bar.setPreferredSize(new Dimension(420, 20));

        JPanel head = new JPanel(new GridLayout(2, 1, 0, 2));
        head.add(titleLabel);
        head.add(stageLabel);

        cancelButton = new JButton(Text.of(Msg.CANCEL_UPDATE));
        cancelButton.addActionListener(event -> requestCancel());
        // 自选模组入口常驻进度窗；清单就绪前没有数据可管，先隐藏
        optionalButton = new JButton(Text.of(Msg.OPTIONAL_MODS_BUTTON));
        optionalButton.setVisible(false);
        optionalButton.addActionListener(event -> requestOptionalManage());
        JPanel south = new JPanel(new BorderLayout());
        south.add(detailLabel, BorderLayout.WEST);
        south.add(optionalButton, BorderLayout.CENTER);
        south.add(cancelButton, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(head, BorderLayout.NORTH);
        root.add(bar, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * 打开进度窗口
     *
     * - `enabled`: 为假或当前环境没有图形界面时返回 {@link Progress#NONE}
     * - `lingerMillis`: 结束后窗口停留多久再关闭；游戏模式下传 0，别拖慢启动
     *
     * # 返回值
     *
     * 窗口实例；没有窗口时是空实现
     */
    static Progress open(boolean enabled, int lingerMillis) {
        if (!enabled || GraphicsEnvironment.isHeadless()) {
            return Progress.NONE;
        }
        try {
            ProgressWindow[] holder = new ProgressWindow[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = new ProgressWindow(lingerMillis));
            return holder[0];
        } catch (Throwable e) {
            return Progress.NONE;
        }
    }

    /**
     * 窗口本体，给模态弹窗当父窗口
     *
     * # 返回值
     *
     * 没有窗口时返回 null
     */
    JFrame frame() {
        return frame;
    }

    /** 是否已经走到过 finish；引导方据此判断还要不要兜底关窗 */
    boolean finished() {
        return finished.get();
    }

    /** 直接关窗，不改文案：--help 与参数错误这类没有结果的路径用 */
    void close() {
        finished.set(true);
        dispose();
    }

    /** 确保窗口已关闭并标记结束；已经关掉时再调一次没有副作用 */
    void ensureDisposed() {
        finished.set(true);
        dispose();
    }

    /** 显示「自选模组」按钮，等用户点 */
    void showOptionalModsButton() {
        update(() -> optionalButton.setVisible(true));
    }

    /** 收起「自选模组」按钮：下载计划定稿后不再接受改动 */
    void hideOptionalModsButton() {
        optionalRequested = false;
        update(() -> {
            optionalButton.setVisible(false);
            optionalButton.setEnabled(true);
        });
    }

    /** 处理完一次管理请求后恢复按钮，用户还可以在下载开始前再改一次 */
    void rearmOptionalModsButton() {
        update(() -> optionalButton.setEnabled(true));
    }

    /**
     * 用户是否点了「自选模组」；取走即清零，按钮保持置灰到处理完
     *
     * # 返回值
     *
     * 有未处理的请求时返回 true
     */
    boolean consumeOptionalRequest() {
        if (!optionalRequested) {
            return false;
        }
        optionalRequested = false;
        return true;
    }

    /** 用户点了「自选模组」：记下请求，由同步线程在安全点弹窗 */
    private void requestOptionalManage() {
        if (finished.get() || optionalRequested) {
            return;
        }
        optionalRequested = true;
        optionalButton.setEnabled(false);
    }

    @Override
    public void title(String text) {
        update(() -> titleLabel.setText(text == null || text.isEmpty() ? Text.of(Msg.SYNCING_SERVER_INSTANCE) : text));
    }

    @Override
    public void stage(String text) {
        update(() -> stageLabel.setText(text == null ? "" : text));
    }

    @Override
    public void total(int total) {
        update(() -> {
            if (total <= 0) {
                bar.setIndeterminate(true);
                return;
            }
            bar.setIndeterminate(false);
            bar.setMinimum(0);
            bar.setMaximum(total);
            bar.setValue(0);
            // 不设自定义字符串：让进度条自己按百分比画，step 更新数值时才会跟着变
            bar.setString(null);
        });
    }

    @Override
    public void step(int done, String detail) {
        update(() -> {
            if (!bar.isIndeterminate()) {
                bar.setValue(Math.max(0, Math.min(done, bar.getMaximum())));
            }
            detailLabel.setText(detail == null || detail.isEmpty() ? " " : detail);
        });
    }

    @Override
    public void finish(boolean ok, String message) {
        if (finished.getAndSet(true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!frame.isDisplayable()) {
                return;
            }
            cancelButton.setEnabled(false);
            optionalButton.setEnabled(false);
            bar.setIndeterminate(false);
            bar.setValue(bar.getMaximum());
            bar.setString(ok ? Text.of(Msg.DONE) : Text.of(Msg.FAILED));
            stageLabel.setText(message == null || message.isEmpty() ? (ok ? Text.of(Msg.SYNC_COMPLETE) : Text.of(Msg.SYNC_FAILED)) : message);
            if (!ok) {
                detailLabel.setForeground(new Color(0xB0, 0x30, 0x30));
            }
        });
        System.clearProperty(CANCEL_KEY);
        if (lingerMillis > 0) {
            Thread closer = new Thread(() -> {
                try {
                    Thread.sleep(lingerMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                dispose();
            }, "mml-server-sync-progress");
            closer.setDaemon(true);
            closer.start();
        } else {
            dispose();
        }
    }

    /** 用户点了取消或关窗：先问一句，再决定停不停 */
    private void requestCancel() {
        if (finished.get() || cancelRequested) {
            return;
        }
        CancelPrompt.Choice choice = CancelPrompt.ask(frame);
        if (choice == CancelPrompt.Choice.CONTINUE) {
            return;
        }
        cancelRequested = true;
        cancelButton.setEnabled(false);
        optionalButton.setEnabled(false);
        stageLabel.setText(Text.of(Msg.CANCELLING));
        System.setProperty(CANCEL_KEY, choice == CancelPrompt.Choice.SKIP ? "skip" : "end");
        // 主线程可能还阻塞在网络读取上，轮询到取消状态要等下一 MB 边界：
        // 给它 2 秒自行收尾，超时就不等了，直接关窗
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (!finished.getAndSet(true)) {
                dispose();
            }
        }, "mml-server-sync-cancel-closer");
        closer.setDaemon(true);
        closer.start();
    }

    private void dispose() {
        try {
            SwingUtilities.invokeLater(frame::dispose);
        } catch (Throwable ignored) {
        }
    }

    /** 更新界面；已结束或窗口已关掉时忽略 */
    private void update(Runnable action) {
        if (finished.get()) {
            return;
        }
        try {
            SwingUtilities.invokeLater(() -> {
                if (frame.isDisplayable()) {
                    action.run();
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
