package com.github.alphardpaarthurnax.mixinlib.janino;

import com.github.alphardpaarthurnax.mixinlib.loader.TargetClassLoader;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;
import com.github.alphardpaarthurnax.mixinlib.logger.TransformDiagnostics;
import com.github.alphardpaarthurnax.mixinlib.mixin.TransformContext;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicLong;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** 处理在加载器之外定义、但属于其后代加载器的类，主要来自 Janino 生成的类。 */
public final class RuntimeTransformer implements ClassFileTransformer {
    private final TargetClassLoader target;
    private final IMixinTransformer transformer;
    private final AtomicLong counter = new AtomicLong();

    public RuntimeTransformer(TargetClassLoader target, IMixinTransformer transformer) {
        this.target = target;
        this.transformer = transformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (loader == null || loader == target || name == null || isHostClass(name)
                || !isTargetDescendant(loader)) {
            return null;
        }
        String dotted = name.replace('/', '.');
        try {
            synchronized (transformer) {
                try (TransformContext.Scope ignored = TransformContext.enter(loader, dotted, classfileBuffer);
                     TransformDiagnostics.Scope diagnostic =
                             TransformDiagnostics.enter("external", loader, dotted)) {
                    MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
                    if (!transformer.couldTransformClass(environment, dotted)) {
                        return null;
                    }
                    byte[] result = transformer.transformClass(environment, dotted, classfileBuffer);
                    if (result != null && result != classfileBuffer) {
                        MixinLog.info("[Transform] external #" + counter.incrementAndGet() + " " + dotted
                                + " loader=" + loader.getClass().getName());
                        return result;
                    }
                    return null;
                }
            }
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:TRANSFORM_FAIL] kind=external class=" + dotted
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(loader), throwable);
            IllegalClassFormatException failure = new IllegalClassFormatException(
                    "Mixin transformation failed for " + dotted);
            failure.initCause(throwable);
            throw failure;
        }
    }

    private static boolean isHostClass(String name) {
        return name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")
                || name.startsWith("org/spongepowered/asm/") || name.startsWith("org/objectweb/asm/")
                || name.startsWith("com/github/alphardpaarthurnax/mixinlib/");
    }

    private boolean isTargetDescendant(ClassLoader loader) {
        ClassLoader current = loader;
        while (current != null) {
            if (current == target) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
