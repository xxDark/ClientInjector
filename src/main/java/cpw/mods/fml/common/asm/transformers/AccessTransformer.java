package cpw.mods.fml.common.asm.transformers;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LogWrapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.objectweb.asm.Opcodes.*;

public final class AccessTransformer implements IClassTransformer {

    private static class Modifier {
        String name = "";
        String desc = "";
        int oldAccess = 0;
        int newAccess = 0;
        int targetAccess = 0;
        boolean changeFinal = false;
        boolean markFinal = false;
        boolean modifyClassVisibility;

        private void setTargetAccess(String name) {
            if (name.startsWith("public")) targetAccess = ACC_PUBLIC;
            else if (name.startsWith("private")) targetAccess = ACC_PRIVATE;
            else if (name.startsWith("protected")) targetAccess = ACC_PROTECTED;

            if (name.endsWith("-f")) {
                changeFinal = true;
                markFinal = false;
            } else if (name.endsWith("+f")) {
                changeFinal = true;
                markFinal = true;
            }
        }
    }

    private final Map<String, List<Modifier>> modifiers = new HashMap<>();

    public AccessTransformer(JarFile jar, String atList) throws IOException {
        for (String at : atList.split(" ")) {
            ZipEntry jarEntry = jar.getEntry("META-INF/" + at);
            if (jarEntry != null) {
                try (InputStream in = jar.getInputStream(jarEntry);
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    processATFile(br);
                }
            } else {
                LogWrapper.warning("Unknown entry file: %s", at);
            }
        }
        LogWrapper.info("Loaded %d rules from AccessTransformer mod jar file %s", modifiers.size(), jar.getName());
    }

    public AccessTransformer(String... lines) {
        for (String line : lines) {
            processLine(line);
        }
        LogWrapper.info("Loaded %d rules from AccessTransformer lines", modifiers.size());
    }

    private void processATFile(BufferedReader reader) throws IOException {
        while (reader.ready()) {
            processLine(reader.readLine());
        }
    }

    private void processLine(String input) {
        Iterator<String> spl = Arrays.asList(input.split("#")).iterator();
        String line = spl.hasNext() ? spl.next().trim() : "";
        if (line.isEmpty()) {
            return;
        }
        List<String> parts = Arrays.asList(line.split(" "));
        if (parts.size() > 3) {
            throw new RuntimeException("Invalid config file line " + input);
        }
        Modifier m = new Modifier();
        m.setTargetAccess(parts.get(0));

        if (parts.size() == 2) {
            m.modifyClassVisibility = true;
        } else {
            String nameReference = parts.get(2);
            int parenIdx = nameReference.indexOf('(');
            if (parenIdx > 0) {
                m.desc = nameReference.substring(parenIdx);
                m.name = nameReference.substring(0, parenIdx);
            } else {
                m.name = nameReference;
            }
        }
        String className = parts.get(1).replace('/', '.');
        modifiers.computeIfAbsent(className, k -> new ArrayList<>(4)).add(m);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (!modifiers.containsKey(transformedName)) {
            return bytes;
        }

        ClassNode classNode = new ClassNode();
        ClassReader classReader = new ClassReader(bytes);
        classReader.accept(classNode, 0);

        Collection<Modifier> mods = modifiers.get(transformedName);
        for (Modifier m : mods) {
            if (m.modifyClassVisibility) {
                classNode.access = getFixedAccess(classNode.access, m);
                // if this is an inner class, also modify the access flags on the corresponding InnerClasses attribute
                for (InnerClassNode innerClass : classNode.innerClasses) {
                    if (innerClass.name.equals(classNode.name)) {
                        innerClass.access = getFixedAccess(innerClass.access, m);
                        break;
                    }
                }
                continue;
            }
            if (m.desc.isEmpty()) {
                for (FieldNode n : classNode.fields) {
                    if (n.name.equals(m.name) || m.name.equals("*")) {
                        n.access = getFixedAccess(n.access, m);
                        if (!m.name.equals("*")) {
                            break;
                        }
                    }
                }
            } else {
                List<MethodNode> nowOverrideable = new ArrayList<>(4);
                for (MethodNode n : classNode.methods) {
                    if ((n.name.equals(m.name) && n.desc.equals(m.desc)) || m.name.equals("*")) {
                        n.access = getFixedAccess(n.access, m);

                        // constructors always use INVOKESPECIAL
                        if (!n.name.equals("<init>")) {
                            // if we changed from private to something else we need to replace all INVOKESPECIAL calls to this method with INVOKEVIRTUAL
                            // so that overridden methods will be called. Only need to scan this class, because obviously the method was private.
                            boolean wasPrivate = (m.oldAccess & ACC_PRIVATE) == ACC_PRIVATE;
                            boolean isNowPrivate = (m.newAccess & ACC_PRIVATE) == ACC_PRIVATE;

                            if (wasPrivate && !isNowPrivate) {
                                nowOverrideable.add(n);
                            }
                        }

                        if (!m.name.equals("*")) {
                            break;
                        }
                    }
                }

                if (!nowOverrideable.isEmpty()) {
                    replaceInvokeSpecial(classNode, nowOverrideable);
                }
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private void replaceInvokeSpecial(ClassNode clazz, List<MethodNode> toReplace) {
        for (MethodNode method : (List<MethodNode>) clazz.methods) {
            for (Iterator<AbstractInsnNode> it = method.instructions.iterator(); it.hasNext(); ) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == INVOKESPECIAL) {
                    MethodInsnNode mInsn = (MethodInsnNode) insn;
                    for (int i = 0, toReplaceSize = toReplace.size(); i < toReplaceSize; i++) {
                        MethodNode n = toReplace.get(i);
                        if (n.name.equals(mInsn.name) && n.desc.equals(mInsn.desc)) {
                            mInsn.setOpcode(INVOKEVIRTUAL);
                            break;
                        }
                    }
                }
            }
        }
    }

    private int getFixedAccess(int access, Modifier target) {
        target.oldAccess = access;
        int t = target.targetAccess;
        int ret = (access & ~7);

        switch (access & 7) {
            case ACC_PRIVATE:
                ret |= t;
                break;
            case 0: // default
                ret |= (t != ACC_PRIVATE ? t : 0 /* default */);
                break;
            case ACC_PROTECTED:
                ret |= (t != ACC_PRIVATE && t != 0 /* default */ ? t : ACC_PROTECTED);
                break;
            case ACC_PUBLIC:
                ret |= (t != ACC_PRIVATE && t != 0 /* default */ && t != ACC_PROTECTED ? t : ACC_PUBLIC);
                break;
            default:
                throw new RuntimeException("The fuck?");
        }

        // Clear the "final" marker on fields only if specified in control field
        if (target.changeFinal) {
            if (target.markFinal) {
                ret |= ACC_FINAL;
            } else {
                ret &= ~ACC_FINAL;
            }
        }
        target.newAccess = ret;
        return ret;
    }
}