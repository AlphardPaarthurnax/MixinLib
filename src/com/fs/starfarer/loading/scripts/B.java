package com.fs.starfarer.loading.scripts;

public class B extends ClassLoader {
    public B(ClassLoader var1) {
        super(var1);
    }
    public Class<?> loadClass(String var1, boolean var2) throws ClassNotFoundException {
        return super.loadClass(var1, var2);
    }
}
