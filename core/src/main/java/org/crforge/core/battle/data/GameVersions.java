package org.crforge.core.battle.data;

import java.util.Set;

/**
 * The game versions the simulator knows, named once: the data versions (the version the game tables
 * name, {@link GameTables#version()}) and the game client versions, and the data versions each
 * client has run. Code and tests that pick out a version use these constants rather than the
 * literal, so retiring a version is deleting its constant and fixing what no longer compiles.
 *
 * <p>The data moves without a client update, and what a replay writes, how its commands are
 * numbered and the rules of the battle are the client's, not the data's: a rule or a replay field
 * established on one data version of a client holds on every data version that client runs. The
 * battle tracks one client and has no rule keyed by the data version; the replay fields and command
 * types are still looked up by the data version, through the sets here. The list of pairs is {@code
 * docs/game-versions.md}.
 */
public final class GameVersions {

  /**
   * Data version 14.593.1, whose replay fields and command types the replay reading still names.
   */
  public static final String DATA_14_593_1 = "14.593.1";

  /** Data version 16.402.18, the first run by game client 16.402.17. */
  public static final String DATA_16_402_18 = "16.402.18";

  /**
   * Data version 16.402.19, run by game client 16.402.17 from 2026-10-06 (content sha
   * 7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec). The asset CDN labels the same data 16.426.22; it was
   * filed under that label until 2026-10-07 (docs/game-versions.md).
   */
  public static final String DATA_16_402_19 = "16.402.19";

  /**
   * Data version 16.402.21, served to game client 16.402.14 of client 16.402.17's range from
   * 2026-10-09 (content sha c99947391d7d95cc51a894935c370e4f4d14014b; the asset CDN labels it
   * 16.426.24).
   */
  public static final String DATA_16_402_21 = "16.402.21";

  /** Game client version 16.402.17, as a replay's capture block names it. */
  public static final String CLIENT_16_402_17 = "16.402.17";

  /**
   * The data versions of game client 16.402.17's range (docs/compatibility.md): 16.402.18, then
   * 16.402.19 from 2026-10-06, then 16.402.21 from 2026-10-09.
   */
  public static final Set<String> CLIENT_16_402_17_DATA =
      Set.of(DATA_16_402_18, DATA_16_402_19, DATA_16_402_21);

  private GameVersions() {
    // Constants only
  }
}
