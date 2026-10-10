/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter between the battle core and the renderers: every entity kind is mapped to what it is
 * drawn as, with its side, its position and its health read from the battle's own objects.
 */
class BattleAdapterTest {

  /** The most steps a test waits for something to appear. */
  private static final int PATIENCE = 400;

  @Test
  @DisplayName("a new Ladder battle shows the six towers, three a side, each whole, one king each")
  void theTowers() {
    BattleSession session = BattleSession.ladder(GameData.tables());
    BattleFrame frame = BattleAdapter.frame(session);

    List<EntityView> towers =
        frame.entities().stream().filter(e -> e.kind() == EntityView.Kind.TOWER).toList();
    assertThat(towers).hasSize(6);
    for (int side = 0; side < 2; side++) {
      int s = side;
      List<EntityView> own = towers.stream().filter(t -> t.side() == s).toList();
      assertThat(own).as("side %d's towers", side).hasSize(3);
      assertThat(own.stream().filter(EntityView::king)).as("side %d's king", side).hasSize(1);
    }
    for (EntityView tower : towers) {
      assertThat(tower.hasHitPoints()).isTrue();
      assertThat(tower.hitPoints()).isEqualTo(tower.maxHitPoints()).isPositive();
      assertThat(tower.healthShare()).isEqualTo(1f);
      assertThat(tower.isCharacter()).isTrue();
    }
    // Side 0 stands at the bottom of the arena, side 1 at the top.
    int bottomKing = king(towers, 0).y();
    int topKing = king(towers, 1).y();
    assertThat(bottomKing).isLessThan(topKing);
  }

  @Test
  @DisplayName("each tower's view is read from its entity: id, position, row and hit points")
  void aTowersViewIsItsEntity() {
    BattleSession session = BattleSession.ladder(GameData.tables());
    for (BattleEntity entity : session.getBattle().getBattle().getHolder().entities()) {
      TowerEntity tower = (TowerEntity) entity;
      EntityView view = BattleAdapter.entity(tower);
      assertThat(view.id()).isEqualTo(tower.getId());
      assertThat(view.side()).isEqualTo(tower.side());
      assertThat(view.x()).isEqualTo(tower.getView().getX());
      assertThat(view.y()).isEqualTo(tower.getView().getY());
      assertThat(view.name()).isEqualTo(tower.getData().name());
      assertThat(view.radius()).isEqualTo(tower.getView().getCollisionRadius());
      assertThat(view.hitPoints()).isEqualTo(tower.getHitPoints().getHitPoints());
    }
  }

  @Test
  @DisplayName("both sides' hands, elixir and next card come from the match")
  void theSides() {
    BattleSession session = BattleSession.ladder(GameData.tables());
    BattleFrame frame = BattleAdapter.frame(session);

    assertThat(frame.sides()).hasSize(2);
    for (BattleFrame.SideView side : frame.sides()) {
      List<String> deck = side.side() == 0 ? BattleDecks.BLUE : BattleDecks.RED;
      assertThat(side.hand()).hasSize(4).doesNotContainNull();
      assertThat(side.hand()).allSatisfy(card -> assertThat(deck).contains(card.name()));
      assertThat(side.hand()).noneMatch(BattleFrame.CardView::pending);
      assertThat(deck).contains(side.next().name());
      assertThat(side.elixir())
          .isEqualTo(session.match().side(side.side()).getElixir())
          .isPositive();
      assertThat(side.crowns()).isZero();
    }
    assertThat(frame.elixirRate()).isEqualTo(1);
    assertThat(frame.overtime()).isFalse();
    assertThat(frame.ended()).isFalse();
  }

  @Test
  @DisplayName("a played troop shows as a troop of its side, deploying, with its hit points")
  void aTroop() {
    BattleSession session = only("Prince");
    assertThat(session.play(0, 0, 9500, 8500)).isTrue();

    EntityView prince = await(session, e -> e.name().equals("Prince"));
    assertThat(prince.kind()).isEqualTo(EntityView.Kind.TROOP);
    assertThat(prince.side()).isZero();
    assertThat(prince.deploying()).isTrue();
    assertThat(prince.hitPoints()).isEqualTo(prince.maxHitPoints()).isPositive();
    assertThat(prince.range()).isPositive();
    assertThat(prince.grid()).isNotNull();
    assertThat(prince.air()).isFalse();
  }

  @Test
  @DisplayName("a played building shows as a building of its side")
  void aBuilding() {
    BattleSession session = only("Tombstone");
    assertThat(session.play(1, 0, 9500, 23500)).isTrue();

    EntityView tombstone = await(session, e -> e.name().equals("Tombstone"));
    assertThat(tombstone.kind()).isEqualTo(EntityView.Kind.BUILDING);
    assertThat(tombstone.side()).isEqualTo(1);
    assertThat(tombstone.hasHitPoints()).isTrue();
  }

