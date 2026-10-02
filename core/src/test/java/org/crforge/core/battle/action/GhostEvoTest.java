package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Royal Ghost's run and its summon areas' runs, on a Ghost and areas that log what the
 * runs ask of them: the latch, the two areas across the line to the point, the damage area's
 * countdown and the summons.
 */
class GhostEvoTest {

  /** An object of the battle that stands at a point. */
  private static final class Spot extends BattleEntity {
    private Spot() {
      super(KIND_CHARACTER);
    }

    @Override
    public boolean isRemovable() {
      return false;
    }
  }

  /** What an owner needs for its holder's runs, and nothing else. */
  private abstract static class Owner implements ActionOwner {
    final ActionHolder holder = new ActionHolder(this);

    @Override
    public HitPoints actionHitPoints() {
      return null;
    }

    @Override
    public int variable(int key) {
      return 0;
    }

    @Override
    public void setVariable(int key, int value) {}

    @Override
    public void killBy(ActionOwner killer) {}

    @Override
    public void queueTypedHit(ActionOwner source, int amount, DamageType type) {}
  }

  /** A summon area, which logs its summons. */
  private final class Area extends Owner {
    final String name;

    private Area(String name) {
      this.name = name;
    }

    @Override
    public GhostEvo.SummonHost ghostSummonHost() {
      return (row, reference, x, y) ->
          log.add(
              "summon %s %s on %s facing %d %d"
                  .formatted(row, positions.get(reference) != null, name, x, y));
    }
  }

  /** The Ghost, invisible or not, with a reference or none. */
  private final class Ghost extends Owner implements GhostEvo.Host {
    boolean invisible;
    BattleEntity reference;
    final List<Area> areas = new ArrayList<>();

    @Override
    public GhostEvo.Host ghostEvoHost(GhostEvo action) {
      return this;
    }

    @Override
    public void started(int phase) {
      log.add("started " + phase);
    }

    @Override
    public boolean invisible() {
      return invisible;
    }

    @Override
    public BattleEntity reference() {
      return reference;
    }

    @Override
    public int x(BattleEntity entity) {
      return positions.get(entity)[0];
    }

    @Override
    public int y(BattleEntity entity) {
      return positions.get(entity)[1];
    }

    @Override
    public int x() {
      return 3500;
    }

    @Override
    public int y() {
      return 12000;
    }

    @Override
    public ActionHolder makeArea(String row, int x, int y, BattleEntity target) {
      log.add("area %s %d %d %s".formatted(row, x, y, target != null));
      Area area = new Area(row + areas.size());
      areas.add(area);
      return area.holder;
    }

    @Override
    public void summoned(BattleEntity reference, int x, int y, int countdownMs) {
      log.add("summoned %d %d %d".formatted(x, y, countdownMs));
    }
  }

  private final List<String> log = new ArrayList<>();
  private final Map<BattleEntity, int[]> positions = new HashMap<>();

  private static GhostEvo ghostEvo(int damageDelayMs, int summonDelayMs) {
    return new GhostEvo(
        ActionRow.named("ghost"),
        GhostEvo.Columns.builder()
            .summonDistance(2000)
            .damageArea("Damage")
            .damageAreaDelayMs(damageDelayMs)
            .leftArea("Left")
            .rightArea("Right")
            .summon(new GhostEvo.Summon(ActionRow.named("summon"), "L", "R", summonDelayMs))
            .build());
  }

  /** A Ghost with the run started on it, and a reference straight ahead. */
  private Ghost ghost(GhostEvo action) {
    Ghost ghost = new Ghost();
    Spot knight = new Spot();
    positions.put(knight, new int[] {3500, 14000});
    ghost.reference = knight;
    ghost.holder.start(action);
    return ghost;
  }

