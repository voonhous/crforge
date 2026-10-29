/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.unit.UnitData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The role icons' roles, read from every troop and building row of the tables: each role follows
 * the row's own columns, and the tables reach every icon.
 */
class UnitRoleTest {

  @Test
  @DisplayName("every troop and building row's role follows its attack, spawn and elixir columns")
  void rolesFollowTheColumns() {
    for (UnitData unit : units()) {
      UnitRole role = UnitRole.of(unit);
      String name = unit.name();
      assertThat(role.buildingsOnly()).as(name).isEqualTo(unit.targetOnlyBuildings());
      switch (role.attack()) {
        case KAMIKAZE -> assertThat(unit.kamikaze()).as(name).isTrue();
        case RANGED -> {
          assertThat(unit.kamikaze()).as(name).isFalse();
          assertThat(unit.projectile() != null || unit.range() >= UnitRole.RANGED_REACH)
              .as(name)
              .isTrue();
        }
        case MELEE -> {
          assertThat(unit.projectile()).as(name).isNull();
          assertThat(unit.damage()).as(name).isPositive();
          assertThat(unit.range()).as(name).isLessThan(UnitRole.RANGED_REACH);
        }
        case NONE -> {
          assertThat(unit.projectile()).as(name).isNull();
          assertThat(unit.damage()).as(name).isZero();
          assertThat(unit.kamikaze()).as(name).isFalse();
        }
      }
      switch (role.work()) {
        case SPAWNER -> {
          assertThat(role.attack()).as(name).isEqualTo(UnitRole.Attack.NONE);
          assertThat(unit.spawnCharacter()).as(name).isNotNull();
          assertThat(unit.spawnNumber()).as(name).isPositive();
        }
        case COLLECTOR -> {
          assertThat(role.attack()).as(name).isEqualTo(UnitRole.Attack.NONE);
          assertThat(unit.manaCollectAmount()).as(name).isPositive();
        }
        case NONE ->
            assertThat(
                    role.attack() != UnitRole.Attack.NONE
                        || (unit.spawnCharacter() == null || unit.spawnNumber() <= 0)
                            && unit.manaCollectAmount() <= 0)
                .as(name)
                .isTrue();
      }
      if (unit.projectile() != null) {
        assertThat(role.splash()).as(name).isEqualTo(unit.projectile().radius() > 0);
      } else {
        assertThat(role.splash()).as(name).isEqualTo(unit.areaDamageRadius() > 0);
      }
    }
  }

  @Test
  @DisplayName("the tables reach every attack icon, every work icon and both badges")
  void everyIconIsReached() {
    Set<UnitRole.Attack> attacks = EnumSet.noneOf(UnitRole.Attack.class);
    Set<UnitRole.Work> works = EnumSet.noneOf(UnitRole.Work.class);
    boolean splash = false;
    boolean buildingsOnly = false;
    for (UnitData unit : units()) {
      UnitRole role = UnitRole.of(unit);
      attacks.add(role.attack());
      works.add(role.work());
      splash |= role.splash();
      buildingsOnly |= role.buildingsOnly();
    }
    assertThat(attacks).containsExactlyInAnyOrder(UnitRole.Attack.values());
    assertThat(works).containsExactlyInAnyOrder(UnitRole.Work.values());
    assertThat(splash).isTrue();
    assertThat(buildingsOnly).isTrue();
  }

  /**
   * Every row of the characters and buildings tables, as the battle reads it. A row the battle
   * refuses (a column or form it does not model) is in no battle, so it never gets an icon, and is
   * left out.
   */
  private static List<UnitData> units() {
    List<UnitData> units = new ArrayList<>();
    for (String table : List.of("characters", "buildings")) {
      for (GameRow row : GameData.tables().table(table).rows()) {
        try {
          units.add(GameData.unit(row.name()));
        } catch (UnsupportedOperationException refused) {
          // refused by name: not a unit the screen can show
        }
      }
    }
    return units;
  }
}
