package org.crforge.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;

/**
 * Where the visualizer's game tables come from, and what it says about them at startup.
 *
 * <p>The folder is named the way the battle core names it ({@link GameTables#configuredDirectory}):
 * the system property {@value GameTables#PROPERTY}, else the environment variable {@value
 * GameTables#ENVIRONMENT}; a blank value counts as none. {@code ./gradlew :desktop:run} passes the
 * Gradle property {@code crforge.gameTables}, or the variable, to the program as the system
 * property, the same way the test tasks do.
 *
 * <p>This is the second of the rules {@link DataSelection} chooses the tables by: a data version
 * asked for explicitly comes first, and the lock's data version in the data root last.
 */
public final class GameTablesSetting {

  private GameTablesSetting() {
    // Utility class
  }

  /**
   * A configured folder and the setting that named it.
   *
   * @param folder the folder
   * @param source the setting or rule it came from: the property's name or the variable's, or one
   *     of {@link DataSelection}'s rules, such as {@code --data-version 16.402.18 in the data root}
   */
  public record Configured(Path folder, String source) {}

  /**
   * Resolves the setting from the two values, the property winning over the variable.
   *
   * @param property the system property's value, or null
   * @param environment the environment variable's value, or null
   * @return the folder and where it came from, or empty when neither names one
   */
  public static Optional<Configured> resolve(String property, String environment) {
    if (property != null && !property.isBlank()) {
      return Optional.of(new Configured(Paths.get(property), GameTables.PROPERTY));
    }
    if (environment != null && !environment.isBlank()) {
      return Optional.of(new Configured(Paths.get(environment), GameTables.ENVIRONMENT));
    }
    return Optional.empty();
  }

  /** Resolves the setting of this process. */
  public static Optional<Configured> resolve() {
    return resolve(System.getProperty(GameTables.PROPERTY), System.getenv(GameTables.ENVIRONMENT));
  }

  /**
   * The startup lines about the tables loaded: the folder and the setting or rule that chose it,
   * the data version and the content hash.
   */
  public static List<String> describe(Configured configured, GameTables tables) {
    return List.of(
        "game tables: "
            + configured.folder().toAbsolutePath().normalize()
            + " (from "
            + configured.source()
            + ")",
        "data version: " + tables.version(),
        "content sha: " + tables.contentSha());
  }
}
