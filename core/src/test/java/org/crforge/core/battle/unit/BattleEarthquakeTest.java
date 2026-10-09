package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An Earthquake's hits where the reference runs leave them: an instance's count toward its next hit
 * set from its area effect's age, an instance that never runs out or whose source has gone counting
 * on as a plain one, a source that is not an area effect refused, and a hidden target that takes
 * the damage over time an ordinary hit would not reach.
 */
class BattleEarthquakeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * The configured tables with the Earthquake's buff written to hit once a second from its source's
   * age, and its area effect to live 3000 ms.
   */
  private static GameTables oncePerSecond(Path folder) throws IOException {
    GameData.altered(
        folder,
        "character_buffs",
        rows ->
            GameData.columns(rows, "Earthquake")
                .put("HitFrequency", 1000)
                .put("HitTickFromSource", true));
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> GameData.columns(rows, "Earthquake").put("LifeDuration", 3000));
    return GameTables.load(folder);
  }

  /** A Knight standing on the top side's half, and the towers passive. */
  private static CharacterEntity knight(Standard1v1Battle match) {
    return match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 23500);
  }

  /** The Earthquake area effect the battle admits first, once the battle has stepped to it. */
  private static AreaEffectEntity earthquake(Standard1v1Battle match, int tick) {
    List<AreaEffectEntity> admitted = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectAdmitted(int at, AreaEffectEntity areaEffect) {
                admitted.add(areaEffect);
              }
            });
    match.placeAreaEffect(tick, "Earthquake", LEVEL, 0, 14000, 9000, "Earthquake");
    for (int step = 0; step <= tick && admitted.isEmpty(); step++) {
      match.getBattle().step();
    }
    return admitted.get(0);
  }

  @Test
  @DisplayName(
      "an instance counts from its area effect's age, however late it was applied, and is hit as"
          + " the age reaches 950 of a second")
  void theCountFollowsTheAreaEffectsAge(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(oncePerSecond(folder), LEVEL, false);
    CharacterEntity knight = knight(match);
    AreaEffectEntity earthquake = earthquake(match, 1);
    for (int step = 0; step < 7; step++) {
      match.getBattle().step();
    }
    BuffComponent buffs = knight.getBuffs();
    BuffData row = match.getWorld().getRecords().buff("Earthquake");
    buffs.apply(row, 1000, LEVEL, earthquake, 0);
    BuffInstance instance = buffs.items().get(0);

    int age = earthquake.age();
    assertThat(age % 1000).isNotZero();
    buffs.visit();
    assertThat(instance.getHitCounter()).isEqualTo(age % 1000 / 50 + 1);
    while (earthquake.age() % 1000 != 950) {
      match.getBattle().step();
    }
    buffs.visit();
    assertThat(instance.getHitCounter()).isZero();
  }

  @Test
  @DisplayName("an instance that never runs out counts on from its own count")
  void anInstanceThatNeverRunsOutCountsOn() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = knight(match);
    AreaEffectEntity earthquake = earthquake(match, 1);
    for (int step = 0; step < 7; step++) {
      match.getBattle().step();
    }
    BuffComponent buffs = knight.getBuffs();
    BuffData row = GameData.records().buff("Earthquake");
    buffs.apply(row, BuffInstance.FOREVER, LEVEL, earthquake, 0);
    BuffInstance forever = buffs.items().get(0);
    buffs.visit();
    buffs.visit();
    assertThat(forever.getHitCounter()).isEqualTo(2);
  }

  @Test
  @DisplayName("an instance whose source has gone counts on, its time running out as a plain one")
  void aGoneSourceCountsOn() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = knight(match);
    AreaEffectEntity earthquake = earthquake(match, 1);
    for (int step = 0; step < 7; step++) {
      match.getBattle().step();
    }
    BuffComponent buffs = knight.getBuffs();
    buffs.apply(GameData.records().buff("Earthquake"), 1000, LEVEL, earthquake, 0);
    BuffInstance instance = buffs.items().get(0);
    buffs.visit();
    int counted = instance.getHitCounter();
    buffs.entityRemoved(earthquake);
    buffs.visit();
    assertThat(instance.getHitCounter()).isEqualTo(counted + 1);
  }

  @Test
  @DisplayName(
      "a buff that follows its source is refused when something other than an area effect applied"
          + " it")
  void aSourceThatIsNotAnAreaEffectIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = knight(match);
    CharacterEntity other = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000);
    match.getBattle().step();
    BuffComponent buffs = knight.getBuffs();
    buffs.apply(GameData.records().buff("Earthquake"), 1000, LEVEL, other, 0);

    assertThatThrownBy(buffs::visit)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("not an area effect");
  }

  @Test
  @DisplayName("the count is the age modulo the hit frequency in visits, the age itself for none")
  void theCountIsTheAgeModuloTheFrequency() {
    BuffInstance second = instance(1000);
    second.followSource(2950, 50);
    assertThat(second.getHitCounter()).isEqualTo(19);
    BuffInstance half = instance(500);
    half.followSource(2950, 50);
    assertThat(half.getHitCounter()).isEqualTo(9);
    BuffInstance none = instance(0);
    none.followSource(150, 50);
    assertThat(none.getHitCounter()).isEqualTo(3);
    BuffInstance negative = instance(-1);
    negative.followSource(150, 50);
    assertThat(negative.getHitCounter()).isEqualTo(3);
  }

  @Test
  @DisplayName("a hidden Tesla takes no hit of damage over time, as no ordinary hit lands on it")
  void aHiddenTeslaTakesNoDamageOverTime() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity tesla = match.deploy(0, GameData.unit("Tesla"), LEVEL, 0, 10000, 12000);
    for (int step = 0; step < 40 && !tesla.hidden(); step++) {
      match.getBattle().step();
    }
    int before = tesla.getHitPoints().getHitPoints();

    assertThat(tesla.hidden()).isTrue();
    assertThat(tesla.takeDamage(100, 0, 0, 1)).isEqualTo(DamageResult.NOTHING);
    // The bookkeeping a buff's hit goes through asks the hidden test of it too.
    assertThat(tesla.takeDamageOverTime(100, null)).isEqualTo(DamageResult.NOTHING);
    assertThat(tesla.getHitPoints().getHitPoints()).isEqualTo(before);
  }

  @Test
  @DisplayName("a typed hit, whose hidden test is not traced, still refuses a hidden Tesla")
  void aTypedHitRefusesAHiddenTesla() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity tesla = match.deploy(0, GameData.unit("Tesla"), LEVEL, 0, 10000, 12000);
    for (int step = 0; step < 40 && !tesla.hidden(); step++) {
      match.getBattle().step();
    }
    int before = tesla.getHitPoints().getHitPoints();

    assertThat(tesla.hidden()).isTrue();
    assertThat(tesla.takeTypedHit(null, 100, 0, 0, 1)).isEqualTo(DamageResult.NOTHING);
    assertThat(tesla.getHitPoints().getHitPoints()).isEqualTo(before);
  }

  /** An instance of a bare row with the given hit frequency, with no source. */
  private static BuffInstance instance(int hitFrequency) {
    BuffData row =
        BuffData.builder()
            .name("Bare")
            .rarity(RarityTable.COMMON)
            .hitFrequency(hitFrequency)
            .hitTickFromSource(true)
            .build();
    return new BuffInstance("buff_0", row, 1000, 0, null, 0);
  }
}
