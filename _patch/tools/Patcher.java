import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode;
import org.jetbrains.org.objectweb.asm.tree.FrameNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;
import org.jetbrains.org.objectweb.asm.tree.InsnNode;
import org.jetbrains.org.objectweb.asm.tree.JumpInsnNode;
import org.jetbrains.org.objectweb.asm.tree.LabelNode;
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Bytecode patcher for instrumented-qoder-core-*.jar.
 *
 * Adds CustomEndpointProvider (a new class) and redirects four existing methods to it.
 *
 * usage: Patcher <input.jar> <output.jar> <extra-classes-dir>
 */
public final class Patcher {

    private static final String HELPER = "com/alibabacloud/intellij/qoder/util/CustomEndpointProvider";

    private static final String BYOK_CONFIG_RESULT = "com/alibabacloud/intellij/qoder/core/lsp/result/ByokConfigResult";
    private static final String CUSTOM_MODEL_PARAM = "com/alibabacloud/intellij/qoder/core/lsp/params/CustomModelParam";
    private static final String ADD_MODEL_DIALOG = "com/alibabacloud/intellij/qoder/ui/components/model/AddModelDialog";
    private static final String BYOK_CONFIG_SERVICE = "com/alibabacloud/intellij/qoder/service/ByokConfigService";
    private static final String CHECK_BYOK_CONFIG_PARAM = "com/alibabacloud/intellij/qoder/core/lsp/params/CheckByokConfigParam";
    private static final String CHECK_BYOK_CONFIG_RESULT = "com/alibabacloud/intellij/qoder/core/lsp/result/CheckByokConfigResult";
    private static final String BYOK_PROVIDER_TYPE = "com/alibabacloud/intellij/qoder/core/lsp/result/ByokProviderType";
    private static final String CHAT_MODEL_CLASS_CHOOSER = "com/alibabacloud/intellij/qoder/ui/chat/component/chooser/ChatModelClassChooser";
    private static final String CHAT_MODEL_CLASS_RESULT = "com/alibabacloud/intellij/qoder/core/lsp/result/ChatModelClassResult";
    private static final String CUSTOM_MODEL_ITEM = "com/alibabacloud/intellij/qoder/ui/components/model/CustomModelItem";
    private static final String CHAT_MODEL_CLASS_ITEM = "com/alibabacloud/intellij/qoder/ui/components/model/ChatModelClassItem";
    private static final String MODEL_CLASS_OPTION_UTIL = "com/alibabacloud/intellij/qoder/util/ModelClassOptionUtil";
    private static final String MODEL_CLASS_OPTION_SELECTION = "com/alibabacloud/intellij/qoder/common/ModelClassOptionSelection";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: Patcher <input.jar> <output.jar> <extra-classes-dir>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Path extra = Paths.get(args[2]);

        int patched = 0;
        try (ZipFile zip = new ZipFile(in.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipEntry copy = new ZipEntry(entry.getName());
                // keep the original timestamps so that rebuilds are byte identical
                copy.setTime(entry.getTime());
                zos.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    byte[] data = readAll(zip.getInputStream(entry));
                    byte[] result = transform(entry.getName(), data);
                    if (result != data) {
                        patched++;
                    }
                    zos.write(result);
                }
                zos.closeEntry();
            }

