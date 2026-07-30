package com.github.alphardpaarthurnax.mixinlib.bootstrap;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 从 Launcher JVM 到 Game JVM 的 ClassFileTransformer */
final class HandoffTransformer implements ClassFileTransformer {
    private static final String LAUNCHER = "com/fs/starfarer/StarfarerLauncher";
    private static final String DESCRIPTOR = "(ZZLjava/lang/String;Ljava/lang/String;)V";
    private static final String BRIDGE =
            "com/github/alphardpaarthurnax/mixinlib/bootstrap/Handoff";
    private final AtomicBoolean transformed = new AtomicBoolean();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!LAUNCHER.equals(className) || classBeingRedefined != null) {
            return null;
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(classfileBuffer).accept(node, 0);
            MethodNode launch = findLaunchMethod(node);
            MethodInsnNode combatMain = findCombatMainCall(launch);

            LabelNode continueLabel = new LabelNode();
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ILOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "beforeGame",
                    "(ZZLjava/lang/String;Ljava/lang/String;)Z", false));
            hook.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
            hook.add(new InsnNode(Opcodes.RETURN));
            hook.add(continueLabel);
            launch.instructions.insertBefore(combatMain, hook);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            transformed.set(true);
            MixinLog.info("[Handoff] patched StarfarerLauncher method " + launch.name + DESCRIPTOR);
            return writer.toByteArray();
        } catch (Throwable throwable) {
            MixinLog.error("[Handoff] unable to patch StarfarerLauncher; refusing unsafe in-process startup", throwable);
            throw new IllegalStateException("MixinLib could not install the launcher handoff", throwable);
        }
    }

    boolean wasTransformed() {
        return transformed.get();
    }

    private static MethodNode findLaunchMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || !DESCRIPTOR.equals(method.desc)) {
                continue;
            }
            boolean readsEnabledMods = false;
            boolean entersCombatMain = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    readsEnabledMods |= "com/fs/starfarer/launcher/ModManager".equals(call.owner)
                            && "getEnabledMods".equals(call.name);
                    entersCombatMain |= "com/fs/starfarer/combat/CombatMain".equals(call.owner)
                            && "main".equals(call.name);
                }
            }
            if (readsEnabledMods && entersCombatMain) {
                return method;
            }
        }
        throw new IllegalStateException("launch method fingerprint not found");
    }

    private static MethodInsnNode findCombatMainCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && "com/fs/starfarer/combat/CombatMain".equals(call.owner)
                    && "main".equals(call.name)) {
                return call;
            }
        }
        throw new IllegalStateException("CombatMain.main call not found");
    }
}