  @Test
  @DisplayName("an air troop is flagged flying")
  void anAirTroop() {
    BattleSession session = only("InfernoDragon");
    assertThat(session.play(0, 0, 9500, 8500)).isTrue();

    EntityView dragon = await(session, e -> e.name().equals("InfernoDragon"));
    assertThat(dragon.kind()).isEqualTo(EntityView.Kind.TROOP);
    assertThat(dragon.air()).isTrue();
  }

  @Test
  @DisplayName("a spell's projectile shows as a projectile of its side with its area and aim")
  void aProjectile() {
    BattleSession session = only("Fireball");
    EntityView target = princess(BattleAdapter.frame(session), 1);
    assertThat(session.play(0, 0, target.x(), target.y())).isTrue();

    EntityView fireball = await(session, e -> e.kind() == EntityView.Kind.PROJECTILE);
    assertThat(fireball.side()).isZero();
    assertThat(fireball.radius()).isPositive();
    assertThat(fireball.hasHitPoints()).isFalse();
    assertThat(Math.abs(fireball.aimX() - target.x())).isLessThanOrEqualTo(1000);

    // The tower it lands on loses hit points.
    int maximum = target.maxHitPoints();
    await(
        session,
        e -> e.id() == target.id() && e.hitPoints() < maximum,
        "the tower to take the Fireball's damage");
  }

  @Test
  @DisplayName(
      "a lasting area spell shows as an area effect of its side with its radius and lifetime")
  void anAreaEffect() {
    BattleSession session = only("Poison");
    assertThat(session.play(1, 0, 9500, 20500)).isTrue();

    EntityView poison = await(session, e -> e.kind() == EntityView.Kind.AREA_EFFECT);
    assertThat(poison.side()).isEqualTo(1);
    assertThat(poison.radius()).isPositive();
    assertThat(poison.lifetimeMs()).isPositive();
    assertThat(poison.lifeMs()).isBetween(0, poison.lifetimeMs());
    assertThat(poison.isCharacter()).isFalse();
  }

  @Test
  @DisplayName("every entity of the live list is drawn, and no other")
  void everyEntityIsDrawn() {
    BattleSession session = only("Witch");
    assertThat(session.play(0, 0, 9500, 8500)).isTrue();
    // Until the Witch has spawned her first skeletons.
    await(
        session,
        e -> e.kind() == EntityView.Kind.TROOP && !e.name().equals("Witch"),
        "the Witch's first spawn");
    int steps = session.tick();
    BattleFrame frame = BattleAdapter.frame(session);
    List<Integer> live =
        session.getBattle().getBattle().getHolder().entities().stream()
            .map(BattleEntity::getId)
            .toList();
    assertThat(frame.entities()).extracting(EntityView::id).containsExactlyElementsOf(live);
    // The Witch and the skeletons she has spawned by now, beside the towers.
    assertThat(frame.entities())
        .filteredOn(e -> e.kind() == EntityView.Kind.TROOP)
        .hasSizeGreaterThan(1);
    assertThat(frame.tick()).isEqualTo(steps);
  }

  @Test
  @DisplayName(
      "a Royal Chef's king tower shows its cooking bar: empty through the start delay, then"
          + " filling; a plain king shows none")
  void theCookingBar() {
    BattleSession session = chefTowers();
    EntityView chef = king(towers(BattleAdapter.frame(session)), 1);
    assertThat(chef.meter()).isNull();
    assertThat(king(towers(BattleAdapter.frame(session)), 0).meter()).isNull();

    // The run starts with the battle's first step and waits its start delay of 140 steps.
    assertThat(session.step()).isTrue();
    ActionMeter started = king(towers(BattleAdapter.frame(session)), 1).meter();
    assertThat(started).isNotNull();
    assertThat(started.kind()).isEqualTo(ActionMeter.Kind.COOKING);
    assertThat(started.value()).isZero();
    assertThat(started.maximum()).isPositive();
    assertThat(started.share()).isZero();

    for (int i = 0; i < 200; i++) {
      assertThat(session.step()).isTrue();
    }
    BattleFrame frame = BattleAdapter.frame(session);
    ActionMeter cooking = king(towers(frame), 1).meter();
    assertThat(cooking.value()).isPositive().isLessThan(cooking.maximum());
    assertThat(cooking.share()).isGreaterThan(0f).isLessThan(1f);
    assertThat(king(towers(frame), 0).meter()).as("a plain king tower").isNull();
    assertThat(towers(frame).stream().filter(t -> !t.king()))
        .as("the princess-slot towers")
        .allSatisfy(t -> assertThat(t.meter()).isNull());
  }

