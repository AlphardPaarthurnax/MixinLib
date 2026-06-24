package com.github.alphardpaarthurnax.mixinlib;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

public class AgentEntry {

    public static void premain(String arg, Instrumentation inst) {
        try {
            Field f = Class.forName("org.spongepowered.tools.agent.MixinAgent")
                    .getDeclaredField("instrumentation");
            f.setAccessible(true);
            f.set(null, inst);

            MixinBootstrap.init();

            registerModMixins();

            StarsectorMixinService svc = (StarsectorMixinService) MixinService.getService();
            svc.init();

            IMixinTransformer transformer = StarsectorMixinService.getCachedTransformer();
            if (transformer != null) {
                inst.addTransformer(new ClassFileTransformer() {
                    public byte[] transform(ClassLoader loader, String name,
                                            Class<?> classBeingRedefined,
                                            ProtectionDomain protectionDomain,
                                            byte[] classfileBuffer) {
                        String dotted = name.replace('/', '.');
                        return transformer.transformClassBytes(dotted, dotted, classfileBuffer);
                    }
                }, false);
            }

            System.out.println("[MixinLib] Initialized");
        } catch (Throwable t) {
            System.err.println("[MixinLib] Failed: " + t);
        }
    }

    private static void registerModMixins() {
        String modsPath = System.getProperty("com.fs.starfarer.settings.paths.mods");
        if (modsPath == null || modsPath.isEmpty()) return;

        java.io.File modsDir = new java.io.File(modsPath);
        if (!modsDir.isDirectory()) return;

        java.io.File[] modDirs = modsDir.listFiles(java.io.File::isDirectory);
        if (modDirs == null) return;

        List<String> configs = new ArrayList<>();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();

        for (java.io.File modDir : modDirs) {
            java.io.File jarsDir = new java.io.File(modDir, "jars");
            if (!jarsDir.isDirectory()) continue;

            java.io.File[] jars = jarsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (jars == null) continue;

            for (java.io.File jarFile : jars) {
                try (JarFile jar = new JarFile(jarFile)) {
                    List<String> found = jar.stream()
                            .filter(e -> !e.isDirectory() && e.getName().endsWith(".mixin.json"))
                            .map(ZipEntry::getName)
                            .toList();
                    configs.addAll(found);

                    jar.stream()
                            .filter(e -> !e.isDirectory() && e.getName().endsWith("Mixin.class"))
                            .forEach(e -> {
                                try {
                                    String className = e.getName()
                                            .replace('/', '.')
                                            .replace(".class", "");
                                    byte[] bytes = jar.getInputStream(e).readAllBytes();
                                    StarsectorMixinService.cacheClass(className, bytes);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }
        }

        for (String config : configs) {
            try {
                env.addConfiguration(config);
                System.out.println("[MixinLib] Loaded: " + config);
            } catch (Throwable ignored) {
            }
        }
    }
}
