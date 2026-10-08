package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps core's main code to the battle core: the battle package, the pathfinding rules it runs, the
 * fidelity notes and the shared utilities, with nothing beside them.
 *
 * <p>The battle core only holds behaviour that can be pointed at. A second engine growing beside it
 * would again hold inferred behaviour next to the settled rules, and a single import from one into
 * the other would let it leak into a class that claims to be settled. A new package under core's
 * main code fails here, so adding one is a decision made in this list rather than by accident.
 */
class BattlePackageDependencyTest {

  private static final Path SOURCE_ROOT = Path.of("src/main/java/org/crforge/core");

  /** Core's root package. */
  private static final String ROOT = "org.crforge.core";

  /** The packages core's main code may hold, each with its subpackages. */
  private static final List<String> PACKAGES = List.of("battle", "pathfinding", "fidelity", "util");

  /** The same packages as references from this code base. */
  private static final List<String> ALLOWED =
      PACKAGES.stream().map(name -> ROOT + "." + name).toList();

  /** Any reference to this code base, in an import or spelled out in the body or the docs. */
  private static final Pattern REFERENCE = Pattern.compile("org\\.crforge\\.[a-z]+(\\.[a-z]+)*");

  /** A source file's package declaration. */
  private static final Pattern PACKAGE =
      Pattern.compile("^package (org\\.crforge\\.[a-z.]+);", Pattern.MULTILINE);

  @Test
  @DisplayName("core's main code holds only the battle, pathfinding, fidelity and util packages")
  void coreHoldsOnlyTheBattleCorePackages() throws IOException {
    List<String> outside = new ArrayList<>();
    for (Path file : sourceFiles(SOURCE_ROOT)) {
      Path relative = SOURCE_ROOT.relativize(file);
      Matcher declared = PACKAGE.matcher(Files.readString(file));
      String packageName = declared.find() ? declared.group(1) : "(none)";
      if (relative.getNameCount() < 2
          || !PACKAGES.contains(relative.getName(0).toString())
          || !allowed(packageName)) {
        outside.add(relative + " in package " + packageName);
      }
    }
    assertThat(outside).isEmpty();
  }

  @Test
  @DisplayName("core's main code refers to nothing outside those packages")
  void coreRefersOnlyToTheBattleCorePackages() throws IOException {
    List<String> found = new ArrayList<>();
    for (Path file : sourceFiles(SOURCE_ROOT)) {
      Matcher matcher = REFERENCE.matcher(Files.readString(file));
      while (matcher.find()) {
        String reference = matcher.group();
        if (!allowed(reference)) {
          found.add(SOURCE_ROOT.relativize(file) + " -> " + reference);
        }
      }
    }
    assertThat(found).isEmpty();
  }

  /**
   * Whether a package or a reference lies in one of the allowed packages or below it. The root
   * package itself names no code of its own; the fidelity ledger scans from it.
   */
  private static boolean allowed(String name) {
    return name.equals(ROOT)
        || ALLOWED.stream().anyMatch(p -> name.equals(p) || name.startsWith(p + "."));
  }

  /** Every Java source file under a folder. */
  private static List<Path> sourceFiles(Path folder) throws IOException {
    try (Stream<Path> files = Files.walk(folder)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }
}
