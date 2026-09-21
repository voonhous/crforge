package org.crforge.core.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Keeps the battle package, and the pathfinding rules it runs, free of the older engine.
 *
 * <p>The two are built on different footings: the battle package only holds behaviour that can be
 * pointed at, while the older engine is inferred. A single import in the wrong direction would let
 * inferred behaviour leak into a class that claims to be settled, and nothing else would notice.
 * The older engine may depend on these packages; they may not depend on it.
 */
class BattlePackageDependencyTest {

  private static final Path SOURCE_ROOT = Path.of("src/main/java/org/crforge/core");

  /** What the battle and pathfinding packages may import from this code base. */
  private static final List<String> ALLOWED =
      List.of(
          "org.crforge.core.battle",
          "org.crforge.core.pathfinding",
          "org.crforge.core.fidelity",
          "org.crforge.core.util");

  /** Any reference to this code base, in an import or spelled out in the body or the docs. */
  private static final Pattern REFERENCE = Pattern.compile("org\\.crforge\\.[a-z]+(\\.[a-z]+)*");

  @Test
  @DisplayName("the battle package does not reach into the older engine")
  void battlePackageIsSelfContained() {
    assertThat(foreignReferences("battle")).isEmpty();
  }

  @Test
  @DisplayName("the pathfinding rules the battle runs do not reach into the older engine")
  void pathfindingRulesAreSelfContained() {
    // The grid system is the older engine's own adapter onto these rules, so it is the one class
    // here that is allowed to know both sides.
    assertThat(foreignReferences("pathfinding"))
        .allMatch(reference -> reference.startsWith("pathfinding/GridPathfindingSystem.java"));
  }

  /** Every reference from the given package to a package outside the allowed list. */
  private static List<String> foreignReferences(String packageDirectory) {
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve(packageDirectory))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        Matcher matcher = REFERENCE.matcher(Files.readString(file));
        while (matcher.find()) {
          String reference = matcher.group();
          if (ALLOWED.stream().noneMatch(reference::startsWith)) {
            found.add(SOURCE_ROOT.relativize(file) + " -> " + reference);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return found;
  }
}