  @Test
  @DisplayName(
      "a Dagger Duchess tower shows its charges: full at the start, drained by its attacks on a"
          + " Giant, then charged again while it does not attack")
  void theChargeBar() {
    int level = Standard1v1Battle.DEFAULT_LEVEL;
    Standard1v1Battle battle =
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers(Standard1v1Battle.PRINCESS_TOWERS, level, level),
                new Standard1v1Battle.Towers("King_KnifeTowers", level, level)),
            true);
    // A Giant of side 0 that walks up the left lane to the left Duchess tower.
    battle.play(1, battle.getWorld().getRecords().card("Giant"), level, 0, 3500, 14000, "g");
    BattleSession session = BattleSession.of(battle);
    assertThat(session.step()).isTrue();

    EntityView duchess = princess(BattleAdapter.frame(session), 1);
    ActionMeter full = duchess.meter();
    assertThat(full).isNotNull();
    assertThat(full.kind()).isEqualTo(ActionMeter.Kind.CHARGES);
    // The charge counter's MaxChargeCount, from the Duchess row's OnStartingAction.
    int charges =
        GameData.tables()
            .table("buildings")
            .row("DaggerDuchess")
            .columns()
            .get("OnStartingAction")
            .path("MaxChargeCount")
            .asInt();
    assertThat(full.segments()).isEqualTo(charges);
    assertThat(full.share()).isEqualTo(1f);
    assertThat(king(towers(BattleAdapter.frame(session)), 1).meter()).as("its king").isNull();
    assertThat(princess(BattleAdapter.frame(session), 0).meter()).as("a plain tower").isNull();

    int lowest = full.value();
    boolean drained = false;
    boolean recharged = false;
    for (int i = 0; i < 1500 && !recharged; i++) {
      assertThat(session.step()).as("step %d: %s", i, session.messages()).isTrue();
      ActionMeter meter = princess(BattleAdapter.frame(session), 1).meter();
      if (meter.value() < lowest) {
        lowest = meter.value();
        drained = true;
      } else if (drained && meter.value() > lowest) {
        recharged = true;
      }
    }
    assertThat(drained).as("the charges drained").isTrue();
    assertThat(lowest).isLessThan(full.maximum());
    assertThat(recharged).as("the charges rose again after %d", lowest).isTrue();
  }

  /** A session whose side 1 has the Royal Chef's towers, every tower passive. */
  private static BattleSession chefTowers() {
    int level = Standard1v1Battle.DEFAULT_LEVEL;
    return BattleSession.of(
        new Standard1v1Battle(
            GameData.tables(),
            List.of(
                new Standard1v1Battle.Towers(Standard1v1Battle.PRINCESS_TOWERS, level, level),
                new Standard1v1Battle.Towers("King_ChefTowers", level, level)),
            false));
  }

  private static List<EntityView> towers(BattleFrame frame) {
    return frame.entities().stream().filter(e -> e.kind() == EntityView.Kind.TOWER).toList();
  }

  /** A Ladder battle in which both decks are eight copies of one card, always in slot 0. */
  private static BattleSession only(String card) {
    List<String> deck = Collections.nCopies(8, card);
    return BattleSession.ladder(GameData.tables(), deck, deck);
  }

  private static EntityView king(List<EntityView> towers, int side) {
    return towers.stream().filter(t -> t.king() && t.side() == side).findFirst().orElseThrow();
  }

  /** A princess tower of a side: its tower that is not the king, lowest along the width. */
  private static EntityView princess(BattleFrame frame, int side) {
    return frame.entities().stream()
        .filter(e -> e.kind() == EntityView.Kind.TOWER && e.side() == side && !e.king())
        .min((a, b) -> Integer.compare(a.x(), b.x()))
        .orElseThrow();
  }

  private static EntityView await(BattleSession session, Predicate<EntityView> wanted) {
    return await(session, wanted, "the entity to appear");
  }

  private static EntityView await(
      BattleSession session, Predicate<EntityView> wanted, String what) {
    for (int i = 0; i < PATIENCE; i++) {
      assertThat(session.step()).as("step %d: %s", i, session.messages()).isTrue();
      for (EntityView entity : BattleAdapter.frame(session).entities()) {
        if (wanted.test(entity)) {
          return entity;
        }
      }
    }
    throw new AssertionError("waited " + PATIENCE + " steps for " + what);
  }
}
