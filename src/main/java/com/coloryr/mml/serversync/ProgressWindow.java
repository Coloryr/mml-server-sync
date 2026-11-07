package com.coloryr.mml.serversync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.coloryr.mml.serversync.host.Progress;

/**
 * 同步进度窗口。
 *
 * 同步经常跑在游戏进程的 premain 里：窗口要在游戏启动前出现、同步一结束就消失。
 * 除了画阶段与步骤，还带一个「取消更新」：点下去先暂停同步，再问一句继续更新、
 * 跳过本次更新还是结束游戏；真正的停止由主流程在安全点检查系统属性后执行。
 *
 * 所有更新方法都可以从任意线程调用，内部统一排到 Swing 事件线程。
 */
final class ProgressWindow implements Progress {
    /** 窗口标题 */
    private static final String TITLE = Text.of(Msg.MML_SERVER_INSTANCE_SYNC);

    /** 取消状态写在系统属性里，主流程按它决定停不停；值：skip / end */
    static final String CANCEL_KEY = "mml.serverSync.cancel";
    /** GUI 初始化失败过一次的标记：用系统属性，跨类加载器也能读到 */
    static final String GUI_FAILED_KEY = "mml.serverSync.guiFailed";

    /** 之前建窗失败过（例如没有显示服务器）：后续别再尝试弹窗 */
    static boolean guiUnavailable() {
        return "true".equalsIgnoreCase(System.getProperty(GUI_FAILED_KEY, ""));
    }

    private final JFrame frame;
    private final JLabel titleLabel;
    private final JLabel stageLabel;
    private final JLabel detailLabel;
    private final JProgressBar bar;
    private final JButton cancelButton;
    private final JButton optionalButton;
    /** 已经点过取消，别重复问 */
    private boolean cancelRequested;
    /** 玩家已选「跳过 / 结束」：进度停在原地，不再跟着后台收尾往前走 */
    private volatile boolean cancelling;
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
        // 自选模组入口常驻进度窗；清单就绪前没有数据可管，先置灰
        optionalButton = new JButton(Text.of(Msg.OPTIONAL_MODS_BUTTON));
        optionalButton.setEnabled(false);
        // 两个按钮取同一个尺寸：不同语言下文字长短不同，各按自身文字算宽度会一大一小
        Dimension optionalSize = optionalButton.getPreferredSize();
        Dimension cancelSize = cancelButton.getPreferredSize();
        Dimension buttonSize = new Dimension(Math.max(optionalSize.width, cancelSize.width),
                Math.max(optionalSize.height, cancelSize.height));
        optionalButton.setPreferredSize(buttonSize);
        cancelButton.setPreferredSize(buttonSize);
        // 文件名自己占一行：长路径不会再把「自选模组」按钮挤来挤去；按钮单独一行靠右
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(optionalButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(detailLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

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
        if (!enabled || guiUnavailable()) {
            return Progress.NONE;
        }
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return Progress.NONE;
            }
            ProgressWindow[] holder = new ProgressWindow[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = new ProgressWindow(lingerMillis));
            return holder[0];
        } catch (Throwable e) {
            // 没有可用的图形环境（X11 初始化失败、cacio 未就绪等）：记住，之后别再试。
            // 这里只写控制台：Log 要到 Runner 里才打开。
            System.setProperty(GUI_FAILED_KEY, "true");
            String message = e.getMessage();
            System.out.println(Log.PREFIX + Text.of(Msg.GUI_UNAVAILABLE_HEADLESS,
                    message == null || message.trim().isEmpty()
                            ? e.getClass().getSimpleName() : message));
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
            String text = detail == null || detail.isEmpty() ? " " : detail;
            detailLabel.setText(text);
            // 路径太长会被截断，悬停时给出完整名字
            detailLabel.setToolTipText(text.trim().isEmpty() ? null : text);
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
            // 取消时进度条就停在原处，别跳到 100% 让人以为还在往前走
            if (!cancelling) {
                bar.setIndeterminate(false);
                bar.setValue(bar.getMaximum());
            }
            bar.setString(cancelling ? Text.of(Msg.CANCELLED)
                    : ok ? Text.of(Msg.DONE) : Text.of(Msg.FAILED));
            stageLabel.setText(message == null || message.isEmpty() ? (ok ? Text.of(Msg.SYNC_COMPLETE) : Text.of(Msg.SYNC_FAILED)) : message);
            if (!ok && !cancelling) {
                detailLabel.setForeground(new Color(0xB0, 0x30, 0x30));
            }
        });
        // 只解除暂停：取消状态要留到后台线程全部自己停下，别让它们看到"没取消"继续写文件
        Cancels.resume();
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

    /** 用户点了取消或关窗：先暂停，再问一句 */
    private void requestCancel() {
        if (finished.get() || cancelRequested) {
            return;
        }
        // 先暂停：点下去的瞬间下载线程就在下一个数据块停住，弹窗期间不再继续下载
        String previousStage = stageLabel.getText();
        Cancels.pause();
        stageLabel.setText(Text.of(Msg.UPDATE_PAUSED_WAITING));
        CancelPrompt.Choice choice = CancelPrompt.ask(frame);
        if (choice == CancelPrompt.Choice.CONTINUE) {
            Cancels.resume();
            stageLabel.setText(previousStage == null ? " " : previousStage);
            return;
        }
        cancelRequested = true;
        cancelling = true;
        cancelButton.setEnabled(false);
        optionalButton.setEnabled(false);
        stageLabel.setText(Text.of(Msg.CANCELLING));
        // 先写取消状态再解除暂停：暂停中的线程醒来立刻就能看到
        System.setProperty(CANCEL_KEY, choice == CancelPrompt.Choice.SKIP ? "skip" : "end");
        Cancels.resume();
        // 后台线程会在下一个数据块看到取消并停下；给它 2 秒自行收尾，超时就不等了，直接关窗
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

    /** 更新界面；已结束、已取消或窗口已关掉时忽略，进度条不会继续往前爬 */
    private void update(Runnable action) {
        if (finished.get() || cancelling) {
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
