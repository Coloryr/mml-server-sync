package com.coloryr.mml.serversync.update;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 更新包类加载器：更新包内的类优先，host 包交给父加载器。
 */
public final class UpdateClassLoader extends URLClassLoader {
    /** 只由宿主提供、更新包里的同名副本不用的包前缀 */
    private static final String HOST_PACKAGE = "com.coloryr.mml.serversync.host.";
    /** 要从更新包加载的包前缀 */
    private static final String APP_PACKAGE = "com.coloryr.mml.serversync.";

    static {
        registerAsParallelCapable();
    }

    public UpdateClassLoader(URL jar) {
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
