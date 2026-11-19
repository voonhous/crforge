/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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

  /**
   * Data version 16.402.22, served to game client 16.402.20 of client 16.402.17's range from
   * 2026-10-19 (content sha 97869d43e0745841430d11d7b4286ac0f8057363; the asset CDN labels it
   * 16.426.25).
   */
  public static final String DATA_16_402_22 = "16.402.22";

  /** Game client version 16.402.17, as a replay's capture block names it. */
  public static final String CLIENT_16_402_17 = "16.402.17";

  /**
   * The data versions of game client 16.402.17's range (docs/compatibility.md): 16.402.18, then
   * 16.402.19 from 2026-10-06, then 16.402.21 from 2026-10-09, then 16.402.22 from 2026-10-19.
   */
  public static final Set<String> CLIENT_16_402_17_DATA =
      Set.of(DATA_16_402_18, DATA_16_402_19, DATA_16_402_21, DATA_16_402_22);

  /**
   * Data version 16.635.3, served to game client 16.635.1 from 2026-10-27 (content sha
   * dae6c6cc7e9126338da0c606e47e48a9ef80462c; the client ships data 16.635.1 and updates to it).
   * Never served to a client of the 16.635.10 range, so the battle no longer plays it; the release
   * v16.635.1-range-16.635.1-16.635.9 does. The name stays as the data version whose replays
   * decided client 16.635.1's replay fields ({@code ReplayFormat}).
   */
  public static final String DATA_16_635_3 = "16.635.3";

  /**
   * Data version 16.635.8, served to game client 16.635.1 from 2026-11-03 (content sha
   * 062a11e0500c03cc041f123413b1ef1846b539a4; client 16.635.1 plays it without the optional update
   * to 16.635.7), and to clients 16.635.10 and 16.635.13, which shipped after it.
   */
  public static final String DATA_16_635_8 = "16.635.8";

  /**
   * Game client version 16.635.13, as a replay's capture block names it: the client whose rules the
   * battle follows since 2026-11-19, when the game stopped accepting clients below 16.635.10. It
   * loads the game data and plays the recorded battles of 16.635.8 as client 16.635.1 did.
   */
  public static final String CLIENT_16_635_13 = "16.635.13";

  /**
   * The data versions of game client 16.635.13's range (docs/compatibility.md): 16.635.8, the data
   * served to the range since it began.
   */
  public static final Set<String> CLIENT_16_635_13_DATA = Set.of(DATA_16_635_8);

  private GameVersions() {
    // Constants only
  }
}
