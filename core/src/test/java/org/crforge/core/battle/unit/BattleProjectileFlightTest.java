package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the projectiles of the Musketeer run to the reference: every launch with its id, start and
 * aim, every position after every flight step, every impact with its damage and the tower's
 * remaining hit points, and the projectile's place in the holder - ahead of every character while
 * it flies, gone in the cleanup of the tick it arrives. The last test takes the Musketeer's target
 * away mid-flight and holds the shot to what the removal notice says: it flies on to where the
 * tower stood and lands on nothing.
 *
 * <p>Ticks are the reference's: battle tick {@code n + 1} is reference tick {@code n}, so an
 * observer's tick is one more than the reference's.
 */
class BattleProjectileFlightTest {

  /** The reference tick a shot fired on 168 is in the air at. */
  private static final int IN_FLIGHT_TICK = 170;

  /** One launch, impact or position as the reference lists it, flattened to a comparable line. */
  private static final class Recording implements WorldObserver {
    final List<String> launches = new ArrayList<>();
    final List<String> impacts = new ArrayList<>();
    final List<String> positions = new ArrayList<>();
    final List<String> deaths = new ArrayList<>();
    int firstTick = -1;
    final CharacterEntity unit;

    Recording(CharacterEntity unit) {
      this.unit = unit;
    }

    @Override
    public void afterPrePass(int tick, List<WorldEntity> present) {
      if (firstTick < 0 && present.contains(unit)) {
        firstTick = tick;
      }
    }

    @Override
    public void projectileLaunched(int tick, ProjectileEntity p) {
      launches.add(
          "%d %s %s %s %s %d %d %d aim %d %d %d"
              .formatted(
                  tick - firstTick,
                  p.name(),
                  p.getData().name(),
                  p.getOwner().name(),
                  p.getTarget() == null ? "null" : p.getTarget().name(),
                  p.getX(),
                  p.getY(),
                  p.getZ(),
                  p.getAimX(),
                  p.getAimY(),
                  p.getAimZ()));
    }

    @Override
    public void projectileImpacted(
        int tick, ProjectileEntity p, WorldEntity target, int damage, DamageResult result) {
      impacts.add(
          "%d %s %s %d %d %d %d %d"
              .formatted(
                  tick - firstTick,
                  p.name(),
                  target.name(),
                  damage,
                  target.getTargetView().getHitPoints(),
                  p.getX(),
                  p.getY(),
                  p.getZ()));
      if (result.died()) {
        deaths.add("%d %s".formatted(tick - firstTick, target.name()));
      }
    }

    @Override
    public void afterPostHooks(
        int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
      for (ProjectileEntity p : projectiles) {
        if (!p.isReleased()) {
          positions.add(
              "%d %d %d %d %d"
                  .formatted(tick - firstTick, p.getId(), p.getX(), p.getY(), p.getZ()));
        }
      }
    }
  }

