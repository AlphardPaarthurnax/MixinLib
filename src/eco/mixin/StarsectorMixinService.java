package eco.mixin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

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
    private static final Map<String, byte[]> CLASS_CACHE = new ConcurrentHashMap<>();

    public static IMixinTransformer getCachedTransformer() {
        return cachedTransformer;
    }

    public static void cacheClass(String className, byte[] bytes) {
        CLASS_CACHE.put(className, bytes);
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
        ClassLoader modFallback = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = CLASS_CACHE.get(name);
                if (bytes == null) {
                    bytes = findClassInModJars(name);
                }
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        };

        return new IClassProvider() {
            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                try {
                    return Class.forName(name, false, getClassLoader());
                } catch (ClassNotFoundException e) {
                    return modFallback.loadClass(name);
                }
            }

            @Override
            public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
                try {
                    return Class.forName(name, initialize, getClassLoader());
                } catch (ClassNotFoundException e) {
                    return modFallback.loadClass(name);
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
                ClassReader reader = new ClassReader(bytes);
                ClassNode node = new ClassNode();
                reader.accept(node, flags);
                return node;
            }

            private byte[] getClassBytes(String name) throws IOException {
                byte[] cached = CLASS_CACHE.get(name);
                if (cached != null) return cached;
                String resourceName = name.replace('.', '/') + ".class";
                InputStream is = getClassLoader().getResourceAsStream(resourceName);
                if (is == null) {
                    byte[] fromJar = findClassInModJars(name);
                    if (fromJar != null) return fromJar;
                    return null;
                }
                byte[] bytes = is.readAllBytes();
                is.close();
                return bytes;
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
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
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

        if (name.endsWith(".mixin.json")) {
            return findModResource(name);
        }
        return null;
    }

    private static InputStream findModResource(String name) {
        String modsPath = System.getProperty("com.fs.starfarer.settings.paths.mods");
        if (modsPath == null) return null;
        File modsDir = new File(modsPath);
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return null;
        for (File modDir : modDirs) {
            File configFile = new File(modDir, name);
            if (configFile.isFile()) {
                try { return new FileInputStream(configFile); } catch (IOException ignored) {}
            }
            File jarsDir = new File(modDir, "jars");
            File[] jars = jarsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (jars == null) continue;
            for (File jarFile : jars) {
                try (JarFile jar = new JarFile(jarFile)) {
                    ZipEntry entry = jar.getEntry(name);
                    if (entry != null) return jar.getInputStream(entry);
                } catch (IOException ignored) {}
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

    private static byte[] findClassInModJars(String className) {
        String resourceName = className.replace('.', '/') + ".class";
        String modsPath = System.getProperty("com.fs.starfarer.settings.paths.mods");
        if (modsPath == null) return null;
        File modsDir = new File(modsPath);
        if (!modsDir.isDirectory()) return null;
        File[] modDirs = modsDir.listFiles(File::isDirectory);
        if (modDirs == null) return null;
        for (File modDir : modDirs) {
            File jarsDir = new File(modDir, "jars");
            File[] jars = jarsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (jars == null) continue;
            for (File jarFile : jars) {
                try (JarFile jar = new JarFile(jarFile)) {
                    ZipEntry entry = jar.getEntry(resourceName);
                    if (entry != null) {
                        InputStream is = jar.getInputStream(entry);
                        byte[] bytes = is.readAllBytes();
                        is.close();
                        return bytes;
                    }
                } catch (IOException ignored) {}
            }
        }
        return null;
    }
}
