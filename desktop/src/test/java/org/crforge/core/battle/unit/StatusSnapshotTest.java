package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.battle.UnitStatus;
import org.junit.jupiter.api.Test;

class StatusSnapshotTest {
  @Test
  void freezeAndElectricalStunStayDistinctAndSnapshotsDoNotChange() {
    var battle = new Standard1v1Battle(GameTables.loadConfigured(), 11, false);
    var knight = knight(battle);
    var records = battle.getWorld().getRecords();
    knight.getBuffs().apply(records.buff("ZapFreeze"), 500, 10, knight, 1);
    knight.getBuffs().apply(records.buff("Freeze"), 1000, 10, null, 1);
    EntityView before = BattleAdapter.entity(knight);
    assertThat(before.statuses())
        .extracting(UnitStatus::kind)
        .containsExactly(UnitStatus.Kind.STUNNED, UnitStatus.Kind.FROZEN);
    assertThat(before.statuses().get(0).source()).isEqualTo("Knight #" + knight.getId());
    assertThat(before.statuses().get(0).appliedSide()).isEqualTo(1);
    // Merely reading/rendering a paused frame does not age effects.
    assertThat(BattleAdapter.entity(knight).statuses()).isEqualTo(before.statuses());
    knight.getBuffs().visit();
    assertThat(BattleAdapter.entity(knight).statuses())
        .extracting(UnitStatus::remainingMs)
        .containsExactly(450, 950);
    assertThat(before.statuses()).extracting(UnitStatus::remainingMs).containsExactly(500, 1000);
    knight.getBuffs().apply(records.buff("ZapFreeze"), 1200, 10, knight, 1);
    assertThat(BattleAdapter.entity(knight).statuses().get(0).remainingMs()).isEqualTo(1200);
    for (int i = 0; i < 24; i++) knight.getBuffs().visit();
    assertThat(BattleAdapter.entity(knight).statuses()).isEmpty();
  }

  @Test
  void aRealCloneKeepsItsIdentityAfterSetupAndCanAlsoBeFrozen() {
    var battle = new Standard1v1Battle(GameTables.loadConfigured(), 11, false);
    var original = knight(battle);
    for (int i = 1; i < 25; i++) battle.getBattle().step();
    battle.placeAreaEffect(25, "Clone", 11, 0, 6500, 11000, "clone spell");
    battle.getBattle().step();
    var clone =
        battle.getBattle().getHolder().entities().stream()
            .filter(entity -> entity instanceof CharacterEntity unit && unit.isClone())
            .map(entity -> (CharacterEntity) entity)
            .findFirst()
            .orElseThrow();
    assertThat(BattleAdapter.entity(original).hasStatus(UnitStatus.Kind.CLONE)).isFalse();
    assertThat(BattleAdapter.entity(original).hasStatus(UnitStatus.Kind.STUNNED)).isFalse();
    assertThat(BattleAdapter.entity(clone).hasStatus(UnitStatus.Kind.CLONE)).isTrue();
    for (int i = 0; i < 15; i++) battle.getBattle().step();
    assertThat(clone.getBuffs().carries("Clone")).isFalse();
    clone.getBuffs().apply(battle.getWorld().getRecords().buff("Freeze"), 1000, 10, original, 1);
    var snapshot = BattleAdapter.entity(clone);
    assertThat(snapshot.hasStatus(UnitStatus.Kind.CLONE)).isTrue();
    assertThat(snapshot.hasStatus(UnitStatus.Kind.FROZEN)).isTrue();
    assertThat(snapshot.hitPoints()).isEqualTo(1);
  }

  @Test
  void unknownBuffsRemainAvailableAndRepeatedStunsShareOneBadge() {
    var battle = new Standard1v1Battle(GameTables.loadConfigured(), 11, false);
    var knight = knight(battle);
    var records = battle.getWorld().getRecords();
    knight.getBuffs().apply(records.buff("Rage"), BuffInstance.FOREVER, 10, null, 0);
    knight.getBuffs().apply(records.buff("Stun"), 1000, 10, null, 1);
    knight.getBuffs().apply(records.buff("ZapFreeze"), 500, 10, null, 1);
    var statuses = BattleAdapter.entity(knight).statuses();
    assertThat(statuses).hasSize(3);
    List<UnitStatus> badges = UnitStatus.badges(statuses);
    assertThat(badges)
        .extracting(UnitStatus::kind)
        .containsExactly(UnitStatus.Kind.STUNNED, UnitStatus.Kind.OTHER);
    assertThat(badges.get(0).remainingMs()).isEqualTo(1000);
    assertThat(badges.get(1).name()).isEqualTo("Rage");
    assertThat(badges.get(1).timed()).isFalse();
    assertThat(badges.get(1).duration()).isEqualTo("ongoing");
  }

  private static CharacterEntity knight(Standard1v1Battle battle) {
    var knight =
        battle.deploy(0, battle.getWorld().getRecords().unit("Knight"), 11, 0, 6500, 11000);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    battle.getBattle().step();
    return knight;
  }
}
