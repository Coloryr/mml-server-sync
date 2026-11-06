package com.coloryr.mml.serversync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 可选模组勾选窗：列出本次会变的那几条，默认全选。
 *
 * 只在交互模式下弹：--no-gui、Agent、headless，以及给了 --skip 的脚本都不走这里。
 */
final class OptionalModsDialog {
    private OptionalModsDialog() {}

    /** 一行：清单路径、文件名、本地状态 */
    static final class Row {
        /** 清单里的相对路径 */
        final String path;
        /** 文件名 */
        final String name;
        /** 旧清单里没有同一身份，属于新增 */
        final boolean added;
        /** 本地这份是否启用着 */
        final boolean enabled;
        /** 本地旧文件名，与 name 不同才有值 */
        final String oldName;

        Row(String path, String name, boolean added, boolean enabled, String oldName) {
            this.path = path;
            this.name = name;
            this.added = added;
            this.enabled = enabled;
            this.oldName = oldName == null ? "" : oldName;
        }
    }

    /**
     * 弹出勾选窗并等待用户确认
     *
     * - `parent`: 父窗口，可为 null
     * - `rows`: 要列出的条目
     *
     * # 返回值
     *
     * 未勾选的路径集合；用户点「取消同步」时返回 null
     */
    @SuppressWarnings("unchecked")
    static Set<String> ask(Component parent, List<Row> rows) {
        if (SwingUtilities.isEventDispatchThread()) {
            return show(parent, rows);
        }
        final Set<String>[] result = new Set[1];
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = show(parent, rows));
        } catch (Exception e) {
            // 弹不出来别拦住同步：按全部更新处理
            Log.error("可选模组勾选窗打开失败: " + e.getMessage());
            return new LinkedHashSet<>();
        }
        return result[0];
    }

    private static Set<String> show(Component parent, List<Row> rows) {
        Frame owner = null;
        if (parent instanceof Frame) {
            owner = (Frame) parent;
        } else if (parent != null) {
            java.awt.Window window = SwingUtilities.getWindowAncestor(parent);
            if (window instanceof Frame) {
                owner = (Frame) window;
            }
        }

        final JDialog dialog = new JDialog(owner, "可选模组更新", true);
        final List<JCheckBox> boxes = new ArrayList<>();
        final Set<String> unchecked = new LinkedHashSet<>();
        final boolean[] cancelled = {false};

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (Row row : rows) {
            list.add(rowPanel(row, boxes));
        }

        final JCheckBox all = new JCheckBox("全选", true);
        final JLabel count = new JLabel();
        Runnable refresh = () -> {
            int checked = 0;
            for (JCheckBox box : boxes) {
                if (box.isSelected()) {
                    checked++;
                }
            }
            count.setText("已选 " + checked + " / " + boxes.size());
        };
        all.addActionListener(e -> {
            for (JCheckBox box : boxes) {
                box.setSelected(all.isSelected());
            }
            refresh.run();
        });
        for (JCheckBox box : boxes) {
            box.addActionListener(e -> refresh.run());
        }

        JButton cancel = new JButton("取消同步");
        cancel.addActionListener(e -> {
            cancelled[0] = true;
            dialog.dispose();
        });
        JButton ok = new JButton("更新所选");
        ok.addActionListener(e -> {
            for (int i = 0; i < boxes.size(); i++) {
                if (!boxes.get(i).isSelected()) {
                    unchecked.add(rows.get(i).path);
                }
            }
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> {
            cancelled[0] = true;
            dialog.dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel headRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headRight.add(count);
        headRight.add(all);
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.add(new JLabel("以下可选模组本次会更新，取消勾选的保持本地旧版本："), BorderLayout.NORTH);
        head.add(headRight, BorderLayout.SOUTH);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 360));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(ok);

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(head, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        refresh.run();
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return cancelled[0] ? null : unchecked;
    }

    private static JPanel rowPanel(Row row, List<JCheckBox> boxes) {
        JCheckBox box = new JCheckBox(row.name, true);
        boxes.add(box);
        JLabel detail = new JLabel(row.path + "    " + stateText(row));
        detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11f));
        detail.setForeground(new Color(0x66, 0x66, 0x66));
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(box, BorderLayout.NORTH);
        panel.add(detail, BorderLayout.SOUTH);
        return panel;
    }

    private static String stateText(Row row) {
        if (row.added) {
            return "新增";
        }
        StringBuilder out = new StringBuilder(row.enabled ? "已启用" : "已禁用");
        if (!row.oldName.isEmpty()) {
            out.append("，本地为 ").append(row.oldName);
        }
        return out.toString();
    }
}
