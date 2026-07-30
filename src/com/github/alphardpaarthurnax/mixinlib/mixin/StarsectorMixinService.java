package com.github.alphardpaarthurnax.mixinlib.mixin;

import com.github.alphardpaarthurnax.mixinlib.bootstrap.AgentEntry;
import com.github.alphardpaarthurnax.mixinlib.loader.TargetClassLoader;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

/** 显式绑定到游戏与 Mod 统一加载器的 Fabric Mixin 服务。 */
public final class StarsectorMixinService extends MixinServiceAbstract {
    private static volatile TargetClassLoader target;
    private static volatile URL[] targetClasspath = new URL[0];
    private static volatile IMixinTransformer transformer;

    private final Set<String> invalidClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> transformerExclusions = ConcurrentHashMap.newKeySet();

    static void configure(TargetClassLoader value, URL[] classpath) {
        if (target != null && target != value) {
            throw new IllegalStateException("Mixin target loader already configured");
        }
        target = value;
        targetClasspath = classpath.clone();
    }

    static TargetClassLoader targetLoader() {
        TargetClassLoader value = target;
        if (value == null) {
            throw new IllegalStateException("Mixin target loader is not configured");
        }
        return value;
    }

    IMixinTransformer createTransformer() {
        IMixinTransformer value = transformer;
        if (value != null) {
            return value;
        }
        synchronized (StarsectorMixinService.class) {
            value = transformer;
            if (value == null) {
                IMixinTransformerFactory factory = getInternal(IMixinTransformerFactory.class);
                if (factory == null) {
                    throw new IllegalStateException(
                            "Mixin transformer factory was not offered to the service");
                }
                value = factory.createTransformer();
                transformer = value;
            }
            return value;
        }
    }

    @Override
    public String getName() {
        return "MixinLib/UnifiedTarget";
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
            public URL[] getClassPath() {
                return targetClasspath.clone();
            }

            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                return findClass(name, false);
            }

            @Override
            public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
                ClassLoader loader = TransformContext.loader();
                if (loader == null || loader == StarsectorMixinService.class.getClassLoader()) {
                    loader = targetLoader();
                }
                return Class.forName(name, initialize, loader);
            }

            @Override
            public Class<?> findAgentClass(String name, boolean initialize)
                    throws ClassNotFoundException {
                return Class.forName(name, initialize, StarsectorMixinService.class.getClassLoader());
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
            public ClassNode getClassNode(String name, boolean runTransformers)
                    throws ClassNotFoundException, IOException {
                return getClassNode(name, runTransformers, ClassReader.EXPAND_FRAMES);
            }

            @Override
            public ClassNode getClassNode(String name, boolean runTransformers, int flags)
                    throws ClassNotFoundException, IOException {
                byte[] bytes = classBytes(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, flags);
                return node;
            }
        };
    }

    private static byte[] classBytes(String name) throws IOException {
        byte[] inFlight = TransformContext.bytes(name);
        if (inFlight != null && inFlight.length > 0) {
            return inFlight;
        }

        ClassLoader active = TransformContext.loader();
        String resourceName = name.replace('.', '/') + ".class";
        if (active != null && active != target) {
            try (InputStream stream = active.getResourceAsStream(resourceName)) {
                if (stream != null) {
                    return stream.readAllBytes();
                }
            }
        }
        return targetLoader().readRawClassBytes(name);
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return new ITransformerProvider() {
            @Override
            public Collection<ITransformer> getTransformers() {
                return Collections.emptyList();
            }

            @Override
            public Collection<ITransformer> getDelegatedTransformers() {
                return Collections.emptyList();
            }

            @Override
            public void addTransformerExclusion(String name) {
                transformerExclusions.add(name);
            }
        };
    }

    @Override
    public IClassTracker getClassTracker() {
        return new IClassTracker() {
            @Override
            public void registerInvalidClass(String name) {
                invalidClasses.add(name);
                targetLoader().registerInvalidClass(name);
            }

            @Override
            public boolean isClassLoaded(String name) {
                if (targetLoader().isLoaded(name)) {
                    return true;
                }
                for (Class<?> loaded : AgentEntry.instrumentation().getAllLoadedClasses()) {
                    if (name.equals(loaded.getName())
                            && isTargetLoaderOrChild(loaded.getClassLoader())) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String getClassRestrictions(String name) {
                if (invalidClasses.contains(name)) {
                    return "PACKAGE_CLASSLOADER_EXCLUSION";
                }
                for (String exclusion : transformerExclusions) {
                    if (name.startsWith(exclusion)) {
                        return "PACKAGE_TRANSFORMER_EXCLUSION";
                    }
                }
                return "";
            }
        };
    }

    private static boolean isTargetLoaderOrChild(ClassLoader loader) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == target) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return new IMixinAuditTrail() {
            @Override
            public void onApply(String targetClass, String mixinClass) {
                MixinLog.info("[Audit] APPLY mixin=" + mixinClass + " target=" + targetClass);
            }

            @Override
            public void onPostProcess(String className) {
                MixinLog.info("[Audit] POSTPROCESS " + className);
            }

            @Override
            public void onGenerate(String targetClass, String generatorName) {
                MixinLog.info("[Audit] GENERATE generator=" + generatorName
                        + " target=" + targetClass);
            }
        };
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return IFeatureValidator.ALLOW_ALL;
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return IAdviceProvider.GENERIC;
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        ContainerHandleVirtual container = new ContainerHandleVirtual("MixinLib");
        for (URL url : targetClasspath) {
            try {
                container.add(new ContainerHandleURI(url.toURI()));
            } catch (Exception ignored) {
                // 无效 URL 无法构成平台容器，直接忽略。
            }
        }
        return container;
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        List<IContainerHandle> containers = new ArrayList<>();
        for (URL url : targetClasspath) {
            try {
                containers.add(new ContainerHandleURI(url.toURI()));
            } catch (Exception ignored) {
                // 无效 URL 无法构成平台容器，直接忽略。
            }
        }
        return containers;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream stream = targetLoader().openTargetResource(name);
        return stream != null ? stream
                : StarsectorMixinService.class.getClassLoader().getResourceAsStream(name);
    }
}
