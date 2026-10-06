package org.crforge.core.battle.data;

import java.util.Set;

/**
 * The data versions each game client version has run. The data moves without a client update, and
 * what a replay writes, how its commands are numbered and the rules of the battle are the client's,
 * not the data's: a rule or a replay field established on one data version of a client holds on
 * every data version that client runs. The rules are still looked up by the data version, the one
 * the game tables name ({@link GameTables#version()}), through the sets here. The list of pairs is
 * {@code docs/game-versions.md}.
 *
 * <p>Kept only while 14.593.1 is the regression set: once it goes, the simulator tracks one client
 * and its version gates go with it.
 */
public final class GameClients {

  /** The data versions game client 16.402.17 has run: 16.402.18, then 16.426.22 from 2026-10-06. */
  public static final Set<String> CLIENT_16_402_17 = Set.of("16.402.18", "16.426.22");

  private GameClients() {
    // Constants only
  }
}