            // append the freshly compiled helper classes
            Path helperRoot = extra.resolve("com/alibabacloud/intellij/qoder/util");
            if (!Files.isDirectory(helperRoot)) {
                throw new IllegalStateException("helper classes not found in " + helperRoot);
            }
            try (var stream = Files.list(helperRoot)) {
                List<Path> files = new ArrayList<>();
                stream.filter(p -> p.getFileName().toString().endsWith(".class")).forEach(files::add);
                for (Path file : files) {
                    String name = "com/alibabacloud/intellij/qoder/util/" + file.getFileName();
                    ZipEntry helperEntry = new ZipEntry(name);
                    helperEntry.setTime(Files.getLastModifiedTime(file).toMillis());
                    zos.putNextEntry(helperEntry);
                    zos.write(Files.readAllBytes(file));
                    zos.closeEntry();
                    System.out.println("added    " + name);
                }
            }
        }

        System.out.println("patched methods/classes: " + patched);
        System.out.println("output: " + out.toAbsolutePath());
    }

    private static byte[] transform(String entryName, byte[] data) {
        try {
            switch (entryName) {
                case BYOK_CONFIG_RESULT + ".class":
                    return patchByokConfigResult(data);
                case CUSTOM_MODEL_PARAM + ".class":
                    return patchCustomModelParam(data);
                case ADD_MODEL_DIALOG + ".class":
                    return patchAddModelDialog(data);
                case BYOK_CONFIG_SERVICE + ".class":
                    return patchByokConfigService(data);
                case CHAT_MODEL_CLASS_CHOOSER + ".class":
                    return patchChatModelClassChooser(data);
                case MODEL_CLASS_OPTION_UTIL + ".class":
                    return patchModelClassOptionUtil(data);
                default:
                    return data;
            }
        } catch (Throwable t) {
            throw new IllegalStateException("failed to patch " + entryName + ": " + t, t);
        }
    }

    // ------------------------------------------------------------------
    // 1. ByokConfigResult.getProviders() -> CustomEndpointProvider.augment(...)
    // ------------------------------------------------------------------
    private static byte[] patchByokConfigResult(byte[] data) {
        ClassNode cn = read(data);
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("getProviders".equals(mn.name) && "()Ljava/util/List;".equals(mn.desc)) {
                target = mn;
            }
        }
        if (target == null) {
            throw new IllegalStateException("getProviders() not found");
        }
        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;
        target.visibleLocalVariableAnnotations = null;
        target.invisibleLocalVariableAnnotations = null;

        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new FieldInsnNode(Opcodes.GETFIELD, BYOK_CONFIG_RESULT, "providers", "Ljava/util/List;"));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "augment",
                "(Ljava/util/List;)Ljava/util/List;", false));
        list.add(new InsnNode(Opcodes.ARETURN));
        target.instructions.add(list);
        System.out.println("patched  " + BYOK_CONFIG_RESULT + "#getProviders");
        return write(cn);
    }

    // ------------------------------------------------------------------
    // 2. CustomModelParam.<init> prologue: url / format / source / type
    // ------------------------------------------------------------------
    private static byte[] patchCustomModelParam(byte[] data) {
        String ctorDesc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Boolean;Ljava/lang/Boolean;"
                + "Ljava/lang/Integer;Ljava/util/Map;Ljava/lang/String;Ljava/lang/String;"
                + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
        ClassNode cn = read(data);
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name) && ctorDesc.equals(mn.desc)) {
                target = mn;
            }
        }
        if (target == null) {
            throw new IllegalStateException("CustomModelParam 11-arg constructor not found");
        }

        // insert right after the super() call
        AbstractInsnNode anchor = null;
        for (AbstractInsnNode node = target.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode m = (MethodInsnNode) node;
                if ("java/lang/Object".equals(m.owner) && "<init>".equals(m.name)) {
                    anchor = node;
                    break;
                }
            }
        }
        if (anchor == null) {
            throw new IllegalStateException("super() call not found in CustomModelParam.<init>");
        }

        LabelNode skip = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isCustomProvider",
                "(Ljava/lang/String;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        // url = resolveUrl(provider, model, parameters)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 6));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveUrl",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)Ljava/lang/String;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 10));
        // format = resolveFormat(type)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 7));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveFormat",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 8));
        // source = resolveSource()
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveSource",
                "()Ljava/lang/String;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 9));
        // type = resolveType(type)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 7));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveType",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 7));
        // isVl = resolveVl(provider, model, isVl)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 3));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveVl",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Boolean;)Ljava/lang/Boolean;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 3));
        // maxInputTokens = resolveMaxInputTokens(provider, model, maxInputTokens)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 5));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveMaxInputTokens",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/Integer;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 5));
        // reasoningEffort = resolveReasoningEffort(provider, model, reasoningEffort)
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 11));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "resolveReasoningEffort",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false));
        pre.add(new VarInsnNode(Opcodes.ASTORE, 11));
        pre.add(skip);

        target.instructions.insert(anchor, pre);

        // log the finished payload right before the constructor returns (only for our provider)
        AbstractInsnNode lastReturn = null;
        for (AbstractInsnNode node = target.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node.getOpcode() == Opcodes.RETURN) {
                lastReturn = node;
            }
        }
        if (lastReturn != null) {
            InsnList tail = new InsnList();
            tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "logCustomModelParam",
                    "(L" + CUSTOM_MODEL_PARAM + ";)V", false));
            target.instructions.insertBefore(lastReturn, tail);
        }

        System.out.println("patched  " + CUSTOM_MODEL_PARAM
                + "#<init> (url/format/source/type/is_vl/max_input_tokens/reasoning_effort + payload log)");
        return write(cn);
    }

    // ------------------------------------------------------------------
    // 3. AddModelDialog.updateModelCheckBoxes: always offer "Input Model Name"
    // ------------------------------------------------------------------
    private static byte[] patchAddModelDialog(byte[] data) {
        ClassNode cn = read(data);
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("updateModelCheckBoxes".equals(mn.name)
                    && ("(L" + BYOK_PROVIDER_TYPE + ";)V").equals(mn.desc)) {
                target = mn;
            }
        }
        if (target == null) {
            throw new IllegalStateException("AddModelDialog.updateModelCheckBoxes not found");
        }

        List<AbstractInsnNode> real = realInstructions(target);
        int isEmptyIndex = -1;
        for (int i = 0; i < real.size(); i++) {
            AbstractInsnNode node = real.get(i);
            if (node instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) node;
                if ("java/util/Collection".equals(m.owner) && "isEmpty".equals(m.name)
                        && "()Z".equals(m.desc)) {
                    isEmptyIndex = i;
                    break;
                }
            }
        }
        if (isEmptyIndex < 2 || isEmptyIndex + 5 >= real.size()) {
            throw new IllegalStateException("unexpected updateModelCheckBoxes shape (isEmpty at "
                    + isEmptyIndex + ")");
        }

        AbstractInsnNode aloadModels = real.get(isEmptyIndex - 2);
        AbstractInsnNode checkcast = real.get(isEmptyIndex - 1);
        AbstractInsnNode ifne = real.get(isEmptyIndex + 1);
        AbstractInsnNode iconst1 = real.get(isEmptyIndex + 2);
        AbstractInsnNode gotoNode = real.get(isEmptyIndex + 3);
        AbstractInsnNode iconst0 = real.get(isEmptyIndex + 4);
        AbstractInsnNode ifeq = real.get(isEmptyIndex + 5);

        if (aloadModels.getOpcode() != Opcodes.ALOAD
                || !(checkcast instanceof TypeInsnNode)
                || ifne.getOpcode() != Opcodes.IFNE
                || iconst1.getOpcode() != Opcodes.ICONST_1
                || gotoNode.getOpcode() != Opcodes.GOTO
                || iconst0.getOpcode() != Opcodes.ICONST_0
                || ifeq.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException("updateModelCheckBoxes shape mismatch, refusing to patch");
        }

        // locate "supportsFreeFormModelName" and the jump that follows it
        MethodInsnNode supports = null;
        for (AbstractInsnNode node = target.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) node;
                if ("supportsFreeFormModelName".equals(m.name)) {
                    supports = m;
                    break;
                }
            }
        }
        if (supports == null) {
            throw new IllegalStateException("supportsFreeFormModelName call not found");
        }
        AbstractInsnNode afterSupports = nextReal(supports);
        if (afterSupports == null || afterSupports.getOpcode() != Opcodes.IFEQ) {
            throw new IllegalStateException("unexpected code after supportsFreeFormModelName");
        }
        AbstractInsnNode addBlockStart = nextReal(afterSupports);
        if (addBlockStart == null) {
            throw new IllegalStateException("add block not found");
        }
        AbstractInsnNode beforeSupports = prevReal(supports);

        InsnList insns = target.instructions;

        // 1) drop "!models.isEmpty()"
        for (int i = isEmptyIndex - 2; i <= isEmptyIndex + 5; i++) {
            insns.remove(real.get(i));
        }

        // 2) custom provider -> jump straight to the add block
        //    NB: InsnList.insert(location, insns) inserts AFTER location, so the label has to be
        //    placed with insertBefore to stay on a basic block boundary (empty stack).
        LabelNode addLabel = new LabelNode();
        insns.insertBefore(addBlockStart, addLabel);

        LabelNode nonCustom = new LabelNode();
        InsnList prologue = new InsnList();
        prologue.add(new VarInsnNode(Opcodes.ALOAD, 3));
        prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isCustomProvider",
                "(Ljava/lang/String;)Z", false));
        prologue.add(new JumpInsnNode(Opcodes.IFEQ, nonCustom));
        prologue.add(new JumpInsnNode(Opcodes.GOTO, addLabel));
        prologue.add(nonCustom);
        insns.insertBefore(beforeSupports, prologue);

        System.out.println("patched  " + ADD_MODEL_DIALOG + "#updateModelCheckBoxes (free form model name)");
        return write(cn);
    }

    // ------------------------------------------------------------------
    // 4. ByokConfigService.checkByokConfig: local check for the custom provider
    // ------------------------------------------------------------------
    private static byte[] patchByokConfigService(byte[] data) {
        ClassNode cn = read(data);
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("checkByokConfig".equals(mn.name)
                    && ("(L" + CHECK_BYOK_CONFIG_PARAM + ";)L" + CHECK_BYOK_CONFIG_RESULT + ";").equals(mn.desc)) {
                target = mn;
            }
        }
        if (target == null) {
            throw new IllegalStateException("ByokConfigService.checkByokConfig not found");
        }

        LabelNode original = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isCustomCheck",
                "(L" + CHECK_BYOK_CONFIG_PARAM + ";)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, original));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "localCheck",
                "(L" + CHECK_BYOK_CONFIG_PARAM + ";)L" + CHECK_BYOK_CONFIG_RESULT + ";", false));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(original);

        target.instructions.insert(pre);
        System.out.println("patched  " + BYOK_CONFIG_SERVICE + "#checkByokConfig (local check)");
        return write(cn);
    }

    // ------------------------------------------------------------------
    // 5. ChatModelClassChooser.buildChatModelClassItemFromConfig:
    //    give local custom models the runtime option template (context / thinking / modality)
    // ------------------------------------------------------------------
    private static byte[] patchChatModelClassChooser(byte[] data) {
        ClassNode cn = read(data);
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("buildChatModelClassItemFromConfig".equals(mn.name)
                    && mn.desc.startsWith("(L" + CUSTOM_MODEL_ITEM + ";")) {
                target = mn;
            }
        }
        if (target == null) {
            throw new IllegalStateException("ChatModelClassChooser.buildChatModelClassItemFromConfig not found");
        }

        // locate "result = new ChatModelClassResult()" and the local slot it is stored in
        int resultSlot = -1;
        AbstractInsnNode newItemNode = null;
        for (AbstractInsnNode node = target.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node.getOpcode() == Opcodes.NEW && node instanceof TypeInsnNode) {
                String type = ((TypeInsnNode) node).desc;
                if (CHAT_MODEL_CLASS_RESULT.equals(type) && resultSlot < 0) {
                    AbstractInsnNode next = nextReal(node);
                    while (next != null && next.getOpcode() != Opcodes.ASTORE) {
                        next = nextReal(next);
                    }
                    if (next instanceof VarInsnNode) {
                        resultSlot = ((VarInsnNode) next).var;
                    }
                } else if (CHAT_MODEL_CLASS_ITEM.equals(type) && newItemNode == null) {
                    newItemNode = node;
                }
            }
        }
        if (resultSlot < 0) {
            throw new IllegalStateException("ChatModelClassResult local slot not found");
        }
        if (newItemNode == null) {
            throw new IllegalStateException("new ChatModelClassItem not found");
        }

        InsnList insns = new InsnList();
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "fillCustomModelMeta",
                "(L" + CHAT_MODEL_CLASS_RESULT + ";L" + CUSTOM_MODEL_ITEM + ";)V", false));
        target.instructions.insertBefore(newItemNode, insns);

        System.out.println("patched  " + CHAT_MODEL_CLASS_CHOOSER
                + "#buildChatModelClassItemFromConfig (custom model option templates)");
        return write(cn);
    }

    // ------------------------------------------------------------------
    // 6. ModelClassOptionUtil.resolveReasoningEffort:
    //    do not send "none" for custom models while thinking is switched off
    // ------------------------------------------------------------------
    private static byte[] patchModelClassOptionUtil(byte[] data) {
        ClassNode cn = read(data);
        String desc = "(L" + CHAT_MODEL_CLASS_RESULT + ";L" + MODEL_CLASS_OPTION_SELECTION + ";)Ljava/lang/String;";
        MethodNode target = null;
        for (MethodNode mn : cn.methods) {
            if ("resolveReasoningEffort".equals(mn.name) && desc.equals(mn.desc)) {
                target = mn;
            }
        }
        if (target == null) {
            throw new IllegalStateException("ModelClassOptionUtil.resolveReasoningEffort not found");
        }

        LabelNode original = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isCustomModelClass",
                "(L" + CHAT_MODEL_CLASS_RESULT + ";)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, original));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "reasoningEffortForCustomModel",
                desc, false));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(original);

        target.instructions.insert(pre);
        System.out.println("patched  " + MODEL_CLASS_OPTION_UTIL
                + "#resolveReasoningEffort (custom model thinking override)");
        return write(cn);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<AbstractInsnNode> realInstructions(MethodNode mn) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode node = mn.instructions.getFirst(); node != null; node = node.getNext()) {
            if (node instanceof LabelNode || node instanceof LineNumberNode || node instanceof FrameNode) {
                continue;
            }
            result.add(node);
        }
        return result;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null
                && (current instanceof LabelNode || current instanceof LineNumberNode
                || current instanceof FrameNode)) {
            current = current.getNext();
        }
        return current;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null
                && (current instanceof LabelNode || current instanceof LineNumberNode
                || current instanceof FrameNode)) {
            current = current.getPrevious();
        }
        return current;
    }

    private static ClassNode read(byte[] data) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(data).accept(cn, 0);
        return cn;
    }

    private static byte[] write(ClassNode cn) {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Falls back to java/lang/Object when the class hierarchy is not on our classpath. */
    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
