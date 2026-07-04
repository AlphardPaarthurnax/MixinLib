package com.github.alphardpaarthurnax.mixinlib;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public class ModClassLoader extends URLClassLoader {

    private static volatile ModClassLoader instance;

    private ModClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public static ModClassLoader getInstance() {
        return instance;
    }

    public static void init(String modsPath) {
        if (instance != null) return;
        File modsDir = new File(modsPath);
        if (!modsDir.isDirectory()) return;
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return;
        List<URL> urlList = new ArrayList<>();
        for (File modDir : modDirs) {
            File jarsDir = new File(modDir, "jars");
            File[] jars = jarsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (jars == null) continue;
            for (File jarFile : jars) {
                try {
                    urlList.add(jarFile.toURI().toURL());
                } catch (Exception ignored) {}
            }
        }
        instance = new ModClassLoader(
            urlList.toArray(new URL[0]),
            ModClassLoader.class.getClassLoader()
        );
    }
}