  @Test
  @DisplayName(
      "the first hit summons on the latch the start sets: two areas 2000 to either side across"
          + " the line to the point, and the damage area on the fourth step after")
  void theFirstHitSummons() {
    Ghost ghost = ghost(ghostEvo(200, 0));

    ghost.holder.attackEnded();
    assertThat(log)
        .containsExactly(
            "started 0",
            "area Left 5500 14000 false",
            "area Right 1500 14000 false",
            "summoned 3500 14000 200");

    log.clear();
    for (int tick = 1; tick <= 4; tick++) {
      ghost.holder.runPass(tick);
    }
    assertThat(log)
        .as("200, 150, 100, 50: the area on landing on 0")
        .containsExactly("area Damage 3500 14000 true");
  }

  @Test
  @DisplayName(
      "a step that sees the Ghost visible clears the latch, and a hit then summons nothing until a"
          + " step sees it invisible again")
  void theLatchFollowsTheInvisibility() {
    Ghost ghost = ghost(ghostEvo(200, 0));
    ghost.holder.runPass(1);
    log.clear();

    ghost.holder.attackEnded();
    assertThat(log).isEmpty();

    ghost.invisible = true;
    ghost.holder.runPass(2);
    ghost.holder.attackEnded();
    assertThat(log).hasSize(3);
  }

  @Test
  @DisplayName("a delay that is not a multiple of 50 steps past 0 and makes no damage area")
  void aCountdownThatSkipsZeroMakesNoArea() {
    Ghost ghost = ghost(ghostEvo(30, 0));
    ghost.holder.attackEnded();
    log.clear();
    for (int tick = 1; tick <= 5; tick++) {
      ghost.holder.runPass(tick);
    }
    assertThat(log).isEmpty();
  }

  @Test
  @DisplayName("a delay of 0 makes the damage area at once, after the summon areas")
  void aZeroDelayMakesTheAreaAtOnce() {
    Ghost ghost = ghost(ghostEvo(0, 0));
    ghost.holder.attackEnded();
    assertThat(log).endsWith("summoned 3500 14000 0", "area Damage 3500 14000 true");
  }

  @Test
  @DisplayName(
      "each summon run spawns its side's summon on its area's next step after its delay, facing"
          + " the point and handed the reference, and finishes")
  void theSummonRuns() {
    Ghost ghost = ghost(ghostEvo(200, 100));
    ghost.holder.attackEnded();
    log.clear();

    for (Area area : ghost.areas) {
      area.holder.runPass(1);
    }
    assertThat(log).isEmpty();
    for (Area area : ghost.areas) {
      area.holder.runPass(2);
    }
    assertThat(log)
        .containsExactly(
            "summon L true on Left0 facing 3500 14000",
            "summon R true on Right1 facing 3500 14000");
    assertThat(ghost.areas.get(0).holder.running().get(0).isFinished()).isTrue();
  }

  @Test
  @DisplayName("a reference that leaves is forgotten by the summon runs it was handed to")
  void aReferenceThatLeavesIsForgotten() {
    Ghost ghost = ghost(ghostEvo(200, 0));
    ghost.holder.attackEnded();
    log.clear();
    for (Area area : ghost.areas) {
      area.holder.objectLeft(ghost.reference.getId());
      area.holder.runPass(1);
    }
    assertThat(log)
        .containsExactly(
            "summon L false on Left0 facing 3500 14000",
            "summon R false on Right1 facing 3500 14000");
  }

  @Test
  @DisplayName("a hit with no reference is refused, and a summon row scheduled itself is refused")
  void theRefusals() {
    Ghost ghost = ghost(ghostEvo(200, 0));
    ghost.reference = null;
    assertThatThrownBy(() -> ghost.holder.attackEnded())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("summons from a hit with no reference");

    ActionHolder holder = new ActionHolder();
    GhostEvo.Summon summon = new GhostEvo.Summon(ActionRow.named("summon"), "L", "R", 0);
    assertThatThrownBy(() -> holder.start(summon))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is scheduled as an action of its own");
  }
}
