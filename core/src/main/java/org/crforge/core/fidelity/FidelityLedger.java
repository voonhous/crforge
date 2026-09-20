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
 * Collects {@link Fidelity} declarations from the compiled classes and renders them as a report.
 *
 * <p>Run it with {@code ./gradlew :core:fidelityReport}, or with {@code --unassessed} to also list
 * the classes nobody has looked at yet.
 *
 * <p>Every class is in scope and starts {@link FidelityStatus#UNASSESSED}, so the ledger cannot
 * miss anything by being told about the wrong set of classes. The number worth watching early is
 * how many classes are established, not what fraction: the denominator still counts plumbing that
 * will never need tracing, so a percentage would be misleading until that is separated out.
 *
 * <p>Classes are discovered by walking the compiled output rather than a hand-maintained list, so a
 * new class cannot be left out by forgetting to register it.
 */
public final class FidelityLedger {

  /** The package the ledger reports on. */
  public static final String REPORTED_PACKAGE = "org.crforge.core";

  /** The ledger's own package, excluded because the tool does not report on itself. */
  private static final String OWN_PACKAGE = "org.crforge.core.fidelity";

  private FidelityLedger() {
    // Utility class
  }

  /** One class's declared fidelity. */
  public record Entry(String className, FidelityStatus status, String note) {

    /** Simple name, for a report that is readable without the package noise. */
    public String simpleName() {
      return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Whether someone has looked at this class and recorded a status. */
    public boolean isAssessed() {
      return status != FidelityStatus.UNASSESSED;
    }
  }

  /**
   * Scans {@link #REPORTED_PACKAGE} and returns one entry per class, sorted by status then name.
   * Unannotated classes come back as {@link FidelityStatus#UNASSESSED} rather than being omitted,
   * so the report shows the gap instead of hiding it.
   */
  public static List<Entry> scan() {
    List<Entry> entries = new ArrayList<>();
    for (String className : reportedClassNames()) {
      Fidelity declared = load(className).getDeclaredAnnotation(Fidelity.class);
      entries.add(
          declared == null
              ? new Entry(className, FidelityStatus.UNASSESSED, "")
              : new Entry(className, declared.status(), declared.note()));
    }
    entries.sort(Comparator.comparing(Entry::status).thenComparing(Entry::className));
    return entries;
  }

  /**
   * Entries claiming {@link FidelityStatus#TRACED} or {@link FidelityStatus#PARTIAL} without a
   * note. Those are the two statuses someone could build on, so an unexplained one is treated as a
   * build failure; a bare {@link FidelityStatus#GUESS} needs no justification.
   */
  public static List<Entry> unjustified(List<Entry> entries) {
    return entries.stream()
        .filter(
            entry ->
                entry.status() == FidelityStatus.TRACED || entry.status() == FidelityStatus.PARTIAL)
        .filter(entry -> entry.note().isBlank())
        .toList();
  }

  /**
   * Renders the counts followed by the assessed classes. Unassessed classes are a count only unless
   * {@code listUnassessed} is set, because listing every unexamined class buries the entries that
   * someone has actually recorded something about.
   */
  public static String render(List<Entry> entries, boolean listUnassessed) {
    Map<FidelityStatus, List<Entry>> byStatus = new EnumMap<>(FidelityStatus.class);
    for (Entry entry : entries) {
      byStatus.computeIfAbsent(entry.status(), status -> new ArrayList<>()).add(entry);
    }

    StringBuilder report = new StringBuilder("Fidelity ledger -- " + REPORTED_PACKAGE + "\n\n");
    for (FidelityStatus status : FidelityStatus.values()) {
      report.append(
          String.format("  %-11s %3d%n", status, byStatus.getOrDefault(status, List.of()).size()));
    }
    report.append(String.format("  %-11s %3d%n", "total", entries.size()));

    for (FidelityStatus status : FidelityStatus.values()) {
      List<Entry> inStatus = byStatus.getOrDefault(status, List.of());
      if (inStatus.isEmpty() || (status == FidelityStatus.UNASSESSED && !listUnassessed)) {
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
    boolean listUnassessed = List.of(args).contains("--unassessed");
    System.out.print(render(scan(), listUnassessed));
  }

  /** Binary names of every reported class in the compiled output, excluding nested classes. */
  private static List<String> reportedClassNames() {
    Path root = compiledClassesRoot();
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(path -> path.toString().endsWith(".class"))
          .map(path -> toBinaryName(root, path))
          .filter(name -> name.startsWith(REPORTED_PACKAGE + "."))
          .filter(name -> !name.startsWith(OWN_PACKAGE + "."))
          // Nested and anonymous classes are covered by their enclosing class's declaration
          .filter(name -> name.indexOf('$') < 0)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk compiled classes under " + root, e);
    }
  }

  /**
   * Loads a class without initializing it. The ledger only reads annotations, and running static
   * initializers for every class just to report on them would be a needless side effect.
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
