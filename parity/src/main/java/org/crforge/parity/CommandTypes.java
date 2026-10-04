package org.crforge.parity;

import java.util.Map;
import java.util.Optional;

/**
 * The command type numbers of one data version's replays: which {@code ct} is a card play and which
 * an ability command. The numbers are a protocol detail of each game version, so a version is
 * listed here only once its replays have established them; the commands of any other version are
 * reported unsupported rather than read by another version's numbers.
 *
 * @param dataVersion the data version, as the game tables name it ({@code GameTables.version()})
 * @param play the command type of a card play
 * @param ability the command type of an ability command, the tap on a champion's button
 */
public record CommandTypes(String dataVersion, int play, int ability) {

  /** The command types of each data version whose replays have established them. */
  private static final Map<String, CommandTypes> BY_VERSION =
      Map.of(
          "14.593.1",
          new CommandTypes("14.593.1", 124, 178),
          // The game client of this data version numbers its commands anew: 124 and 178 are no
          // longer a play and an ability command, and are refused in its replays.
          "16.402.18",
          new CommandTypes("16.402.18", 153, 189));

  /**
   * The command types of a data version.
   *
   * @param dataVersion the data version, or null
   * @return its command types, or empty when they are not established
   */
  public static Optional<CommandTypes> of(String dataVersion) {
    return dataVersion == null
        ? Optional.empty()
        : Optional.ofNullable(BY_VERSION.get(dataVersion));
  }

  /**
   * What a command type is in this version.
   *
   * @return "a card play", "an ability command", or null for a type this version does not map
   */
  public String describe(int type) {
    if (type == play) {
      return "a card play";
    }
    if (type == ability) {
      return "an ability command";
    }
    return null;
  }
}
