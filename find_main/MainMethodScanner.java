package find_main;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
   * Scans a JAR file for all classes that contain a public static void main(String[] args) method,
   * loading dependencies from the current working directory.
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
        File[] jpmFiles = jpmDepsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
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
            classpathUrls.toArray(new URL[0]), Thread.currentThread().getContextClassLoader())) {
      try (JarFile jarFile = new JarFile(mainJarFile)) {
        List<String> unloadable = new ArrayList<>();

        // Pass 1: reflection scan on loadable classes (cheap)
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          String entryName = entry.getName();

          if (entryName.endsWith(".class") && !entry.isDirectory()) {
            String className =
                entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');

            try {
              Class<?> clazz = classLoader.loadClass(className);
              if (hasMainMethod(clazz)) {
                mainClasses.add(className);
              }
            } catch (Throwable e) {
              unloadable.add(entryName);
            }
          }
        }

        // Pass 2: bytecode fallback only for classes that failed to load
        for (String entryName : unloadable) {
          JarEntry entry = jarFile.getJarEntry(entryName);
          if (entry == null) continue;
          String className =
              entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
          try (InputStream is = jarFile.getInputStream(entry)) {
            if (hasMainMethodInBytecode(is)) {
              mainClasses.add(className);
            }
          } catch (IOException ignored) {
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

  /**
   * Parses raw class file bytes to check for a public static void main(String[] args) method
   * without loading the class. Used as a fallback when the class cannot be loaded.
   */
  private static boolean hasMainMethodInBytecode(InputStream classBytes) throws IOException {
    DataInputStream in = new DataInputStream(classBytes);
    in.readInt(); // magic
    in.readUnsignedShort(); // minor
    in.readUnsignedShort(); // major
    int cpCount = in.readUnsignedShort();
    // constant pool entries are 1-indexed; index 0 is unused
    String[] utf8Pool = new String[cpCount];
    for (int i = 1; i < cpCount; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8Pool[i] = new String(in.readNBytes(in.readUnsignedShort()));
        case 3, 4, 9, 10, 11, 12, 18 -> in.skipBytes(4);
        case 5, 6 -> { in.skipBytes(8); i++; } // Long/Double take two slots
        case 7, 8, 16, 19, 20 -> in.skipBytes(2);
        case 15 -> in.skipBytes(3);
        default -> throw new IOException("Unknown constant pool tag: " + tag);
      }
    }
    in.readUnsignedShort(); // access_flags
    in.readUnsignedShort(); // this_class
    in.readUnsignedShort(); // super_class
    int ifaceCount = in.readUnsignedShort();
    in.skipBytes(ifaceCount * 2);
    int fieldCount = in.readUnsignedShort();
    for (int i = 0; i < fieldCount; i++) {
      in.skipBytes(6); // access_flags, name_index, descriptor_index
      int attrCount = in.readUnsignedShort();
      for (int j = 0; j < attrCount; j++) {
        in.skipBytes(2);
        in.skipBytes(in.readInt());
      }
    }
    int methodCount = in.readUnsignedShort();
    for (int i = 0; i < methodCount; i++) {
      int accessFlags = in.readUnsignedShort();
      int nameIdx = in.readUnsignedShort();
      int descIdx = in.readUnsignedShort();
      int attrCount = in.readUnsignedShort();
      for (int j = 0; j < attrCount; j++) {
        in.skipBytes(2);
        in.skipBytes(in.readInt());
      }
      boolean isNotPrivate = (accessFlags & 0x0002) == 0;
      if (isNotPrivate && "main".equals(utf8Pool[nameIdx])) {
        String desc = utf8Pool[descIdx];
        if ("([Ljava/lang/String;)V".equals(desc) || "()V".equals(desc)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Uses reflection to check if a class has a main method. */
  private static boolean hasMainMethod(final Class<?> clazz) {
    try {
      Method m = clazz.getMethod("main", String[].class);
      return !Modifier.isPrivate(m.getModifiers()) && m.getReturnType() == void.class;
    } catch (NoSuchMethodException e) {
      // Java 21+ instance main: no-arg, non-private, returns void
      try {
        Method m = clazz.getDeclaredMethod("main");
        return !Modifier.isPrivate(m.getModifiers()) && m.getReturnType() == void.class;
      } catch (NoSuchMethodException ex) {
        return false;
      }
    }
  }

  public static void main(String[] args) {
    var exitCode = 1;
    final int oneArg = 1;
    if (args.length != oneArg) System.err.println("Usage: java MainMethodScanner <path-to-jar>");
    var foundClasses = findMainClasses((args.length != 1) ? "" : args[0]);
    for (final String className : foundClasses) {
      System.out.println(className);
      exitCode = 0;
    }
    System.exit(exitCode);
  }
}
