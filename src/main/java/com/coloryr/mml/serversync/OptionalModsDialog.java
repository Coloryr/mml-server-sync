package com.coloryr.mml.serversync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

/**
 * 可选模组勾选窗：列出本次会变的那几条，默认全选。
 *
 * 用表格而不是一列复选框：条目多时要有表头与滚动条，名称 / 路径 / 本地状态各占一列才对得齐。
 * 点整行任意位置都能翻勾选状态，空格也可以。
 *
 * 只在交互模式下弹：--no-gui、Agent、headless，以及给了 --skip 的脚本都不走这里。
 */
final class OptionalModsDialog {
    /** 表格行高，单位像素 */
    private static final int ROW_HEIGHT = 32;
    /** 隔行底色，浅到不影响文字 */
    private static final Color STRIPE = new Color(0xF5, 0xF6, 0xF8);
    /** 状态列：新增 */
    private static final Color ADDED = new Color(0x2E, 0x7D, 0x32);
    /** 状态列：已禁用 */
    private static final Color DISABLED = new Color(0x8A, 0x8A, 0x8A);
    /** 普通文字颜色 */
    private static final Color TEXT = new Color(0x1F, 0x23, 0x28);

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
        final JDialog dialog = new JDialog(ownerOf(parent), "可选模组更新", true);
        dialog.setMinimumSize(new Dimension(680, 360));

        final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"更新", "模组", "路径", "本地状态"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                // 勾选由鼠标 / 空格自己翻：交给编辑器会和监听器各翻一次
                return false;
            }
        };
        for (Row row : rows) {
            model.addRow(new Object[]{Boolean.TRUE, row.name, row.path, stateText(row)});
        }

        final JTable table = new JTable(model);
        table.setRowHeight(ROW_HEIGHT);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xE8, 0xEA, 0xED));
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, 32));
        table.setDefaultRenderer(Boolean.class, new CheckRenderer());
        table.setDefaultRenderer(String.class, new TextRenderer());
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(0).setMinWidth(56);
        columns.getColumn(0).setMaxWidth(56);
        columns.getColumn(0).setPreferredWidth(56);
        columns.getColumn(1).setPreferredWidth(200);
        columns.getColumn(2).setPreferredWidth(330);
        columns.getColumn(3).setPreferredWidth(180);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0) {
                    toggle(model, row);
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle-row");
        table.getActionMap().put("toggle-row", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    toggle(model, row);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(780, 380));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xDD, 0xE0, 0xE4)));
        scroll.getViewport().setBackground(table.getBackground());

        final JCheckBox all = new JCheckBox("全选", true);
        final JLabel count = new JLabel();
        final JButton cancel = new JButton("取消同步");
        final JButton ok = new JButton("更新所选");
        final Set<String> unchecked = new LinkedHashSet<>();
        final boolean[] cancelled = {false};

        Runnable refresh = () -> {
            int checked = 0;
            for (int i = 0; i < model.getRowCount(); i++) {
                if (Boolean.TRUE.equals(model.getValueAt(i, 0))) {
                    checked++;
                }
            }
            count.setText("已选 " + checked + " / " + model.getRowCount());
            ok.setText("更新所选（" + checked + "）");
            all.setSelected(checked == model.getRowCount());
        };
        model.addTableModelListener(event -> refresh.run());
        all.addActionListener(event -> {
            for (int i = 0; i < model.getRowCount(); i++) {
                model.setValueAt(all.isSelected(), i, 0);
            }
            refresh.run();
        });

        cancel.addActionListener(event -> {
            cancelled[0] = true;
            dialog.dispose();
        });
        ok.addActionListener(event -> {
            for (int i = 0; i < rows.size(); i++) {
                if (!Boolean.TRUE.equals(model.getValueAt(i, 0))) {
                    unchecked.add(rows.get(i).path);
                }
            }
            dialog.dispose();
        });
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(event -> {
            cancelled[0] = true;
            dialog.dispose();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JLabel title = new JLabel("本次有 " + rows.size() + " 个可选模组会更新");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        JLabel hint = new JLabel("取消勾选的保持本地旧版本；发布方不换内容就不再询问。");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(0x70, 0x76, 0x7D));
        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.add(title, BorderLayout.NORTH);
        head.add(hint, BorderLayout.SOUTH);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.add(count);
        left.add(all);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(cancel);
        right.add(ok);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(head, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        refresh.run();
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return cancelled[0] ? null : unchecked;
    }

    private static void toggle(DefaultTableModel model, int row) {
        model.setValueAt(!Boolean.TRUE.equals(model.getValueAt(row, 0)), row, 0);
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

    private static Color rowBackground(JTable table, boolean selected, int row) {
        if (selected) {
            return table.getSelectionBackground();
        }
        return row % 2 == 0 ? table.getBackground() : STRIPE;
    }

    /** 勾选列的复选框渲染：居中、跟着隔行底色 */
    private static final class CheckRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final JCheckBox box = new JCheckBox();

        CheckRenderer() {
            box.setHorizontalAlignment(SwingConstants.CENTER);
            box.setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            box.setSelected(Boolean.TRUE.equals(value));
            box.setBackground(rowBackground(table, selected, row));
            return box;
        }
    }

    /** 文字列：留边距，状态列按启用 / 禁用 / 新增上色 */
    private static final class TextRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            int index = table.convertColumnIndexToModel(column);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setBackground(rowBackground(table, selected, row));
            if (!selected) {
                setForeground(foregroundOf(index, value));
            }
            return this;
        }
    }

    private static Color foregroundOf(int column, Object value) {
        String text = value == null ? "" : value.toString();
        if (column == 3) {
            if (text.startsWith("新增")) {
                return ADDED;
            }
            if (text.startsWith("已禁用")) {
                return DISABLED;
            }
        }
        return TEXT;
    }
}
