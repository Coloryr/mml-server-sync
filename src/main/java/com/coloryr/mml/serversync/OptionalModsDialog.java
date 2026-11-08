package com.coloryr.mml.serversync;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 自选模组勾选窗：列出全部自选模组，勾选决定这次是否下载/更新。
 *
 * 用表格而不是一列复选框：条目多时要有表头与滚动条，名称 / 介绍 / 状态各占一列才对得齐。
 * 点整行任意位置都能翻勾选状态，空格也可以。关窗、Esc 与「确定」同义：
 * 返回当前勾选结果，没有取消的概念——不改动就按打开时的勾选继续。
 *
 * 底部另有「记住我的选择，以后不再显示」：勾上并确认后由调用方写标记文件，
 * 之后只在自选模组有新增或内容变化时才自动弹窗。初始状态由调用方给出——自动弹窗
 * 在玩家尚未记住时不替他做决定，管理窗则反映当前是否已记住。
 *
 * 只在交互模式下弹：--no-gui、headless 与给了 --skip 的脚本自动弹的这扇窗不出现。
 */
final class OptionalModsDialog {
    /** 表格行高，单位像素 */
    private static final int ROW_HEIGHT = 30;
    /** 隔行底色，浅到不影响文字 */
    private static final Color STRIPE = new Color(0xF5, 0xF6, 0xF8);
    /** 状态列：新增 */
    private static final Color ADDED = new Color(0x2E, 0x7D, 0x32);
    /** 状态列：需更新 */
    private static final Color NEEDS_UPDATE = new Color(0xB2, 0x6A, 0x00);
    /** 介绍列文字 */
    private static final Color DESC = new Color(0x70, 0x76, 0x7D);
    /** 普通文字颜色 */
    private static final Color TEXT = new Color(0x1F, 0x23, 0x28);

    private OptionalModsDialog() {}

    /** 一行：清单路径、文件名、简介、状态、初始勾选 */
    static final class Row {
        /** 清单里的相对路径 */
        final String path;
        /** 文件名 */
        final String name;
        /** 简介文字，发布端没写时为空 */
        final String desc;
        /** 状态文字：新增 / 需更新 / 已同步，可带本地启用状态后缀 */
        final String status;
        /** 初始是否勾选 */
        final boolean checked;

        Row(String path, String name, String desc, String status, boolean checked) {
            this.path = path;
            this.name = name;
            this.desc = desc == null ? "" : desc;
            this.status = status == null ? "" : status;
            this.checked = checked;
        }
    }

    /** 弹窗结果：玩家确认的勾选，外加「以后不再显示」 */
    static final class Result {
        /** 路径 → 是否勾选，覆盖全部展示行 */
        final LinkedHashMap<String, Boolean> selections;
        /** 玩家勾了「记住我的选择，以后不再显示」 */
        final boolean suppressPrompt;

        Result(LinkedHashMap<String, Boolean> selections, boolean suppressPrompt) {
            this.selections = selections;
            this.suppressPrompt = suppressPrompt;
        }
    }

    /**
     * 弹出勾选窗并等待玩家确认；必须在事件线程上调用
     *
     * 调用方自己决定时机：按钮点击直接调，自动弹窗交给后台线程包装。方法阻塞到
     * 窗口关闭为止，返回**所有展示行**的最终勾选状态——没有取消出口，关窗就是确认。
     * 调用方只应把结果并进展示过的行，未展示行的选择保持原状。
     *
     * - `parent`: 父窗口，可为 null
     * - `rows`: 要列出的条目
     * - `header`: 表格上方的说明文字
     * - `rememberChecked`: 「以后不再显示」的初始勾选状态，由调用方按当前是否已记住给出
     *
     * # 返回值
     *
     * 全部展示行的勾选状态与「以后不再显示」的勾选状态
     */
    static Result askForSelection(Component parent, List<Row> rows, String header,
                                  boolean rememberChecked) {
        // 提前解析 Result：更新包的类加载器可能在同步失败后被宿主关闭，
        // 弹窗收尾（或回退）时才加载会 NoClassDefFoundError
        Result.class.getName();
        if (SwingUtilities.isEventDispatchThread()) {
            return show(parent, rows, header, rememberChecked);
        }
        final Result[] result = new Result[1];
        try {
            SwingUtilities.invokeAndWait(
                    () -> result[0] = show(parent, rows, header, rememberChecked));
        } catch (Exception e) {
            // 弹不出来别拦住同步：按打开时的勾选处理，「不再显示」的勾选状态原样保留
            Log.error(Text.of(Msg.FAILED_TO_OPEN_THE_OPTIONAL_MOD_DIALOG) + e.getMessage());
            return fallbackResult(rows, rememberChecked);
        }
        return result[0];
    }

    /** 弹窗打不开时的结果：每行都按初始勾选，标记状态不变 */
    private static Result fallbackResult(List<Row> rows, boolean rememberChecked) {
        LinkedHashMap<String, Boolean> selections = new LinkedHashMap<>();
        for (Row row : rows) {
            selections.put(row.path, row.checked);
        }
        return new Result(selections, rememberChecked);
    }

