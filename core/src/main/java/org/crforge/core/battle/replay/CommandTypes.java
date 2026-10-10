/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.crforge.core.battle.data.GameVersions;

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

  /**
   * The command types of each data version whose replays have established them. The numbers are the
   * game client's: client 16.402.17's replays of data version 16.402.18 established them for every
   * data version that client runs ({@link GameVersions#CLIENT_16_402_17_DATA}).
   */
  private static final Map<String, CommandTypes> BY_VERSION = byVersion();

  /** The table of {@link #BY_VERSION}. */
  private static Map<String, CommandTypes> byVersion() {
    Map<String, CommandTypes> byVersion = new HashMap<>();
    byVersion.put(
        GameVersions.DATA_14_593_1, new CommandTypes(GameVersions.DATA_14_593_1, 124, 178));
    // Client 16.402.17 numbers its commands anew: 124 and 178 are no longer a play and an ability
    // command, and are refused in its replays.
    for (String version : GameVersions.CLIENT_16_402_17_DATA) {
      byVersion.put(version, new CommandTypes(version, 153, 189));
    }
    return Map.copyOf(byVersion);
  }

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
