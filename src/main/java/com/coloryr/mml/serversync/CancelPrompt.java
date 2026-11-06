package com.coloryr.mml.serversync;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 更新过程中点取消后的确认框：跳过本次更新，还是结束游戏。
 *
 * 关窗或按 Esc 等于「继续更新」：取消是手滑时还能接着跑，不会一按就停。
 */
final class CancelPrompt {
    /** 三个出路 */
    enum Choice {
        /** 继续更新 */
        CONTINUE,
        /** 跳过更新，继续启动 */
        SKIP,
        /** 结束游戏 */
        END
    }

    private CancelPrompt() {}

    /**
     * 弹出确认框
     *
     * - `parent`: 父窗口，可为 null
     *
     * # 返回值
     *
     * 见 {@link Choice}；弹不出来时按继续更新处理
     */
    @SuppressWarnings("unchecked")
    static Choice ask(Component parent) {
        if (SwingUtilities.isEventDispatchThread()) {
            return show(parent);
        }
        final Choice[] result = {Choice.CONTINUE};
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = show(parent));
        } catch (Exception e) {
            Log.error(Text.of(Msg.FAILED_TO_OPEN_THE_CANCEL_DIALOG, e.getMessage()));
        }
        return result[0];
    }

    private static Choice show(Component parent) {
        final JDialog dialog = new JDialog(ownerOf(parent), Text.of(Msg.CANCEL_DIALOG_TITLE), true);
        final Choice[] choice = {Choice.CONTINUE};

        JLabel question = new JLabel(Text.of(Msg.CANCEL_DIALOG_QUESTION));
        question.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));

        JButton skip = new JButton(Text.of(Msg.CANCEL_DIALOG_SKIP));
        skip.addActionListener(event -> {
            choice[0] = Choice.SKIP;
            dialog.dispose();
        });
        JButton end = new JButton(Text.of(Msg.CANCEL_AFTER_END));
        end.addActionListener(event -> {
            choice[0] = Choice.END;
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(skip);
        dialog.getRootPane().registerKeyboardAction(event -> {
            choice[0] = Choice.CONTINUE;
            dialog.dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(end);
        buttons.add(skip);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        root.add(question, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(dialog.getPreferredSize());
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return choice[0];
    }

    private static Frame ownerOf(Component parent) {
        if (parent instanceof Frame) {
            return (Frame) parent;
        }
        if (parent != null) {
            java.awt.Window window = SwingUtilities.getWindowAncestor(parent);
            if (window instanceof Frame) {
                return (Frame) window;
            }
        }
        return null;
    }
}
