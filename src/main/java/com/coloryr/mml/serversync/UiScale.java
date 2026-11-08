package com.coloryr.mml.serversync;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;

/**
 * 界面缩放：安卓启动器（FCL / Pojav / Zalith）下把 Swing 窗口整体放大到可读尺寸。
 *
 * 这些启动器把 AWT 叠在游戏窗口上：Swing 按物理像素绘制、字体从 96 DPI 的 12px 起步，
 * 手机上不到 1mm 高。游戏内界面不小是因为 MC 自己按 guiScale 放大了，所以这里用
 * 固定倍数统一放大 UIManager 字体与界面里的硬编码尺寸，桌面端保持 1.0，
 * 把 HiDPI 交给 JVM 自己处理，避免叠两层缩放。
 *
 * 倍数优先级：{@code --ui-scale=} &gt; {@code -Dmml.serverSync.uiScale=} &gt;
 * {@code MML_SERVER_SYNC_UI_SCALE} &gt; 自动固定值。自动值还会受「不超屏」封顶约束；
 * 显式指定则按玩家给的值来，窗口本身由 {@link #fit} 兜底裁到屏幕内。
 */
final class UiScale {
    /** 移动启动器下的固定倍数：按常见的 2400x1080 横屏估的 */
    static final double MOBILE_SCALE = 2.5;
    /** 最大窗口（自选模组弹窗）的基准尺寸，含边距与页眉页脚，用来算不超屏倍数 */
    private static final int BASE_WIDTH = 808;
    private static final int BASE_HEIGHT = 460;
    private static final double MIN_SCALE = 1.0;
    private static final double MAX_SCALE = 4.0;

    /** 0 表示还没算过 */
    private static volatile double cached;
    private static volatile Double explicit;
    private static volatile String source = "";
    /**
     * 自更新包由独立类加载器装载，本类会有两份副本。倍数与「字体已放大」必须走
     * JVM 系统属性共享，否则两个副本各乘一次，或者基包窗口与更新包窗口倍数不一致。
     */
    private static final String RESOLVED_KEY = "mml.serverSync.uiScaleResolved";
    private static final String APPLIED_KEY = "mml.serverSync.uiScaleApplied";

    private UiScale() {}

    /**
     * Runner 解析 {@code --ui-scale=} 时调用，优先级最高
     *
     * - `value`: 玩家指定的倍数
     */
    static synchronized void override(double value) {
        explicit = clamp(value);
        cached = 0;
        System.clearProperty(RESOLVED_KEY);
    }

    /** 本次运行生效的缩放倍数 */
    static double scale() {
        double value = cached;
        if (value != 0) {
            return value;
        }
        // 同一个 JVM 里另一个类加载器已经算过：直接沿用，避免两份代码算出不同倍数
        Double shared = parse(System.getProperty(RESOLVED_KEY, ""));
        if (shared != null) {
            value = clamp(shared);
            source = "共享（跨类加载器）";
            cached = value;
            return value;
        }
        double requested;
        boolean manual;
        Double given = explicit;
        if (given != null) {
            requested = given;
            source = "--ui-scale";
            manual = true;
        } else {
            String property = System.getProperty("mml.serverSync.uiScale", "").trim();
            String env = System.getenv("MML_SERVER_SYNC_UI_SCALE");
            String raw = !property.isEmpty() ? property : (env == null ? "" : env.trim());
            Double parsed = raw.isEmpty() ? null : parse(raw);
            if (parsed != null) {
                requested = clamp(parsed);
                source = property.isEmpty() ? "MML_SERVER_SYNC_UI_SCALE" : "-Dmml.serverSync.uiScale";
                manual = true;
            } else {
                requested = Agent.isAndroidLauncher() ? MOBILE_SCALE : MIN_SCALE;
                source = Agent.isAndroidLauncher() ? "移动启动器固定值" : "桌面默认";
                manual = false;
            }
        }
        if (!manual) {
            // AWT 自己已经做了 HiDPI 变换时除掉这一层，别把窗口放大两次
            double awt = awtTransform();
            if (requested > MIN_SCALE && awt > 1.05) {
                requested = Math.max(MIN_SCALE, requested / awt);
            }
            // 竖屏或低分辨率设备：窗口不能比屏幕还大，按最大窗口的基准尺寸收紧
            requested = Math.min(requested, fitCap());
        }
        value = clamp(requested);
        cached = value;
        System.setProperty(RESOLVED_KEY, String.format(Locale.ROOT, "%.4f", value));
        return value;
    }

