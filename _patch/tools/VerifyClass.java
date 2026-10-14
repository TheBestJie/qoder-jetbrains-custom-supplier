import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Dataflow verifier for patched classes.
 * usage: VerifyClass <class-file|dir> [methodName]
 */
public final class VerifyClass {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        String dumpMethod = args.length > 1 ? args[1] : null;
        List<Path> files = new java.util.ArrayList<>();
        if (Files.isDirectory(root)) {
            Files.walk(root).filter(p -> p.toString().endsWith(".class")).forEach(files::add);
        } else {
            files.add(root);
        }
        int failed = 0;
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null || mn.instructions.size() == 0) {
                    continue;
                }
                try {
                    new Analyzer<>(new BasicVerifier()).analyze(cn.name, mn);
                } catch (AnalyzerException e) {
                    failed++;
                    System.out.println("FAIL " + cn.name + "#" + mn.name + mn.desc + " -> " + e.getMessage());
                }
                if (dumpMethod != null && dumpMethod.equals(mn.name)) {
                    System.out.println("---- dump " + cn.name + "#" + mn.name + mn.desc
                            + " maxStack=" + mn.maxStack + " maxLocals=" + mn.maxLocals);
                    int index = 0;
                    for (AbstractInsnNode node = mn.instructions.getFirst(); node != null; node = node.getNext()) {
                        System.out.println("  [" + (index++) + "] " + node.getClass().getSimpleName()
                                + " op=" + node.getOpcode() + " " + node);
                    }
                }
            }
        }
        System.out.println(failed == 0 ? "VERIFY OK (" + files.size() + " classes)" : "VERIFY FAILURES: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
