package com.fs.starfarer.loading.scripts;

import com.github.alphardpaarthurnax.mixinlib.ModClassLoader;

public class B extends ClassLoader {
    public B(ClassLoader var1) {
        super(ModClassLoader.getInstance());
    }
    public Class<?> loadClass(String var1, boolean var2) throws ClassNotFoundException {
        return super.loadClass(var1, var2);
    }
    public Class<?> define(String name, byte[] b, int off, int len) {
        return defineClass(name, b, off, len);
    }
}
