package com.github.alphardpaarthurnax.mixinlib;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.Method;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.tools.agent.MixinAgent;

public class StarsectorMixinService extends MixinServiceAbstract {

    private static volatile IMixinTransformer cachedTransformer;
    private static volatile ClassLoader gameClassLoader;

    public static IMixinTransformer getCachedTransformer() {
        return cachedTransformer;
    }

    private static ClassLoader findGameBClassLoader() {
        ClassLoader cl = gameClassLoader;
        if (cl != null) return cl;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        while (ctx != null) {
            if (ctx.getClass().getName().equals("com.fs.starfarer.loading.scripts.B")) {
                gameClassLoader = ctx;
                return ctx;
            }
            ctx = ctx.getParent();
        }
        return StarsectorMixinService.class.getClassLoader();
    }

    private static Class<?> defineOnGameLoader(String name, byte[] bytes) throws ClassNotFoundException {
        ClassLoader target = findGameBClassLoader();
        try {
            Class<?> defined;
            if (target instanceof com.fs.starfarer.loading.scripts.B) {
                defined = ((com.fs.starfarer.loading.scripts.B) target).define(name, bytes, 0, bytes.length);
            } else {
                Method define = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                define.setAccessible(true);
                defined = (Class<?>) define.invoke(target, name, bytes, 0, bytes.length);
            }
            return defined;
        } catch (Exception e) {
            logError("[Svc]  FAILED to define " + name, e);
            throw new ClassNotFoundException("Cannot define " + name + " in game classloader", e);
        }
    }

    private static byte[] lookupBytes(String name) {
        ModClassLoader mc = ModClassLoader.getInstance();
        if (mc == null) return null;
        String resourceName = name.replace('.', '/') + ".class";
        try (InputStream is = mc.getResourceAsStream(resourceName)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public String getName() {
        return "StarsectorMixinService";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.DEFAULT;
    }

    @Override
    public IClassProvider getClassProvider() {
        return new IClassProvider() {
            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                try {
                    return Class.forName(name, false, getClassLoader());
                } catch (ClassNotFoundException e) {
                    ModClassLoader mc = ModClassLoader.getInstance();
                    if (mc != null) {
                        try {
                            return Class.forName(name, false, mc);
                        } catch (ClassNotFoundException ignored) {}
                    }
                    byte[] bytes = lookupBytes(name);
                    if (bytes != null) {
                        return defineOnGameLoader(name, bytes);
                    }
                    throw new ClassNotFoundException(name);
                }
            }

            @Override
            public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
                try {
                    return Class.forName(name, initialize, getClassLoader());
                } catch (ClassNotFoundException e) {
                    ModClassLoader mc = ModClassLoader.getInstance();
                    if (mc != null) {
                        try {
                            return Class.forName(name, initialize, mc);
                        } catch (ClassNotFoundException ignored) {}
                    }
                    byte[] bytes = lookupBytes(name);
                    if (bytes != null) {
                        return defineOnGameLoader(name, bytes);
                    }
                    throw new ClassNotFoundException(name);
                }
            }

            @Override
            public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
                return findClass(name, initialize);
            }

            @Override
            public URL[] getClassPath() {
                return new URL[0];
            }
        };
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return new IClassBytecodeProvider() {
            @Override
            public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
                return getClassNode(name, true, ClassReader.EXPAND_FRAMES);
            }

            @Override
            public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
                return getClassNode(name, runTransformers, ClassReader.EXPAND_FRAMES);
            }

            @Override
            public ClassNode getClassNode(String name, boolean runTransformers, int flags) throws ClassNotFoundException, IOException {
                byte[] bytes = getClassBytes(name);
                if (bytes == null) return null;
                try {
                    ClassReader reader = new ClassReader(bytes);
                    ClassNode node = new ClassNode();
                    reader.accept(node, flags);
                    return node;
                } catch (Exception e) {
                    logError("[Svc]  getClassNode: ASM error for " + name, e);
                    return null;
                }
            }

            private byte[] getClassBytes(String name) throws IOException {
                ModClassLoader mc = ModClassLoader.getInstance();
                String resourceName = name.replace('.', '/') + ".class";
                if (mc != null) {
                    try (InputStream is = mc.getResourceAsStream(resourceName)) {
                        if (is != null) return is.readAllBytes();
                    }
                }
                try (InputStream is = getClassLoader().getResourceAsStream(resourceName)) {
                    if (is != null) return is.readAllBytes();
                }
                return null;
            }
        };
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual("MixinLib");
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.singletonList("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    @Override
    public void init() {
        IMixinTransformerFactory factory = getInternal(IMixinTransformerFactory.class);
        if (factory != null) {
            cachedTransformer = factory.createTransformer();
            new MixinAgent(cachedTransformer);
        }
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return new IMixinAuditTrail() {
            public void onApply(String targetClass, String mixinClass) {
                log("[Audit] APPLY mixin=" + mixinClass + " to target=" + targetClass);
            }
            public void onPostProcess(String className) {
                log("[Audit] POSTPROCESS " + className);
            }
            public void onGenerate(String targetClass, String mixinClass) {
                log("[Audit] GENERATE mixin=" + mixinClass + " for target=" + targetClass);
            }
        };
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        ModClassLoader mc = ModClassLoader.getInstance();
        if (mc != null) {
            InputStream is = mc.getResourceAsStream(name);
            if (is != null) return is;
        }
        Set<ClassLoader> tried = new HashSet<>();
        ClassLoader[] loaders = {
            Thread.currentThread().getContextClassLoader(),
            getClass().getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            while (cl != null && tried.add(cl)) {
                InputStream is = cl.getResourceAsStream(name);
                if (is != null) return is;
                cl = cl.getParent();
            }
        }
        return null;
    }

    private ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) return cl;
        cl = getClass().getClassLoader();
        if (cl != null) return cl;
        return ClassLoader.getSystemClassLoader();
    }

    private static String logPath() {
        String dir = System.getProperty("com.fs.starfarer.settings.paths.logs");
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.dir", ".");
        }
        return dir + File.separator + "corecracking_debug.log";
    }

    private static void logError(String msg, Throwable t) {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(logPath(), true));
            pw.println(new java.util.Date() + " " + msg);
            t.printStackTrace(pw);
            pw.close();
        } catch (Throwable ignored) {}
    }

    private static void log(String msg) {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(logPath(), true));
            pw.println(new java.util.Date() + " " + msg);
            pw.close();
        } catch (Throwable ignored) {}
    }
}