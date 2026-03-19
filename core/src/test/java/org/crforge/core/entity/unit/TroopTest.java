package org.crforge.core.entity.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TroopTest {

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
  }

  @Test
  void builder_shouldCreateTroopWithDefaults() {
    Troop troop = Troop.builder().build();

    assertThat(troop.getName()).isNull(); // Name is not defaulted in AbstractEntity
    assertThat(troop.getTeam()).isNull(); // Team is not defaulted
    assertThat(troop.getHealth().getMax()).isEqualTo(100);
    assertThat(troop.getMovementType()).isEqualTo(MovementType.GROUND);
    assertThat(troop.getEntityType()).isEqualTo(EntityType.TROOP);
  }

  @Test
  void builder_shouldAllowCustomization() {
    Combat combat = Combat.builder().damage(100).range(1.5f).build();

    Troop troop =
        Troop.builder()
            .name("Knight")
            .team(Team.RED)
            .position(new Position(10, 20))
            .health(new Health(1000))
            .movement(new Movement(1.5f, 2.0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(combat)
            .build();

    assertThat(troop.getName()).isEqualTo("Knight");
    assertThat(troop.getTeam()).isEqualTo(Team.RED);
    assertThat(troop.getPosition().getX()).isEqualTo(10);
    assertThat(troop.getPosition().getY()).isEqualTo(20);
    assertThat(troop.getHealth().getMax()).isEqualTo(1000);
    assertThat(troop.getCombat().getDamage()).isEqualTo(100);
  }

  @Test
  void troop_shouldBeTargetableWhileDeploying() {
    Troop troop = Troop.builder().deployTime(1.0f).build();
    troop.onSpawn();

    assertThat(troop.isDeploying()).isTrue();
    // Deploying troops are on the field and can be targeted/attacked by enemies
    assertThat(troop.isTargetable()).isTrue();

    // Still targetable after deploy finishes
    troop.setDeployTimer(0);

    assertThat(troop.isDeploying()).isFalse();
    assertThat(troop.isTargetable()).isTrue();
  }

  @Test
  void troop_shouldTakeDamageWhileDeploying() {
    Troop troop = Troop.builder().deployTime(1.0f).health(new Health(500)).build();
    troop.onSpawn();

    assertThat(troop.isDeploying()).isTrue();
    troop.getHealth().takeDamage(100);

    assertThat(troop.getHealth().getCurrent()).isEqualTo(400);
    assertThat(troop.isTargetable()).isTrue();
  }

  @Test
  void troop_shouldTrackTarget() {
    Troop attacker = Troop.builder().name("Attacker").team(Team.BLUE).build();

    Troop target = Troop.builder().name("Target").team(Team.RED).build();
    target.onSpawn();
    target.update(2.0f); // Deploy

    attacker.getCombat().setCurrentTarget(target);

    assertThat(attacker.getCombat().hasTarget()).isTrue();
    assertThat(attacker.getCombat().getCurrentTarget()).isEqualTo(target);

    attacker.getCombat().clearTarget();

    assertThat(attacker.getCombat().hasTarget()).isFalse();
  }

  @Test
  void troop_shouldCalculateDistanceToTarget() {
    Troop attacker = Troop.builder().position(new Position(0, 0)).build();

    Troop target = Troop.builder().position(new Position(3, 4)).build();
    target.onSpawn();
    target.update(2.0f);

    attacker.getCombat().setCurrentTarget(target);

    assertThat(attacker.getDistanceToTarget()).isEqualTo(5.0f, within(0.01f));
  }

  @Test
  void troop_withNoTarget_shouldReturnMaxDistance() {
    Troop troop = Troop.builder().build();

    assertThat(troop.getDistanceToTarget()).isEqualTo(Float.MAX_VALUE);
  }

  @Test
  void troop_shouldHaveUniqueIds() {
    Troop troop1 = Troop.builder().build();
    Troop troop2 = Troop.builder().build();
    Troop troop3 = Troop.builder().build();

    assertThat(troop1.getId()).isNotEqualTo(troop2.getId());
    assertThat(troop2.getId()).isNotEqualTo(troop3.getId());
  }
}