    private static Result show(Component parent, List<Row> rows, String header,
                               boolean rememberChecked) {
        // 窗口可能由宿主建好外观，这里补一次全局字体（幂等）
        UiScale.apply();
        final JDialog dialog = new JDialog(ownerOf(parent), Text.of(Msg.OPTIONAL_MODS_BUTTON), true);
        dialog.setMinimumSize(UiScale.size(720, 380));
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        final DefaultTableModel model = new DefaultTableModel(
                new Object[]{Text.of(Msg.SELECT_2), Text.of(Msg.MOD), Text.of(Msg.DESC_COLUMN),
                        Text.of(Msg.STATUS_COLUMN)}, 0) {
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
            model.addRow(new Object[]{row.checked, row.name, row.desc, row.status});
        }

        final JTable table = new JTable(model);
        table.setRowHeight(UiScale.px(ROW_HEIGHT));
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xE8, 0xEA, 0xED));
        table.setIntercellSpacing(UiScale.size(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(UiScale.size(0, 30));
        table.setDefaultRenderer(Boolean.class, new CheckRenderer());
        table.setDefaultRenderer(String.class, new TextRenderer());
        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(0).setMinWidth(UiScale.px(52));
        columns.getColumn(0).setMaxWidth(UiScale.px(52));
        columns.getColumn(0).setPreferredWidth(UiScale.px(52));
        columns.getColumn(1).setPreferredWidth(UiScale.px(210));
        columns.getColumn(2).setPreferredWidth(UiScale.px(320));
        columns.getColumn(3).setPreferredWidth(UiScale.px(170));

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
        scroll.setPreferredSize(UiScale.size(780, 360));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xDD, 0xE0, 0xE4)));
        scroll.getViewport().setBackground(table.getBackground());

        final JCheckBox all = new JCheckBox(Text.of(Msg.SELECT_ALL), true);
        final JLabel count = new JLabel();
        // 勾上只表示「以后不要自动弹」，不影响这次的勾选内容
        final JCheckBox never = new JCheckBox(Text.of(Msg.NEVER_SHOW_AGAIN), rememberChecked);
        // 按钮文字固定：带已选数量的文字会随勾选变化，按钮宽度与位置跟着左右跳
        final JButton ok = new JButton(Text.of(Msg.CONFIRM_SELECTION));

        Runnable refresh = () -> {
            int checked = 0;
            for (int i = 0; i < model.getRowCount(); i++) {
                if (Boolean.TRUE.equals(model.getValueAt(i, 0))) {
                    checked++;
                }
            }
            count.setText(Text.of(Msg.SELECTED_COUNT, checked, model.getRowCount()));
            // 全选勾选框反映实际状态：取消任意一行后它自动落回未勾选
            all.setSelected(checked > 0 && checked == model.getRowCount());
        };
        model.addTableModelListener(event -> refresh.run());
        // 「全选」始终是勾选全部：按切换语义点一下会把全部清空，与直觉相反
        all.addActionListener(event -> {
            for (int i = 0; i < model.getRowCount(); i++) {
                model.setValueAt(Boolean.TRUE, i, 0);
            }
            refresh.run();
        });

        dialog.getRootPane().setDefaultButton(ok);
        // 关窗与 Esc 同「确定」：收集当前勾选后正常返回
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                dialog.dispose();
            }
        });
        ok.addActionListener(event -> dialog.dispose());

        JLabel title = new JLabel(header);
        title.setFont(title.getFont().deriveFont(Font.BOLD, UiScale.font(14f)));
        JLabel hint = new JLabel(Text.of(Msg.HINT_UNSELECTED_DISABLED));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, UiScale.font(11f)));
        hint.setForeground(new Color(0x70, 0x76, 0x7D));
        JPanel head = new JPanel(new BorderLayout(0, UiScale.px(4)));
        head.add(title, BorderLayout.NORTH);
        head.add(hint, BorderLayout.SOUTH);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(10), 0));
        left.add(count);
        left.add(all);
        left.add(never);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(8), 0));
        right.add(ok);
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, UiScale.px(10)));
        root.setBorder(UiScale.emptyBorder(14, 14, 14, 14));
        root.add(head, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        refresh.run();
        dialog.setContentPane(root);
        dialog.pack();
        UiScale.fit(dialog);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);

        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            out.put(rows.get(i).path, Boolean.TRUE.equals(model.getValueAt(i, 0)));
        }
        return new Result(out, never.isSelected());
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

    private static Color rowBackground(JTable table, boolean selected, int row) {
        if (selected) {
            return table.getSelectionBackground();
        }
        return row % 2 == 0 ? table.getBackground() : STRIPE;
    }

    /** 勾选列的复选框渲染：居中、无内边距（默认 margin 会左右不对称）、跟着隔行底色 */
    private static final class CheckRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final JCheckBox box = new JCheckBox();

        CheckRenderer() {
            box.setHorizontalAlignment(SwingConstants.CENTER);
            box.setMargin(new java.awt.Insets(0, 0, 0, 0));
            box.setBorderPainted(false);
            box.setFocusPainted(false);
            box.setIconTextGap(0);
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

    /** 文字列：留边距，介绍列灰色，状态列按新增 / 需更新上色 */
    private static final class TextRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            int index = table.convertColumnIndexToModel(column);
            setBorder(UiScale.emptyBorder(0, 10, 0, 10));
            setBackground(rowBackground(table, selected, row));
            if (!selected) {
                setForeground(foregroundOf(index, value));
            }
            return this;
        }
    }

    private static Color foregroundOf(int column, Object value) {
        String text = value == null ? "" : value.toString();
        if (column == 2) {
            return DESC;
        }
        if (column == 3) {
            if (text.startsWith(Text.of(Msg.ADDED))) {
                return ADDED;
            }
            if (text.startsWith(Text.of(Msg.STATUS_NEEDS_UPDATE))) {
                return NEEDS_UPDATE;
            }
        }
        return TEXT;
    }
}
