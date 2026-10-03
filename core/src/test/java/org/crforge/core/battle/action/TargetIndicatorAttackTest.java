package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Goblin Machine's rocket run against a machine of its own row's radius at the origin, facing
 * up the arena, whose query lists every object strictly within the radius plus the object's own, as
 * the battle's query does for anything but a building: the ring's edges, the nearest pick with the
 * const-priority offset, the abort that holds a started cooldown at 0, and the stop tags a shot
 * sets until the next step. The inner edge and the abort are the native cases inner_in, inner_out
 * and stun_after_shot. The outer edge is the query's alone: the native case outer_in marks a Knight
 * at 6249, and in outer_out the query drops one at 6250; the finder's ring test asks only the inner
 * edge.
 */
class TargetIndicatorAttackTest {

  /** The Goblin Machine's collision radius. */
  private static final int MACHINE_RADIUS = 750;

  /** A Knight's or a Musketeer's collision radius. */
  private static final int TROOP_RADIUS = 500;

  private static final long STOP_TAG = 1L << 30;

  /** The shipped row's columns, with no actions on the owner. */
  private static final TargetIndicatorAttack ROCKET =
      new TargetIndicatorAttack(
          ActionRow.named("goblin_machine_rocket"),
          TargetIndicatorAttack.Columns.builder()
              .loadTimeMs(1500)
              .attackDelayMs(1000)
              .attackCooldownMs(2500)
              .range(5000)
              .minimumRange(2500)
              .targetAoE("signal")
              .projectile("rocket")
              .projectileStartZ(5000)
              .lookOffset(-1200)
              .stopTags(STOP_TAG)
              .build());

  /** The machine and the world around it. */
  private static final class Machine implements ActionOwner, TargetIndicatorHost {
    final ActionHolder holder = new ActionHolder(this);

    /** Each object's x, y, radius and const-priority offset, in the query's order. */
    final Map<Integer, int[]> objects = new LinkedHashMap<>();

    final Set<Integer> live = new HashSet<>();
    final List<String> made = new ArrayList<>();
    boolean active = true;
    private int nextId = 3000000;

    Machine() {
      holder.start(ROCKET);
    }

    void object(int id, int x, int y, int priority) {
      objects.put(id, new int[] {x, y, TROOP_RADIUS, priority});
      live.add(id);
    }

    void steps(int count) {
      for (int i = 0; i < count; i++) {
        holder.runPass(0);
      }
    }

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

    @Override
    public TargetIndicatorHost targetIndicatorHost() {
      return this;
    }

    @Override
    public int timeStep(int stepMs) {
      return stepMs;
    }

    @Override
    public boolean active() {
      return active;
    }

    @Override
    public boolean noAttack() {
      return false;
    }

    @Override
    public int ownerX() {
      return 0;
    }

    @Override
    public int ownerY() {
      return 0;
    }

    @Override
    public int ownerRadius() {
      return MACHINE_RADIUS;
    }

    @Override
    public int[] facing() {
      return new int[] {0, 256};
    }

    @Override
    public List<Integer> query(int radius, GameObjectFilter filter) {
      List<Integer> listed = new ArrayList<>();
      for (Map.Entry<Integer, int[]> object : objects.entrySet()) {
        int[] o = object.getValue();
        long reach = (long) radius + o[2];
        if ((long) o[0] * o[0] + (long) o[1] * o[1] < reach * reach) {
          listed.add(object.getKey());
        }
      }
      return listed;
    }

    @Override
    public boolean live(int id) {
      return live.contains(id);
    }

    @Override
    public int x(int id) {
      return objects.get(id)[0];
    }

    @Override
    public int y(int id) {
      return objects.get(id)[1];
    }

    @Override
    public int radius(int id) {
      return objects.get(id)[2];
    }

    @Override
    public int priority(int id) {
      return objects.get(id)[3];
    }

    @Override
    public int signal(String row, int targetId) {
      int id = nextId++;
      live.add(id);
      made.add("signal at " + targetId);
      return id;
    }

    @Override
    public int launch(String projectile, int signalId, int x, int y, int z) {
      int id = nextId++;
      live.add(id);
      made.add("shot from %d %d %d".formatted(x, y, z));
      return id;
    }

    @Override
    public void endSignal(int signalId) {
      live.remove(signalId);
      made.add("signal ended");
    }

    @Override
    public void schedule(BattleAction action, int instigatorId) {}

    @Override
    public void log(TargetIndicatorAttack.Event event) {}
  }

  @Test
  @DisplayName(
      "a centre at least 2500 beyond both radii and listed by the query is marked: a Knight at"
          + " 3750 or 6249 is, one at 3749, or at 6250 where the query drops it, is not")
  void theRingsEdges() {
    for (int distance : List.of(3749, 3750, 6249, 6250)) {
      Machine machine = new Machine();
      machine.object(5000000, 0, distance, 0);
      // The load passes 1500 on the 31st step.
      machine.steps(31);
      boolean marked = distance == 3750 || distance == 6249;
      assertThat(machine.made)
          .as("a Knight at %d", distance)
          .isEqualTo(marked ? List.of("signal at 5000000") : List.of());
    }
  }

  @Test
  @DisplayName(
      "the finder takes the least squared distance less the const-priority offset, the earlier"
          + " of equals, and the rocket starts 1200 behind the facing at height 5000")
  void theNearestIsMarked() {
    int offset = 4500 * 4500 - 4000 * 4000;
    Machine tied = new Machine();
    tied.object(5000000, 0, 4000, 0);
    tied.object(5000001, 0, 4500, offset);
    tied.steps(51);
    assertThat(tied.made).containsExactly("signal at 5000000", "shot from 0 -1200 5000");

    Machine closer = new Machine();
    closer.object(5000000, 0, 4000, 0);
    closer.object(5000001, 0, 4500, offset + 1);
    closer.steps(31);
    assertThat(closer.made).containsExactly("signal at 5000001");
  }

  @Test
  @DisplayName(
      "a shot sets the stop tags until the next step; an abort after it ends the signal, puts the"
          + " started cooldown back to 0 and holds it there, so the next signal comes 50 steps"
          + " after the component is back")
  void anAbortHoldsTheCooldown() {
    Machine machine = new Machine();
    machine.object(5000000, 0, 4500, 0);
    machine.steps(51);
    assertThat(machine.made).containsExactly("signal at 5000000", "shot from 0 -1200 5000");
    assertThat(machine.holder.tags()).as("the stop tags after the shot").isEqualTo(STOP_TAG);
    machine.steps(1);
    assertThat(machine.holder.tags()).as("cleared by the next step").isZero();

    // Ten steps after the shot the component goes off for ten steps, a stun.
    machine.steps(9);
    machine.active = false;
    machine.steps(10);
    assertThat(machine.made).last().isEqualTo("signal ended");
    machine.active = true;
    machine.made.clear();
    // Counted from 0 again, the cooldown reaches 2500 at the entry of the 51st step.
    machine.steps(50);
    assertThat(machine.made).isEmpty();
    machine.steps(1);
    assertThat(machine.made).containsExactly("signal at 5000000");
  }
}
