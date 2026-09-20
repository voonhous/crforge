package org.crforge.core.fidelity;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Collects {@link Fidelity} declarations from the compiled simulation classes and renders them as a
 * report.
 *
 * <p>Run it with {@code ./gradlew :core:fidelityReport}. The number that matters is how many
 * classes are still {@link FidelityStatus#GUESS}: it is the size of the gap between what the
 * simulator does and what is actually established, and it should fall as behaviour is settled.
 *
 * <p>Classes are discovered by walking the compiled output rather than a hand-maintained list, so a
 * new system cannot be left out of the ledger by forgetting to register it.
 */
public final class FidelityLedger {

  /** The package the ledger reports on. */
  public static final String SIMULATION_PACKAGE = "org.crforge.core";

  private FidelityLedger() {
    // Utility class
  }

  /** One class's declared fidelity. */
  public record Entry(String className, FidelityStatus status, String note) {

    /** Simple name, for a report that is readable without the package noise. */
    public String simpleName() {
      return className.substring(className.lastIndexOf('.') + 1);
    }
  }

  /**
   * Whether a class is expected to carry a {@link Fidelity} declaration. Systems and services hold
   * the behaviour worth tracking; data holders, components and utilities do not, so requiring an
   * annotation on them would only add noise the ledger has to be read around.
   */
  public static boolean isSimulationClass(String className) {
    String simpleName = className.substring(className.lastIndexOf('.') + 1);
    return simpleName.endsWith("System") || simpleName.endsWith("Service");
  }

  /**
   * Scans {@link #SIMULATION_PACKAGE} and returns one entry per simulation class, sorted by status
   * then name. Classes without the annotation come back as {@link FidelityStatus#UNDECLARED} rather
   * than being omitted, so the report shows the gap instead of hiding it.
   */
  public static List<Entry> scan() {
    List<Entry> entries = new ArrayList<>();
    for (String className : simulationClassNames()) {
      Class<?> type = load(className);
      Fidelity declared = type.getDeclaredAnnotation(Fidelity.class);
      entries.add(
          declared == null
              ? new Entry(className, FidelityStatus.UNDECLARED, "")
              : new Entry(className, declared.status(), declared.note()));
    }
    entries.sort(Comparator.comparing(Entry::status).thenComparing(Entry::className));
    return entries;
  }

  /** Renders the counts followed by the classes in each status. */
  public static String render(List<Entry> entries) {
    Map<FidelityStatus, List<Entry>> byStatus = new EnumMap<>(FidelityStatus.class);
    for (Entry entry : entries) {
      byStatus.computeIfAbsent(entry.status(), status -> new ArrayList<>()).add(entry);
    }

    StringBuilder report = new StringBuilder("Fidelity ledger -- " + SIMULATION_PACKAGE + "\n\n");
    for (FidelityStatus status : FidelityStatus.values()) {
      report.append(
          String.format("  %-11s %3d%n", status, byStatus.getOrDefault(status, List.of()).size()));
    }
    report.append(String.format("  %-11s %3d%n", "total", entries.size()));

    for (FidelityStatus status : FidelityStatus.values()) {
      List<Entry> inStatus = byStatus.getOrDefault(status, List.of());
      if (inStatus.isEmpty()) {
        continue;
      }
      report.append("\n").append(status).append("\n");
      for (Entry entry : inStatus) {
        report.append("  ").append(entry.simpleName());
        if (!entry.note().isEmpty()) {
          report.append(" -- ").append(entry.note());
        }
        report.append("\n");
      }
    }
    return report.toString();
  }

  /** Prints the report. Entry point for the {@code fidelityReport} Gradle task. */
  public static void main(String[] args) {
    System.out.print(render(scan()));
  }

  /** Binary names of every simulation class in the compiled output, excluding nested classes. */
  private static List<String> simulationClassNames() {
    Path root = compiledClassesRoot();
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(path -> path.toString().endsWith(".class"))
          .map(path -> toBinaryName(root, path))
          .filter(name -> name.startsWith(SIMULATION_PACKAGE + "."))
          // Nested and anonymous classes are covered by their enclosing class's declaration
          .filter(name -> name.indexOf('$') < 0)
          .filter(FidelityLedger::isSimulationClass)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk compiled classes under " + root, e);
    }
  }

  /**
   * Loads a class without initializing it. The ledger only reads annotations, and running static
   * initializers for every system just to report on them would be a needless side effect.
   */
  private static Class<?> load(String className) {
    try {
      return Class.forName(className, false, FidelityLedger.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Compiled class could not be loaded: " + className, e);
    }
  }

  private static String toBinaryName(Path root, Path classFile) {
    String relative = root.relativize(classFile).toString();
    return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
  }

  /**
   * Directory holding this class's compiled output. The ledger is only ever run from a build or a
   * test, where the code source is an exploded directory; a packaged jar would need walking
   * differently, so fail loudly rather than silently reporting nothing.
   */
  private static Path compiledClassesRoot() {
    try {
      Path location =
          Path.of(FidelityLedger.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      checkState(
          Files.isDirectory(location),
          () -> "Fidelity ledger needs exploded classes, got: " + location);
      return location;
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Could not locate compiled classes", e);
    }
  }
}
