package com.github.alphardpaarthurnax.mixinlib.janino;

import com.github.alphardpaarthurnax.mixinlib.logger.MixinLog;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 处理原版 ScriptStore, 保留Janino能力 */
public final class StarsectorTransformer {
    private static final String SCRIPT_STORE = "com.fs.starfarer.loading.scripts.ScriptStore";
    private static final String URL_LOADER = "java/net/URLClassLoader";
    private static final String URL = "java/net/URL";
    private static final String CONSTRUCTOR = "([Ljava/net/URL;Ljava/lang/ClassLoader;)V";

    private StarsectorTransformer() {
    }

    public static byte[] transform(String className, byte[] bytes) {
        if (!SCRIPT_STORE.equals(className)) {
            return bytes;
        }

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int allocations = 0;
        int constructors = 0;
        int emptiedArguments = 0;

        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && URL_LOADER.equals(type.desc)) {
                    allocations++;
                } else if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL
                        && URL_LOADER.equals(call.owner) && "<init>".equals(call.name)
                        && CONSTRUCTOR.equals(call.desc)) {
                    constructors++;
                    AbstractInsnNode parentArgument = previousReal(call);
                    AbstractInsnNode urlArgument = previousReal(parentArgument);
                    if (!(urlArgument instanceof VarInsnNode variable)
                            || variable.getOpcode() != Opcodes.ALOAD) {
                        throw new IllegalStateException(
                                "Unexpected ScriptStore URLClassLoader argument shape");
                    }

                    method.instructions.insertBefore(urlArgument, new InsnNode(Opcodes.ICONST_0));
                    method.instructions.set(urlArgument, new TypeInsnNode(Opcodes.ANEWARRAY, URL));
                    emptiedArguments++;
                }
            }
        }

        if (allocations == 0 || allocations != constructors || constructors != emptiedArguments) {
            throw new IllegalStateException("Unexpected ScriptStore URLClassLoader shape: new="
                    + allocations + " init=" + constructors + " emptied=" + emptiedArguments);
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        MixinLog.info("[Compat] ScriptStore emptied " + emptiedArguments
                + " redundant URLClassLoader classpath(s)");
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE
                || current.getType() == AbstractInsnNode.FRAME)) {
            current = current.getPrevious();
        }
        return current;
    }
}
