package com.github.alphardpaarthurnax.mixinlib;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

public class AgentEntry {

    private static int transformCounter = 0;

    public static void premain(String arg, Instrumentation inst) {
        log("[Agent] premain called");
        try {
            Field f = Class.forName("org.spongepowered.tools.agent.MixinAgent")
                    .getDeclaredField("instrumentation");
            f.setAccessible(true);
            f.set(null, inst);

            log("[Agent] calling MixinBootstrap.init()");
            MixinBootstrap.init();

            String modsPath = System.getProperty("com.fs.starfarer.settings.paths.mods");
            ModClassLoader.init(modsPath);

            java.io.File modsDir = new java.io.File(modsPath);
            java.io.File[] modDirs = modsDir.listFiles(java.io.File::isDirectory);
            if (modDirs != null) {
                for (java.io.File modDir : modDirs) {
                    java.io.File modInfoFile = new java.io.File(modDir, "mod_info.json");
                    if (!modInfoFile.isFile()) continue;
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(modInfoFile))) {
                        JsonObject obj = new Gson().fromJson(br, JsonObject.class);
                        String modId = obj.get("id").getAsString();
                        boolean addToSystemCl = modId.equals("MixinLib");
                        if (!addToSystemCl) {
                            JsonArray deps = obj.getAsJsonArray("dependencies");
                            if (deps != null) {
                                for (JsonElement dep : deps) {
                                    if ("MixinLib".equals(dep.getAsJsonObject().get("id").getAsString())) {
                                        addToSystemCl = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (addToSystemCl) {
                            java.io.File jarsDir = new java.io.File(modDir, "jars");
                            java.io.File[] jarFiles = jarsDir.listFiles(jf -> jf.isFile() && jf.getName().endsWith(".jar"));
                            if (jarFiles != null) {
                                for (java.io.File jarFile : jarFiles) {
                                    try {
                                        log("[Agent]  appending to System CL: " + modDir.getName() + "/jars/" + jarFile.getName());
                                        inst.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(jarFile));
                                    } catch (Exception e) {
                                        logError("[Agent]  FAILED to append jar: " + jarFile, e);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log("[Agent]  FAILED to read mod_info.json for " + modDir.getName() + ": " + e);
                    }
                }
            }

            registerModMixins();

            StarsectorMixinService svc = (StarsectorMixinService) MixinService.getService();
            svc.init();

            IMixinTransformer transformer = StarsectorMixinService.getCachedTransformer();
            if (transformer != null) {
                log("[Agent] adding transformer");
                inst.addTransformer(new ClassFileTransformer() {
                    public byte[] transform(ClassLoader loader, String name,
                                            Class<?> classBeingRedefined,
                                            ProtectionDomain protectionDomain,
                                            byte[] classfileBuffer) {
                        transformCounter++;
                        String dotted = name.replace('/', '.');
                        try {
                            byte[] result = transformer.transformClassBytes(dotted, dotted, classfileBuffer);
                            if (result != null && result != classfileBuffer) {
                                log("[Xform] #" + transformCounter + " MODIFIED " + dotted + " len=" + result.length);
                            }
                            return result != null ? result : classfileBuffer;
                        } catch (org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException e) {
                            String mixinInfo = e.getMixin() != null ? e.getMixin().toString() : "unknown";
                            logError("[Xform] MIXIN REJECTED for target=" + dotted + " mixin=" + mixinInfo, e);
                            return classfileBuffer;
                        } catch (Throwable t) {
                            logError("[Xform] EXCEPTION transforming " + dotted, t);
                            return classfileBuffer;
                        }
                    }
                }, false);
            } else {
                log("[Agent] transformer is NULL");
            }

            log("[MixinLib] Initialized");
            log("[Agent] premain done");
        } catch (Throwable t) {
            logError("[Agent] FAILED", t);
        }
    }

    private static void registerModMixins() {
        String modsPath = System.getProperty("com.fs.starfarer.settings.paths.mods");
        log("[Agent] registerModMixins modsPath=" + modsPath);
        if (modsPath == null || modsPath.isEmpty()) {
            log("[Agent] modsPath is null/empty, returning");
            return;
        }

        java.io.File modsDir = new java.io.File(modsPath);
        if (!modsDir.isDirectory()) {
            log("[Agent] modsDir not a directory: " + modsPath);
            return;
        }

        java.io.File[] modDirs = modsDir.listFiles(java.io.File::isDirectory);
        if (modDirs == null) {
            log("[Agent] modDirs listFiles returned null");
            return;
        }
        log("[Agent] found " + modDirs.length + " mod dirs");

        List<String> configs = new ArrayList<>();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();

        for (java.io.File modDir : modDirs) {
            log("[Agent] checking mod: " + modDir.getName());
            java.io.File jarsDir = new java.io.File(modDir, "jars");
            if (!jarsDir.isDirectory()) {
                log("[Agent]  no jars dir for " + modDir.getName());
                continue;
            }

            java.io.File[] jars = jarsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
            if (jars == null) {
                log("[Agent]  jars listFiles returned null for " + modDir.getName());
                continue;
            }
            log("[Agent]  " + modDir.getName() + " has " + jars.length + " jars");

            for (java.io.File jarFile : jars) {
                log("[Agent]   scanning jar: " + jarFile.getName());
                try (JarFile jar = new JarFile(jarFile)) {
                    List<String> found = jar.stream()
                            .filter(e -> !e.isDirectory() && e.getName().endsWith(".mixin.json"))
                            .map(ZipEntry::getName)
                            .toList();
                    if (!found.isEmpty()) {
                        log("[Agent]    found mixin configs: " + found);
                        for (String configName : found) {
                            ZipEntry configEntry = jar.getEntry(configName);
                            if (configEntry != null) {
                                try {
                                    String jsonContent = new String(jar.getInputStream(configEntry).readAllBytes());
                                    parseMixinConfig(configName, jsonContent);
                                } catch (Exception e) {
                                    log("[Agent]    FAILED to parse config: " + configName + " " + e);
                                }
                            }
                        }
                    }
                    configs.addAll(found);
                } catch (IOException ignored) {
                    log("[Agent]   FAILED to open jar: " + jarFile.getName());
                }
            }
        }

        log("[Agent] total configs found: " + configs.size());
        for (String config : configs) {
            try {
                env.addConfiguration(config);
                log("[MixinLib] Loaded: " + config);
            } catch (Throwable t) {
                logError("[Agent] FAILED to register config: " + config, t);
            }
        }
    }

    private static String logPath() {
        String dir = System.getProperty("com.fs.starfarer.settings.paths.logs");
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.dir", ".");
        }
        return dir + File.separator + "corecracking_debug.log";
    }

    private static void parseMixinConfig(String configName, String jsonContent) {
        try {
            JsonObject obj = new Gson().fromJson(jsonContent, JsonObject.class);
            StringBuilder sb = new StringBuilder("[Agent]    config " + configName + " declares:");
            String pkg = obj.has("package") ? obj.get("package").getAsString() + "." : "";
            appendMixinList(sb, obj, "mixins", pkg);
            appendMixinList(sb, obj, "client", pkg);
            appendMixinList(sb, obj, "server", pkg);
            appendMixinList(sb, obj, "common", pkg);
            log(sb.toString());
        } catch (Exception e) {
            log("[Agent]    FAILED to parse config JSON: " + configName + " " + e);
        }
    }

    private static void appendMixinList(StringBuilder sb, JsonObject obj, String key, String pkg) {
        JsonArray arr = obj.getAsJsonArray(key);
        if (arr != null) {
            sb.append(" [").append(key).append(":");
            for (JsonElement el : arr) {
                sb.append(" ").append(pkg).append(el.getAsString());
            }
            sb.append("]");
        }
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