    /** 把基准像素换成本次运行的实际像素 */
    static int px(int value) {
        return (int) Math.round(value * scale());
    }

    /** 把基准字号换成本次运行的实际字号 */
    static float font(float size) {
        return (float) (size * scale());
    }

    /** 把基准宽高换成本次运行的实际尺寸 */
    static Dimension size(int width, int height) {
        return new Dimension(px(width), px(height));
    }

    /** 统一缩放的空白边框 */
    static Border emptyBorder(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(px(top), px(left), px(bottom), px(right));
    }

    /**
     * 放大 Swing 的全局字体，必须在 {@code UIManager.setLookAndFeel} 之后、
     * 创建任何组件之前调用；重复调用只生效一次。
     */
    static synchronized void apply() {
        if ("true".equalsIgnoreCase(System.getProperty(APPLIED_KEY, ""))) {
            return;
        }
        System.setProperty(APPLIED_KEY, "true");
        double value = scale();
        if (value <= 1.0001) {
            return;
        }
        // 先收齐键再写：边遍历 UIDefaults 边 put 会触发并发修改
        List<Object> keys = new ArrayList<>();
        Enumeration<Object> enumeration = UIManager.getDefaults().keys();
        while (enumeration.hasMoreElements()) {
            keys.add(enumeration.nextElement());
        }
        for (Object key : keys) {
            Object current = UIManager.get(key);
            if (!(current instanceof Font)) {
                continue;
            }
            Font font = (Font) current;
            Font scaled = font.deriveFont((float) (font.getSize2D() * value));
            UIManager.put(key, scaled instanceof FontUIResource
                    ? scaled : new FontUIResource(scaled));
        }
    }

    /** pack 之后把窗口裁进屏幕，表格等可滚动区域自己出滚动条 */
    static void fit(Window window) {
        try {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int maxWidth = (int) (screen.width * 0.92);
            int maxHeight = (int) (screen.height * 0.88);
            Dimension current = window.getSize();
            int width = Math.min(current.width, maxWidth);
            int height = Math.min(current.height, maxHeight);
            if (width != current.width || height != current.height) {
                window.setSize(width, height);
            }
        } catch (Throwable ignored) {
            // 拿不到屏幕尺寸时按 pack 的结果显示
        }
    }

    /** 把运行环境与最终倍数写进日志，方便在真机上校准常数 */
    static void log() {
        String screen;
        String dpi;
        try {
            Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
            screen = size.width + "x" + size.height;
            dpi = String.valueOf(Toolkit.getDefaultToolkit().getScreenResolution());
        } catch (Throwable e) {
            screen = "?";
            dpi = "?";
        }
        Log.info(Text.of(Msg.UI_SCALE_INFO, format(scale()), source, screen, dpi,
                format(awtTransform()), String.valueOf(Agent.isAndroidLauncher())));
    }

    /** 解析显式倍数：非法值返回 null，按自动模式处理 */
    static Double parse(String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 屏幕能容纳的最大倍数；拿不到屏幕信息时不封顶 */
    private static double fitCap() {
        try {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            if (screen.width <= 0 || screen.height <= 0) {
                return MAX_SCALE;
            }
            return Math.min(screen.width * 0.92 / BASE_WIDTH,
                    screen.height * 0.88 / BASE_HEIGHT);
        } catch (Throwable e) {
            return MAX_SCALE;
        }
    }

    /** AWT 报告的设备缩放倍数，1.0 表示没有额外变换 */
    private static double awtTransform() {
        try {
            GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            return Math.max(config.getDefaultTransform().getScaleX(), MIN_SCALE);
        } catch (Throwable e) {
            return MIN_SCALE;
        }
    }
}
