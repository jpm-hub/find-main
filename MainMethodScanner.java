import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class MainMethodScanner {
    private MainMethodScanner() {}

    /**
     * Scans a JAR file for all classes that contain a public static void main(String[] args)
     * method, loading dependencies from the current working directory.
     *
     * @param jarFilePath The path to the main JAR file.
     * @return A list of fully qualified class names that have a main method.
     */
    private static List<String> findMainClasses(String jarFilePath) {
        if (jarFilePath == null || jarFilePath.isEmpty()) return new ArrayList<>();
        List<String> mainClasses = new ArrayList<>();
        File mainJarFile = new File(jarFilePath);
        if (!mainJarFile.exists() || !jarFilePath.toLowerCase().endsWith(".jar")) {
            System.err.println("Invalid JAR file path or file does not exist: " + jarFilePath);
            return mainClasses;
        }

        List<URL> classpathUrls = new ArrayList<>();
        try {
            // 1. Add the main JAR file to the classpath URLs
            classpathUrls.add(mainJarFile.toURI().toURL());

            // 2. Add jpm_dependencies directory to the classpath URLs
            File jpmDepsDir = new File(System.getProperty("user.dir"), "jpm_dependencies");
            if (jpmDepsDir.exists() && jpmDepsDir.isDirectory()) {
                classpathUrls.add(jpmDepsDir.toURI().toURL());
            }

            // 3. Scan jpm_dependencies directory for JAR files and add them
            if (jpmDepsDir.exists() && jpmDepsDir.isDirectory()) {
                File[] jpmFiles =
                        jpmDepsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                if (jpmFiles != null) {
                    for (File file : jpmFiles) {
                        if (!file.getAbsolutePath().equals(mainJarFile.getAbsolutePath())) {
                            classpathUrls.add(file.toURI().toURL());
                        }
                    }
                }
            }

            // 4. Add jpm_dependencies/execs directory to the classpath URLs
            File jpmExecsDir = new File(jpmDepsDir, "execs");
            if (jpmExecsDir.exists() && jpmExecsDir.isDirectory()) {
                classpathUrls.add(jpmExecsDir.toURI().toURL());

                // Scan jpm_dependencies/execs directory for JAR files and add them
                File[] execFiles =
                        jpmExecsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                if (execFiles != null) {
                    for (File file : execFiles) {
                        if (!file.getAbsolutePath().equals(mainJarFile.getAbsolutePath())) {
                            classpathUrls.add(file.toURI().toURL());
                        }
                    }
                }
            }

        } catch (MalformedURLException e) {
            return mainClasses;
        }

        try ( // Create a custom ClassLoader with all the collected URLs
        URLClassLoader classLoader =
                new URLClassLoader(
                        classpathUrls.toArray(new URL[0]),
                        Thread.currentThread().getContextClassLoader())) {
            try (JarFile jarFile = new JarFile(mainJarFile)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    if (entryName.endsWith(".class") && !entry.isDirectory()) {
                        String className =
                                entryName
                                        .substring(0, entryName.length() - ".class".length())
                                        .replace('/', '.');

                        try {
                            // Load the class using the custom ClassLoader that has all dependencies
                            Class<?> clazz = classLoader.loadClass(className);

                            if (hasMainMethod(clazz)) {
                                mainClasses.add(className);
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                }
            } catch (IOException e) {
                return mainClasses;
            }
        } catch (IOException e) {
            return mainClasses;
        }
        return mainClasses;
    }

    /** Uses reflection to check if a class has a public static void main(String[] args) method. */
    private static boolean hasMainMethod(final Class<?> clazz) {
        try {
            Method mainMethod = clazz.getMethod("main", String[].class);
            int modifiers = mainMethod.getModifiers();
            return !Modifier.isPrivate(modifiers) && mainMethod.getReturnType() == void.class;
        } catch (NoSuchMethodException e) {
            try {
                Method mainMethodSimple = clazz.getMethod("main", String[].class);
                int modifiersSimple = mainMethodSimple.getModifiers();
                return !Modifier.isPrivate(modifiersSimple)
                        && mainMethodSimple.getReturnType() == void.class;
            } catch (NoSuchMethodException ex) {
                return false;
            }
        }
    }

    public static void main(String[] args) {
        var exitCode = 1;
        final int oneArg = 1;
        if (args.length != oneArg)
            System.err.println("Usage: java MainMethodScanner <path-to-jar>");
        var foundClasses = findMainClasses((args.length != 1) ? "" : args[0]);
        for (final String className : foundClasses) {
            System.out.println(className);
            exitCode = 0;
        }
        System.exit(exitCode);
    }
}
