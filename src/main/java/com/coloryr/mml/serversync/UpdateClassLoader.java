package com.coloryr.mml.serversync;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 更新包的类加载器：启动器自己的类优先从更新包读，host 包一律交给父加载器。
 *
 * host 包（{@link com.coloryr.mml.serversync.host.Progress}）是宿主与更新包之间唯一共享的
 * 类型，必须解析到同一个 Class 实例，否则反射调用会因为参数类型对不上而失败。
 */
final class UpdateClassLoader extends URLClassLoader {
    /** 只由宿主提供、更新包里的同名副本不用的包前缀 */
    private static final String HOST_PACKAGE = "com.coloryr.mml.serversync.host.";
    /** 要从更新包加载的包前缀 */
    private static final String APP_PACKAGE = "com.coloryr.mml.serversync.";

    static {
        registerAsParallelCapable();
    }

    UpdateClassLoader(URL jar) {
        super(new URL[]{jar}, UpdateClassLoader.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(HOST_PACKAGE)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith(APP_PACKAGE)) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // 更新包里没这个类（例如新加的辅助类被删了）：退回宿主
                        loaded = super.loadClass(name, false);
                    }
                } else {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