  @Test
  @DisplayName("every launch leaves from the reference start with the reference id and aim")
  void everyLaunchLeavesFromTheReferenceStart() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleMusketeerRunTest.REFERENCE);
    List<String> expected = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      if (event.get("event").asText().equals("launch")) {
        expected.add(
            "%d %s %s %s %s %d %d %d aim %d %d %d"
                .formatted(
                    event.get("tick").asInt(),
                    event.get("projectile").asText(),
                    event.get("config").asText(),
                    event.get("owner").asText(),
                    event.get("target").asText(),
                    event.get("x").asInt(),
                    event.get("y").asInt(),
                    event.get("z").asInt(),
                    event.get("aim").get(0).asInt(),
                    event.get("aim").get(1).asInt(),
                    event.get("aim_z").asInt()));
      }
    }
    assertThat(expected).hasSize(15);
    assertThat(expected.get(0))
        .isEqualTo(
            "168 proj_4000000 MusketeerProjectile Musketeer PrincessTower_1_1 3718 18503 450 aim"
                + " 3500 25500 0");

    Recording recording = run(reference, BattleMusketeerRunTest.PRINCESS_DEATH_TICK);

    assertThat(recording.launches).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("every projectile stands where the reference has it after every flight step")
  void everyProjectileStandsWhereTheReferenceHasIt() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleMusketeerRunTest.REFERENCE);
    List<String> expected = new ArrayList<>();
    for (JsonNode row : reference.get("projectiles")) {
      expected.add(
          "%d %d %d %d %d"
              .formatted(
                  row.get(0).asInt(),
                  row.get(1).asInt(),
                  row.get(2).asInt(),
                  row.get(3).asInt(),
                  row.get(4).asInt()));
    }
    assertThat(expected).as("seven positions for each of fifteen shots").hasSize(105);
    assertThat(expected.get(0)).isEqualTo("169 4000000 3687 19502 450");
    assertThat(expected.get(6))
        .as("the last step before the arrival")
        .isEqualTo("175 4000000 3501 25498 65");

    Recording recording = run(reference, BattleMusketeerRunTest.PRINCESS_DEATH_TICK);

    assertThat(recording.positions).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName(
      "every impact deals the reference damage and leaves the tower at the reference hit points")
  void everyImpactDealsTheReferenceDamage() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleMusketeerRunTest.REFERENCE);
    List<String> expected = new ArrayList<>();
    List<String> deaths = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      if (event.get("event").asText().equals("impact")) {
        expected.add(
            "%d %s %s %d %d %d %d %d"
                .formatted(
                    event.get("tick").asInt(),
                    event.get("projectile").asText(),
                    event.get("target").asText(),
                    event.get("damage").asInt(),
                    event.get("hp").asInt(),
                    event.get("x").asInt(),
                    event.get("y").asInt(),
                    event.get("z").asInt()));
      } else if (event.get("event").asText().equals("death")) {
        deaths.add("%d %s".formatted(event.get("tick").asInt(), event.get("target").asText()));
      }
    }
    assertThat(expected).hasSize(15);
    assertThat(expected.get(0))
        .isEqualTo("176 proj_4000000 PrincessTower_1_1 217 2835 3500 25500 0");
    assertThat(deaths).containsExactly("456 PrincessTower_1_1");

    Recording recording = run(reference, BattleMusketeerRunTest.PRINCESS_DEATH_TICK);

    assertThat(recording.impacts).containsExactlyElementsOf(expected);
    assertThat(recording.deaths).containsExactlyElementsOf(deaths);
  }

  @Test
  @DisplayName(
      "a shot in flight precedes every character in the holder and leaves in the tick it arrives")
  void aShotPrecedesEveryCharacterAndLeavesWhenItArrives() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleMusketeerRunTest.REFERENCE);
    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity musketeer = BattleMusketeerRunTest.deployMusketeer(match, reference);

    battle.step();
    for (int tick = 0; tick <= IN_FLIGHT_TICK; tick++) {
      battle.step();
    }

    List<BattleEntity> entities = battle.getHolder().entities();
    assertThat(entities).hasSize(8);
    assertThat(entities.get(0)).isInstanceOf(ProjectileEntity.class);
    ProjectileEntity shot = (ProjectileEntity) entities.get(0);
    assertThat(shot.getKind()).isEqualTo(BattleEntity.KIND_PROJECTILE);
    assertThat(shot.getId()).isEqualTo(4000000);
    assertThat(shot.name()).isEqualTo("proj_4000000");
    assertThat(entities.stream().map(BattleEntity::getId).toList())
        .as("the projectile band precedes the character band")
        .containsExactly(4000000, 5000000, 5000001, 5000002, 5000003, 5000004, 5000005, 5000006);
    assertThat(shot.getOwner()).isSameAs(musketeer);
    assertThat(shot.getRoot()).isSameAs(musketeer);
    assertThat(shot.getTarget())
        .isSameAs(BattleMusketeerRunTest.towerNamed(battle, BattleMusketeerRunTest.PRINCESS_TOWER));
    assertThat(shot.getSide()).isEqualTo(musketeer.side());
    assertThat(shot.level()).isEqualTo(11);
    assertThat(shot.damage()).isEqualTo(217);
    assertThat(shot.isReleased()).isFalse();
    assertThat(shot.isRemovable()).isFalse();
    assertThat(shot.getX()).isEqualTo(3656);
    assertThat(shot.getY()).isEqualTo(20501);
    assertThat(shot.getZ()).isEqualTo(386);

    // The arrival tick: the impact lands in the post-hook pass and the closing cleanup drops the
    // released projectile, before the Musketeer's next shot has even left.
    for (int tick = IN_FLIGHT_TICK + 1;
        tick <= BattleMusketeerRunTest.FIRST_LAUNCH_TICK + BattleMusketeerRunTest.FLIGHT_TICKS;
        tick++) {
      battle.step();
    }
    assertThat(shot.isReleased()).isTrue();
    assertThat(shot.isRemovable()).isTrue();
    assertThat(shot.getX()).isEqualTo(3500);
    assertThat(shot.getY()).isEqualTo(25500);
    assertThat(shot.getZ()).isZero();
    assertThat(battle.getHolder().entities()).doesNotContain(shot);
    assertThat(battle.getHolder().entities())
        .noneMatch(entity -> entity instanceof ProjectileEntity);
  }

  @Test
  @DisplayName("a homing shot whose target left flies on to where it stood and lands on nothing")
  void aHomingShotWhoseTargetLeftLandsOnNothing() {
    JsonNode reference = BattleMusketeerRunTest.load(BattleMusketeerRunTest.REFERENCE);
    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity musketeer = BattleMusketeerRunTest.deployMusketeer(match, reference);
    Recording recording = new Recording(musketeer);
    match.getWorld().addObserver(recording);

    battle.step();
    for (int tick = 0; tick <= IN_FLIGHT_TICK; tick++) {
      battle.step();
    }
    ProjectileEntity shot = (ProjectileEntity) battle.getHolder().entities().get(0);
    TowerEntity tower =
        BattleMusketeerRunTest.towerNamed(battle, BattleMusketeerRunTest.PRINCESS_TOWER);
    assertThat(shot.getTarget()).isSameAs(tower);

    // The tower is destroyed from outside, between two steps; the next step's opening cleanup
    // removes it and tells the shot, which keeps its aim on the tower's last position.
    match.getWorld().dealDamage(tower.getTargetView(), 100000, 0, 1);
    battle.step();
    assertThat(battle.getHolder().entities()).doesNotContain(tower);
    assertThat(shot.getTarget()).as("the target is forgotten").isNull();
    assertThat(shot.getOwner()).as("the owner stands").isSameAs(musketeer);
    assertThat(shot.getAimX()).isEqualTo(3500);
    assertThat(shot.getAimY()).isEqualTo(25500);
    assertThat(shot.getAimZ()).isZero();
    assertThat(BattleMusketeerRunTest.referenceName(musketeer)).isNull();
    assertThat(musketeer.getView().getState()).isEqualTo(GridEntityState.ATTACKING);

    // The shot flies the rest of the way as the reference has it, arrives on 176 and impacts on
    // nothing: no damage is dealt and it leaves the holder in that tick's cleanup.
    for (int tick = IN_FLIGHT_TICK + 2;
        tick <= BattleMusketeerRunTest.FIRST_LAUNCH_TICK + BattleMusketeerRunTest.FLIGHT_TICKS;
        tick++) {
      battle.step();
    }
    assertThat(recording.positions)
        .containsSubsequence(
            "171 4000000 3625 21500 322",
            "172 4000000 3594 22499 258",
            "175 4000000 3501 25498 65");
    assertThat(recording.impacts).isEmpty();
    assertThat(shot.isReleased()).isTrue();
    assertThat(shot.getX()).isEqualTo(3500);
    assertThat(shot.getY()).isEqualTo(25500);
    assertThat(battle.getHolder().entities()).doesNotContain(shot);
  }

  /** Plays the run through the given reference tick with a recording observer attached. */
  private static Recording run(JsonNode reference, int lastTick) {
    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity musketeer = BattleMusketeerRunTest.deployMusketeer(match, reference);
    Recording recording = new Recording(musketeer);
    match.getWorld().addObserver(recording);
    battle.step();
    for (int tick = 0; tick <= lastTick; tick++) {
      battle.step();
    }
    return recording;
  }
}
