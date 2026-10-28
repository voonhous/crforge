/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.battle.UnitRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The crowds the renderer draws with one shared label and thin bars, on synthetic units: troops of
 * one side, row and level whose bodies stand within {@link CrowdLayout#GAP} of each other.
 */
class CrowdLayoutTest {

  private static final int RADIUS = 300;

  /** A step between two bodies that just keeps them linked. */
  private static final int NEAR = 2 * RADIUS + CrowdLayout.GAP;

  @Test
  @DisplayName("a chain of close troops of one side, row and level is one crowd; a far one is not")
  void aChainIsOneCrowd() {
    EntityView a = troop(1, 0, "Unit", 5000, 5000);
    EntityView b = troop(2, 0, "Unit", 5000 + NEAR, 5000);
    // Linked to b only, not to a: the crowd follows the chain.
    EntityView c = troop(3, 0, "Unit", 5000 + 2 * NEAR, 5000);
    EntityView far = troop(4, 0, "Unit", 5000 + 2 * NEAR + NEAR + 1, 5000);

    CrowdLayout layout = CrowdLayout.of(frame(a, b, c, far), -1, -1);

    assertThat(layout.groups()).hasSize(1);
    assertThat(layout.groups().get(0).members()).containsExactly(a, b, c);
    assertThat(layout.groupOf(far)).isNull();
    assertThat(layout.groups().get(0).label("Unit", "Lv3")).isEqualTo("Unit x3 Lv3");
  }

  @Test
  @DisplayName("troops of another side, row or level never share a crowd, however close")
  void onlyLikeTroopsShare() {
    EntityView unit = troop(1, 0, "Unit", 5000, 5000);
    EntityView otherSide = troop(2, 1, "Unit", 5000, 5000 + RADIUS);
    EntityView otherRow = troop(3, 0, "Other", 5000 + RADIUS, 5000);
    EntityView otherLevel = withLevel(troop(4, 0, "Unit", 5000, 5000 - RADIUS), 4);

    CrowdLayout layout = CrowdLayout.of(frame(unit, otherSide, otherRow, otherLevel), -1, -1);

    assertThat(layout.groups()).isEmpty();
  }

  @Test
  @DisplayName("the inspected and the hovered unit stay out of crowds and keep their own labels")
  void focusedUnitsStayOut() {
    EntityView a = troop(1, 0, "Unit", 5000, 5000);
    EntityView b = troop(2, 0, "Unit", 5000 + NEAR, 5000);
    EntityView c = troop(3, 0, "Unit", 5000 + 2 * NEAR, 5000);

    assertThat(CrowdLayout.of(frame(a, b), 1, -1).groups()).isEmpty();
    assertThat(CrowdLayout.of(frame(a, b), -1, 2).groups()).isEmpty();
    // Taking the middle one out breaks the chain in two lone troops.
    assertThat(CrowdLayout.of(frame(a, b, c), -1, 2).groups()).isEmpty();
  }

  @Test
  @DisplayName("buildings and towers are never drawn in a crowd")
  void onlyTroops() {
    EntityView a = withKind(troop(1, 0, "Unit", 5000, 5000), EntityView.Kind.BUILDING);
    EntityView b = withKind(troop(2, 0, "Unit", 5000 + NEAR, 5000), EntityView.Kind.BUILDING);

    assertThat(CrowdLayout.of(frame(a, b), -1, -1).groups()).isEmpty();
  }

  private static BattleFrame frame(EntityView... entities) {
    return new BattleFrame(
        0, 0, false, 1, Arrays.asList(entities), List.of(), false, -1, false, null, List.of());
  }

  /** A synthetic troop at level 3 with the given id, side, row name and position. */
  private static EntityView troop(int id, int side, String name, int x, int y) {
    return view(id, EntityView.Kind.TROOP, side, name, 3, x, y);
  }

  private static EntityView withLevel(EntityView e, int level) {
    return view(e.id(), e.kind(), e.side(), e.name(), level, e.x(), e.y());
  }

  private static EntityView withKind(EntityView e, EntityView.Kind kind) {
    return view(e.id(), kind, e.side(), e.name(), e.level(), e.x(), e.y());
  }

  private static EntityView view(
      int id, EntityView.Kind kind, int side, String name, int level, int x, int y) {
    return new EntityView(
        id,
        kind,
        side,
        name,
        level,
        x,
        y,
        RADIUS,
        100,
        100,
        0,
        0,
        false,
        false,
        0,
        false,
        false,
        0,
        0,
        0,
        false,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        List.of(),
        UnitRole.NONE,
        EntityView.Bar.HEALTH);
  }
}
