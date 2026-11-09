package com.coloryr.mml.serversync.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.coloryr.mml.serversync.util.Log;
import com.coloryr.mml.serversync.util.Msg;
import com.coloryr.mml.serversync.util.Text;

/** 更新没跑完时的三选一：重试、跳过本次更新、关闭游戏。 */
public final class FailurePrompt {
    /** 三个出路 */
    public enum Choice {
        /** 再跑一次同步 */
        RETRY,
        /** 跳过本次更新，继续启动 */
        SKIP,
        /** 关闭游戏 */
        CLOSE
    }

    private FailurePrompt() {}

    /**
     * 弹出失败提示框
     *
     * @param parent 父窗口，可为 null
     * @param reason 失败原因，直接显示给玩家
     * @return 见 {@link Choice}；弹不出来时按跳过更新处理，与无界面模式的行为一致
     */
    public static Choice ask(Component parent, String reason) {
        if (GraphicsEnvironment.isHeadless() || ProgressWindow.guiUnavailable()) {
            return Choice.SKIP;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return show(parent, reason);
        }
        final Choice[] result = {Choice.SKIP};
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = show(parent, reason));
        } catch (Exception e) {
            Log.error(Text.of(Msg.FAILED_TO_OPEN_THE_FAILURE_DIALOG, e.getMessage()));
        }
        return result[0];
    }

    /**
     * 显示失败提示框。
     *
     * @param parent 父窗口，可为 null
     * @param reason 失败原因
     * @return 玩家选择
     */
    private static Choice show(Component parent, String reason) {
        UiScale.apply();
        final JDialog dialog = new JDialog(UiScale.ownerOf(parent), Text.of(Msg.FAILURE_DIALOG_TITLE), true);
        final Choice[] choice = {Choice.SKIP};
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // 原因可能很长（URL、异常信息），用只读文本区自动换行，窗口不会被撑成一条横线
        JTextArea message = new JTextArea(Text.of(Msg.FAILURE_DIALOG_QUESTION, safe(reason)));
        message.setEditable(false);
        message.setOpaque(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setBorder(UiScale.emptyBorder(0, 0, 14, 0));
        message.setFont(message.getFont().deriveFont(UiScale.font(12f)));
        message.setPreferredSize(new Dimension(UiScale.px(460), message.getPreferredSize().height));

        JButton retry = new JButton(Text.of(Msg.FAILURE_DIALOG_RETRY));
        retry.addActionListener(event -> {
            choice[0] = Choice.RETRY;
            dialog.dispose();
        });
        JButton skip = new JButton(Text.of(Msg.FAILURE_DIALOG_SKIP));
        skip.addActionListener(event -> {
            choice[0] = Choice.SKIP;
            dialog.dispose();
        });
        JButton close = new JButton(Text.of(Msg.FAILURE_DIALOG_CLOSE));
        close.addActionListener(event -> {
            choice[0] = Choice.CLOSE;
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(retry);
        dialog.getRootPane().registerKeyboardAction(event -> {
            choice[0] = Choice.SKIP;
            dialog.dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(8), 0));
        buttons.add(retry);
        buttons.add(skip);
        buttons.add(close);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(UiScale.emptyBorder(16, 18, 14, 18));
        root.add(message, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(dialog.getPreferredSize());
        UiScale.fit(dialog);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return choice[0];
    }

    /**
     * 把异常信息换成玩家能看懂的文字。
     *
     * @param reason 原始原因
     * @return 展示文字
     */
    private static String safe(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return Text.of(Msg.SYNC_FAILED);
        }
        String value = reason.trim();
        // 常见的网络异常给一句人话；原始信息已经写在日志里，玩家不需要看懂 getsockopt
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused") || lower.contains("connectexception")
                || lower.contains("connection reset") || lower.contains("network is unreachable")) {
            return Text.of(Msg.FAILURE_REASON_NO_CONNECTION);
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return Text.of(Msg.FAILURE_REASON_TIMEOUT);
        }
        if (lower.contains("unknownhost") || lower.contains("name or service not known")) {
            return Text.of(Msg.FAILURE_REASON_DNS);
        }
        if (lower.contains("http 5")) {
            return Text.of(Msg.FAILURE_REASON_SERVER_BUSY);
        }
        return value;
    }

}
