/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.WorldEntity;

/**
 * Which of the field's characters and towers a seat's player sees: the one rule every part of the
 * observation that names an entity asks, so a change of what the screen shows is a change here
 * alone. The entity rows, the overflow count, the targets a row names and the buildings the deploy
 * mask is built on all go through {@link #sees}.
 *
 * <p>The rule today: a seat sees its own side's entities, every tower, and every enemy character
 * but one that is hidden, invisible or underground as the battle core's targeting tests answer.
 * Those tests are the core's, not the client's drawing; which of those states the game's client
 * still draws for an enemy is not settled yet, and until it is the rule leaves them all out.
 * Projectiles and area effects are always shown.
 */
final class SeatView {

  private SeatView() {
    // Static rule
  }

  /**
   * Whether a seat's player sees a character or tower.
   *
   * @param seat the seat, 0 or 1
   * @param world the character or tower
   */
  static boolean sees(int seat, WorldEntity world) {
    if (world.side() == seat || !(world instanceof CharacterEntity character)) {
      return true;
    }
    return !character.hidden() && !character.invisible() && !character.underground();
  }
}
