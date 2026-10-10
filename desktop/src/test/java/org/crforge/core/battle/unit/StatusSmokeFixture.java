/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import org.crforge.desktop.battle.BattleSession;

/** Creates real core buffs and a real Clone for the opt-in desktop graphics checks. */
public final class StatusSmokeFixture {
  private StatusSmokeFixture() {}

  public static CharacterEntity populate(BattleSession session) {
    var battle = session.getBattle();
    var records = battle.getWorld().getRecords();
    CharacterEntity stunned = battle.deploy(0, records.unit("Knight"), 11, 0, 4500, 12500);
    CharacterEntity frozen = battle.deploy(0, records.unit("MiniPekka"), 11, 1, 13500, 21000);
    CharacterEntity original = battle.deploy(0, records.unit("Musketeer"), 11, 0, 12000, 14500);
    for (CharacterEntity unit : new CharacterEntity[] {stunned, frozen, original}) {
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    for (int i = 0; i < 25; i++) session.step();
    battle.placeAreaEffect(25, "Clone", 11, 0, 12000, 14500, "clone spell");
    for (int i = 0; i < 16; i++) session.step();
    var clone =
        battle.getBattle().getHolder().entities().stream()
            .filter(entity -> entity instanceof CharacterEntity unit && unit.isClone())
            .map(entity -> (CharacterEntity) entity)
            .findFirst()
            .orElseThrow();
    clone.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    original.setActive(CharacterEntity.MOVEMENT_SLOT, true);
    for (int i = 0; i < 55; i++) session.step();
    original.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    stunned.getBuffs().apply(records.buff("ZapFreeze"), 5000, 10, frozen, 1);
    frozen.getBuffs().apply(records.buff("Freeze"), 5000, 10, stunned, 0);
    clone.getBuffs().apply(records.buff("Freeze"), 5000, 10, frozen, 1);
    clone.getBuffs().apply(records.buff("ZapFreeze"), 3000, 10, frozen, 1);
    return clone;
  }
}
