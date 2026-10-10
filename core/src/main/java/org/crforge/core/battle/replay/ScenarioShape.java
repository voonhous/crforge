/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import java.util.Arrays;

/**
 * The kind of scenario a run reads, which decides the fields it is read by ({@link ReplayFormat}).
 *
 * <p>A replay is a battle as the game client of the tables' data version records it, with every
 * field that version's replays write. A generated case is a battle written for the tests in version
 * 14.593.1's replay shape, whatever the version it is played on: only the command types are the
 * version's, and the fields a later version's replays add (such as each player's king level) are
 * left out, which the battle reads as the values a generated case is established on.
 *
 * <p>The kind is always named by the caller, by a run option or a corpus listing; it is never
 * inferred from the fields a scenario holds, so a replay that lacks a required field is still
 * refused.
 */
public enum ScenarioShape {

  /** A replay the game client of the tables' data version records. */
  REPLAY("replay"),

  /** A case generated in version 14.593.1's replay shape, with the version's command types. */
  GENERATED("generated");

  private final String id;

  ScenarioShape(String id) {
    this.id = id;
  }

  /** The name a run option or a corpus listing gives the kind by. */
  public String id() {
    return id;
  }

  /**
   * The kind a name gives.
   *
   * @param id {@code replay} or {@code generated}
   * @throws IllegalArgumentException for any other name
   */
  public static ScenarioShape of(String id) {
    return Arrays.stream(values())
        .filter(shape -> shape.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("not a scenario shape: " + id));
  }
}
