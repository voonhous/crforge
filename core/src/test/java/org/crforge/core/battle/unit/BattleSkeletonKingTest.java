package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.ContactRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Skeleton King's ability where its runs do not take it: its graveyard after the King has died,
 * and a clone deploying out of collisions.
 */
class BattleSkeletonKingTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Skeleton King and seven Knights. */
  private static final List<String> KING_KNIGHTS =
      List.of("SkeletonKing", "Knight", "Knight", "Knight", "Knight", "Knight", "Knight", "Knight");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** A ladder match with passive towers, the Skeleton King played for side 0, and its souls. */
  private static final class Scene {
    Standard1v1Battle battle;
    LadderMatch match;
    final CharacterEntity king;
    final List<String> souls = new ArrayList<>();
    final List<String> spawned = new ArrayList<>();
    int lifetime = -1;

    Scene() {
      // The first player's word whose shuffle deals the King into the opening hand.
      for (int word = 0; match == null || !inHand(match, "SkeletonKing"); word++) {
        battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
        match = battle.startLadderMatch(KING_KNIGHTS, KNIGHTS, word, 0);
      }
      battle
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void soulCounted(
                    int tick, CharacterEntity unit, WorldEntity dying, int count) {
                  souls.add(unit.name() + " " + dying.name() + " " + count);
                }

                @Override
                public void soulsSpent(
                    int tick,
                    CharacterEntity unit,
                    AreaEffectEntity areaEffect,
                    int spent,
                    int count,
                    int lifetimeMs) {
                  lifetime = lifetimeMs;
                }

                @Override
                public void areaSpawned(
                    int tick,
                    AreaEffectEntity areaEffect,
                    CharacterEntity child,
                    int retries,
                    int stateAfter) {
                  spawned.add(
                      tick + " " + areaEffect.x() + " " + areaEffect.y() + " " + child.name());
                }
              });
      int cost = GameData.records().matchCard("SkeletonKing").cost();
      while (match.side(0).wholeElixir() < cost) {
        battle.getBattle().step();
      }
      int tick = battle.getBattle().getTick();
      battle.play(tick, GameData.card("SkeletonKing"), LEVEL, 0, 3500, 4000, "s");
      run(tick + 25);
      king = battle.getPlays().get(0).units().get(0);
      assertThat(king.getView().getState()).isNotEqualTo(GridEntityState.DEPLOYING);
    }

    /** Places a unit now, to deploy with the others placed on this tick. */
    CharacterEntity place(String row, int side, int x, int y, String name) {
      int tick = battle.getBattle().getTick();
      return battle.deploy(tick, GameData.unit(row), LEVEL, side, x, y, name);
    }

    /** Steps until everything placed on this tick has deployed. */
    void deployed() {
      run(battle.getBattle().getTick() + 25);
    }

    void kill(WorldEntity unit) {
      battle.getWorld().kill(unit, null);
      battle.getBattle().step();
    }

    void run(int lastTick) {
      while (battle.getBattle().getTick() <= lastTick) {
        battle.getBattle().step();
      }
    }
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  @Test
  @DisplayName(
      "the graveyard the ability makes stays where its King died and makes the rest of its"
          + " skeletons there")
  void theGraveyardOutlivesItsKing() {
    Scene scene = new Scene();
    int tick = scene.battle.getBattle().getTick();
    while (scene.match.side(0).wholeElixir() < 2) {
      scene.battle.getBattle().step();
    }
    tick = scene.battle.getBattle().getTick();
    scene.battle.useAbility(tick, 0, "s_0", "a");
    scene.run(tick + 9);
    assertThat(scene.lifetime).as("six skeletons, 250 each").isEqualTo(1500);
    // Three updates in: no skeleton yet.
    scene.run(tick + 12);
    assertThat(scene.spawned).isEmpty();
    int x = scene.king.getView().getX();
    int y = scene.king.getView().getY();
    scene.kill(scene.king);
    scene.run(tick + 45);

    assertThat(scene.spawned).hasSize(6);
    for (String line : scene.spawned) {
      assertThat(line.split(" ")[1]).isEqualTo(Integer.toString(x));
      assertThat(line.split(" ")[2]).isEqualTo(Integer.toString(y));
    }
  }

  @Test
  @DisplayName(
      "a skeleton's point that a building overlaps is drawn again: around a King ringed by Cannons"
          + " none is made over one")
  void aPointOverABuildingIsDrawnAgain() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    int kx = 9000;
    int ky = 10000;
    CharacterEntity king = battle.deploy(0, GameData.unit("SkeletonKing"), LEVEL, 0, kx, ky, "s");
    // Eight Cannons of the King's side on the ring the skeletons are placed in.
    List<CharacterEntity> cannons = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      double angle = Math.toRadians(i * 45);
      int x = kx + (int) Math.round(2750 * Math.sin(angle));
      int y = ky + (int) Math.round(2750 * Math.cos(angle));
      cannons.add(battle.deploy(0, GameData.unit("Cannon"), LEVEL, 0, x, y, "c" + i));
    }
    List<int[]> made = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaSpawned(
                  int tick,
                  AreaEffectEntity areaEffect,
                  CharacterEntity child,
                  int retries,
                  int stateAfter) {
                made.add(new int[] {child.getView().getX(), child.getView().getY(), retries});
              }
            });
    while (battle.getBattle().getTick() < 30) {
      battle.getBattle().step();
    }
    king.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    king.requestAbility();
    while (battle.getBattle().getTick() < 70) {
      battle.getBattle().step();
    }

    assertThat(made).hasSize(6);
    assertThat(made.stream().mapToInt(m -> m[2]).sum()).as("points drawn again").isPositive();
    int reach = GameData.unit("SkeletonKingSkeleton").collisionRadius();
    for (int[] m : made) {
      if (m[2] == 5) {
        continue;
      }
      for (CharacterEntity cannon : cannons) {
        long dx = m[0] - cannon.getView().getX();
        long dy = m[1] - cannon.getView().getY();
        long apart = cannon.getView().getCollisionRadius() + reach;
        assertThat(dx * dx + dy * dy).isGreaterThanOrEqualTo(apart * apart);
      }
    }
  }

  @Test
  @DisplayName("a clone takes no part in collision while it deploys, and does once it has deployed")
  void aDeployingCloneIsOutOfCollision() {
    GridEntity view = new GridEntity();
    view.setState(GridEntityState.DEPLOYING);
    assertThat(ContactRule.collides(view)).isEqualTo(1);
    view.setClone(true);
    assertThat(ContactRule.collides(view)).isZero();
    assertThat(ContactRule.avoidable(view)).isZero();
    view.setState(GridEntityState.MOVING);
    assertThat(ContactRule.collides(view)).isEqualTo(1);
  }
}
