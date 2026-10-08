package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the buffs a hit's dealer carries make of the hit. Each listing and each removal folds the
 * listed rows into two percents: from DamageMultiplier and from CharacterCrownTowerDamagePercent,
 * each the largest value above 100 (from 100) times the smallest value from 1 to 99 (from 100),
 * over 100; a value below 1 counts for nothing. The damage entry scales a hit its dealer deals by
 * the first, truncated and floored at 1, and a hit on a crown tower by the second after it.
 */
class BattleBuffDamagePercentTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * A buff row that sets only the two columns given. Of the configured buffs only TripleDamage sets
   * 200 or 300, and its first attack removes it.
   */
  private static BuffData percents(String name, int damageMultiplier, int crownTowerPercent) {
    return BuffData.builder()
        .name(name)
        .rarity(GameData.records().buff("Rage").rarity())
        .damageMultiplier(damageMultiplier)
        .characterCrownTowerDamagePercent(crownTowerPercent)
        .build();
  }

  /**
   * The hit points each hit of the Knight named "knight" took off the target named, in order: a
   * passive battle with the Knight of side 0 and the target, the buffs given listed on the Knight
   * before its first hit.
   */
  private static List<Integer> hits(boolean tower, List<BuffData> buffs) {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<Integer> taken = new ArrayList<>();
    CharacterEntity knight;
    WorldEntity target;
    if (tower) {
      knight = battle.deploy(0, GameData.records().unit("Knight"), LEVEL, 0, 3500, 23500, "knight");
      battle.getBattle().step();
      target =
          battle.getWorld().getHolder().entities().stream()
              .filter(TowerEntity.class::isInstance)
              .map(TowerEntity.class::cast)
              .filter(t -> t.side() == 1 && t.getView().getX() == 3500)
              .findFirst()
              .orElseThrow();
    } else {
      knight = battle.deploy(0, GameData.records().unit("Knight"), LEVEL, 0, 3500, 15000, "knight");
      target = battle.deploy(0, GameData.records().unit("Knight"), LEVEL, 1, 3500, 16500, "enemy");
      battle.getBattle().step();
    }
    for (BuffData buff : buffs) {
      knight.getBuffs().apply(buff, 1000000, knight.getPackedLevel(), null, 0);
    }
    WorldEntity struck = target;
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity entity, int damage, DamageResult result) {
                if (entity == struck && result.landed()) {
                  taken.add(result.applied());
                }
              }
            });
    for (int i = 0; i < 400 && taken.size() < 3; i++) {
      battle.getBattle().step();
    }
    assertThat(taken).as("the Knight hits three times").hasSizeGreaterThanOrEqualTo(3);
    return taken.subList(0, 3);
  }

  @Test
  @DisplayName("a hit dealt under a damage multiplier of 200 takes twice the plain hit")
  void aMultiplierScalesTheHit() {
    int plain = hits(false, List.of()).get(0);
    assertThat(hits(false, List.of(percents("double", 200, 0)))).containsOnly(plain * 200 / 100);
  }

  @Test
  @DisplayName(
      "the multipliers fold to the largest above 100 times the smallest below 100, over 100, and"
          + " a value below 1 counts for nothing: 300, 200, 50 and -100 make 150")
  void theMultipliersFold() {
    int plain = hits(false, List.of()).get(0);
    List<BuffData> buffs =
        List.of(
            percents("triple", 300, 0),
            percents("double", 200, 0),
            percents("half", 50, 0),
            percents("still", -100, 0));
    assertThat(hits(false, buffs)).containsOnly(plain * 150 / 100);
  }

  @Test
  @DisplayName("a multiplier of -100 alone leaves the hit as it is")
  void aNegativeMultiplierCountsForNothing() {
    int plain = hits(false, List.of()).get(0);
    assertThat(hits(false, List.of(percents("still", -100, 0)))).containsOnly(plain);
  }

  @Test
  @DisplayName(
      "on a crown tower the crown tower percent scales the hit after the multiplier: 200 then 25"
          + " make half the plain hit, and the crown tower percent alone does not touch a unit")
  void theCrownTowerPercentScalesATowerHit() {
    int plain = hits(true, List.of()).get(0);
    List<BuffData> buffs = List.of(percents("double", 200, 0), percents("tower", 0, 25));
    assertThat(hits(true, buffs)).containsOnly(plain * 200 / 100 * 25 / 100);
    int unit = hits(false, List.of()).get(0);
    assertThat(hits(false, List.of(percents("tower", 0, 25)))).containsOnly(unit);
  }
}
