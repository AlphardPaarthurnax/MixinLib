package com.github.alphardpaarthurnax.mixinlib.loader;

import com.github.alphardpaarthurnax.mixinlib.janino.StarsectorTransformer;
import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;
import com.github.alphardpaarthurnax.mixinlib.logger.TransformDiagnostics;
import com.github.alphardpaarthurnax.mixinlib.mixin.TransformContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * 在同一命名空间中定义 Starsector 核心类和已启用 JAR Mod 的类。
 * 宿主与 Mixin 包仍通过父加载器保持可见。
 */
public final class TargetClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> resourceOverrides = new LinkedHashMap<>();
    private final Set<String> invalidClasses = Collections.synchronizedSet(new LinkedHashSet<>());
    private final ThreadLocal<Set<String>> defining = ThreadLocal.withInitial(LinkedHashSet::new);
    private volatile IMixinTransformer transformer;

    public TargetClassLoader(URL[] urls, ClassLoader parent) {
        super("MixinLib-Target", urls, parent);
    }

    public void setTransformer(IMixinTransformer value) {
        if (value == null) {
            throw new IllegalArgumentException("transformer");
        }
        transformer = value;
    }

    public void addResourceOverride(String name, byte[] bytes) {
        byte[] previous = resourceOverrides.putIfAbsent(name, bytes);
        if (previous != null) {
            throw new IllegalStateException("Duplicate target resource " + name);
        }
    }

    public void registerInvalidClass(String className) {
        invalidClasses.add(className);
    }

    public boolean isLoaded(String className) {
        synchronized (getClassLoadingLock(className)) {
            return findLoadedClass(className) != null;
        }
    }

    public byte[] readRawClassBytes(String className) throws IOException {
        byte[] inFlight = TransformContext.bytes(className);
        if (inFlight != null) {
            return inFlight;
        }
        URL resource = findResource(className.replace('.', '/') + ".class");
        return resource == null ? null : read(resource.openStream());
    }

    public InputStream openTargetResource(String name) {
        byte[] override = resourceOverrides.get(name);
        if (override != null) {
            return new ByteArrayInputStream(override);
        }
        URL resource = findResource(name);
        try {
            return resource == null ? null : resource.openStream();
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    try {
                        loaded = getParent().loadClass(name);
                    } catch (ClassNotFoundException parentMiss) {
                        // Java 17 已不再包含部分 javax API；Starsector 会在自己的核心 JAR
                        // 中提供这些 API，例如 JAXB。
                        loaded = findClass(name);
                    }
                } else {
                    boolean targetOwnsResource = findResource(name.replace('.', '/') + ".class") != null;
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException targetMiss) {
                        if (targetOwnsResource) {
                            throw targetMiss;
                        }
                        loaded = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                try {
                    resolveClass(loaded);
                } catch (RuntimeException | LinkageError throwable) {
                    MixinLog.error("[Diag:RESOLVE_FAIL] class=" + name
                            + " thread=" + TransformDiagnostics.threadDescription()
                            + " loader=" + TransformDiagnostics.loaderDescription(this), throwable);
                    throw throwable;
                }
            }
            return loaded;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            ClassNotFoundException failure =
                    new ClassNotFoundException("Mixin marked class invalid: " + name);
            MixinLog.error("[Diag:DEFINE_REJECTED] class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " reason=mixin-invalid", failure);
            throw failure;
        }
        Set<String> active = defining.get();
        if (!active.add(name)) {
            ClassNotFoundException failure =
                    new ClassNotFoundException("Recursive class definition: " + name);
            MixinLog.error("[Diag:DEFINE_RECURSIVE] class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this)
                    + " definitionStack=" + TransformDiagnostics.definitionStack(active), failure);
            throw failure;
        }

        URL resource = null;
        try {
            String resourceName = name.replace('.', '/') + ".class";
            resource = findResource(resourceName);
            if (resource == null) {
                return defineGeneratedClass(name);
            }

            ClassMaterial material = readClassMaterial(resource);
            byte[] transformed = transform(name, material.bytes);
            definePackageIfNecessary(name, material.manifest, material.codeSourceUrl);
            CodeSource source = new CodeSource(material.codeSourceUrl, material.certificates);
            ProtectionDomain domain = new ProtectionDomain(source, null, this, null);
            Class<?> defined = defineClass(name, transformed, 0, transformed.length, domain);
            if (TransformDiagnostics.isWatched(name)) {
                MixinLog.info("[Diag:DEFINE_OK] class=" + name
                        + " thread=" + TransformDiagnostics.threadDescription()
                        + " loader=" + TransformDiagnostics.loaderDescription(this)
                        + " source=" + material.codeSourceUrl
                        + " rawBytes=" + material.bytes.length
                        + " definedBytes=" + transformed.length);
            }
            return defined;
        } catch (ClassNotFoundException throwable) {
            if (resource != null || TransformDiagnostics.isWatched(name)) {
                MixinLog.error("[Diag:DEFINE_FAIL] class=" + name
                        + " stage=class-not-found"
                        + " thread=" + TransformDiagnostics.threadDescription()
                        + " loader=" + TransformDiagnostics.loaderDescription(this)
                        + " resource=" + resource
                        + " definitionStack=" + TransformDiagnostics.definitionStack(active), throwable);
            }
            throw throwable;
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:DEFINE_FAIL] class=" + name
                    + " stage=define-or-link"
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this)
                    + " resource=" + resource
                    + " definitionStack=" + TransformDiagnostics.definitionStack(active), throwable);
            throw new ClassNotFoundException("Unable to define target class " + name, throwable);
        } finally {
            active.remove(name);
            if (active.isEmpty()) {
                defining.remove();
            }
        }
    }

    private Class<?> defineGeneratedClass(String name) throws ClassNotFoundException {
        IMixinTransformer value = transformer;
        if (value == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            synchronized (value) {
                try (TransformContext.Scope ignored = TransformContext.enter(this, name, new byte[0]);
                     TransformDiagnostics.Scope diagnostic =
                             TransformDiagnostics.enter("generate", this, name)) {
                    byte[] generated = value.generateClass(MixinEnvironment.getDefaultEnvironment(), name);
                    if (generated == null) {
                        throw new ClassNotFoundException(name);
                    }
                    definePackageIfNecessary(name, null, null);
                    MixinLog.info("[Generate] " + name + " bytes=" + generated.length);
                    return defineClass(name, generated, 0, generated.length,
                            new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null, this, null));
                }
            }
        } catch (ClassNotFoundException throwable) {
            throw throwable;
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:GENERATE_FAIL] class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this), throwable);
            throw new ClassNotFoundException("Unable to generate Mixin class " + name, throwable);
        }
    }

    private byte[] transform(String name, byte[] original) throws Throwable {
        byte[] prepared = StarsectorTransformer.transform(name, original);
        IMixinTransformer value = transformer;
        if (value == null) {
            return prepared;
        }
        try {
            synchronized (value) {
                try (TransformContext.Scope ignored = TransformContext.enter(this, name, prepared);
                     TransformDiagnostics.Scope diagnostic =
                             TransformDiagnostics.enter("target", this, name)) {
                    MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
                    if (!value.couldTransformClass(environment, name)) {
                        return prepared;
                    }
                    byte[] result = value.transformClass(environment, name, prepared);
                    if (result != null && result != prepared) {
                        MixinLog.info("[Transform] target " + name + " " + prepared.length + " -> " + result.length);
                    }
                    return result == null ? prepared : result;
                }
            }
        } catch (Throwable throwable) {
            MixinLog.error("[Diag:TRANSFORM_FAIL] kind=target class=" + name
                    + " thread=" + TransformDiagnostics.threadDescription()
                    + " loader=" + TransformDiagnostics.loaderDescription(this), throwable);
            throw throwable;
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] override = resourceOverrides.get(name);
        return override == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(override);
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        LinkedHashSet<URL> resources = new LinkedHashSet<>();
        Enumeration<URL> own = findResources(name);
        while (own.hasMoreElements()) {
            resources.add(own.nextElement());
        }
        Enumeration<URL> parent = getParent().getResources(name);
        while (parent.hasMoreElements()) {
            resources.add(parent.nextElement());
        }
        return Collections.enumeration(resources);
    }

    private static boolean isParentFirst(String name) {
        if (name.startsWith("org.spongepowered.asm.synthetic.")) {
            return false;
        }
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.")
                || name.startsWith("org.spongepowered.asm.") || name.startsWith("org.objectweb.asm.")
                || name.startsWith("com.github.alphardpaarthurnax.mixinlib.");
    }

    private void definePackageIfNecessary(String className, Manifest manifest, URL source) {
        int split = className.lastIndexOf('.');
        if (split < 0) {
            return;
        }
        String packageName = className.substring(0, split);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            if (manifest != null && source != null) {
                definePackage(packageName, manifest, source);
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException ignored) {
            // 另一个并行类定义已经率先定义了该包。
        }
    }

    private static ClassMaterial readClassMaterial(URL resource) throws IOException {
        URLConnection connection = resource.openConnection();
        Manifest manifest = null;
        URL source = resource;
        JarEntry entry = null;
        Certificate[] certificates = null;

        // LunaLib 的版本检查器会全局禁用 URLConnection 缓存。缓存关闭时，关闭
        // JarURLConnection 的条目流也会关闭对应 JarFile，因此所有依赖 JarFile
        // 的元数据都必须在流关闭前取得。证书只会在条目读取完成后填充，并会继续
        // 保存在 JarEntry 对象中。
        if (connection instanceof JarURLConnection jarConnection) {
            manifest = jarConnection.getManifest();
            source = jarConnection.getJarFileURL();
            entry = jarConnection.getJarEntry();
        }

        byte[] bytes;
        try (InputStream stream = connection.getInputStream()) {
            bytes = read(stream);
        }
        if (entry != null) {
            certificates = entry == null ? null : entry.getCertificates();
        }
        return new ClassMaterial(bytes, manifest, source, certificates);
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    private record ClassMaterial(byte[] bytes, Manifest manifest, URL codeSourceUrl,
                                 Certificate[] certificates) {
    }
}
