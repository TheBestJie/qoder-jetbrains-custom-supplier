import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads (and therefore links + verifies) patched classes with the real plugin/IDE classpath.
 * Run with -Xverify:all so that the JVM verifier (including the StackMapTable) is exercised.
 *
 * usage: LoadAndVerify <pluginLib> <ideaLib> <extraJar> <commaSeparatedClassNames>
 */
public final class LoadAndVerify {
    public static void main(String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        collect(Paths.get(args[0]), urls);
        collect(Paths.get(args[1]), urls);
        Path extra = Paths.get(args[2]);
        if (Files.isRegularFile(extra)) {
            urls.add(extra.toUri().toURL());
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        for (String name : args[3].split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Class.forName(trimmed, true, loader);
                System.out.println("LOADED      " + trimmed);
            } catch (VerifyError e) {
                System.out.println("VERIFYERROR " + trimmed + " -> " + e);
            } catch (LinkageError e) {
                Throwable cause = e.getCause();
                System.out.println("LINKAGE     " + trimmed + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + (cause == null ? "" : " | cause=" + cause));
            } catch (Throwable t) {
                System.out.println("OTHER       " + trimmed + " -> " + t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }

    private static void collect(Path dir, List<URL> urls) throws Exception {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".jar"))::iterator) {
                urls.add(path.toUri().toURL());
            }
        }
    }
}
