package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectileTest {

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
  }

  @Test
  void newProjectile_shouldStartAtSourcePosition() {
    Troop source = createTroop(Team.BLUE, 5, 5);
    Troop target = createTroop(Team.RED, 10, 5);

    Projectile projectile = new Projectile(source, target, 50);

    assertThat(projectile.getPosition().getX()).isEqualTo(5);
    assertThat(projectile.getPosition().getY()).isEqualTo(5);
    assertThat(projectile.isActive()).isTrue();
    assertThat(projectile.isHit()).isFalse();
    assertThat(projectile.hasEffects()).isFalse();
  }

  @Test
  void projectile_shouldInheritTeamFromSource() {
    Troop source = createTroop(Team.BLUE, 5, 5);
    Troop target = createTroop(Team.RED, 10, 5);

    Projectile projectile = new Projectile(source, target, 50);

    assertThat(projectile.getTeam()).isEqualTo(Team.BLUE);
  }

  @Test
  void update_shouldMoveTowardTarget() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    Projectile projectile = new Projectile(source, target, 50);
    float initialX = projectile.getPosition().getX();

    projectile.update(0.1f);

    assertThat(projectile.getPosition().getX()).isGreaterThan(initialX);
    assertThat(projectile.isActive()).isTrue();
  }

  @Test
  void update_shouldReturnTrueWhenHittingTarget() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 1, 0); // Very close

    Projectile projectile = new Projectile(source, target, 50, 0, 100f, null); // Fast projectile

    boolean hit = projectile.update(1.0f);

    assertThat(hit).isTrue();
    assertThat(projectile.isHit()).isTrue();
    assertThat(projectile.isActive()).isFalse();
  }

  @Test
  void update_shouldDeactivateWhenTargetDies() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 100, 0);

    Projectile projectile = new Projectile(source, target, 50);

    // Kill the target
    target.getHealth().takeDamage(1000);

    boolean hit = projectile.update(0.1f);

    assertThat(hit).isFalse();
    assertThat(projectile.isActive()).isFalse();
  }

  @Test
  void hasAoe_shouldReturnTrueWhenAoeRadiusSet() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    Projectile noAoe = new Projectile(source, target, 50, 0);
    Projectile withAoe = new Projectile(source, target, 50, 2.5f);

    assertThat(noAoe.hasAoe()).isFalse();
    assertThat(withAoe.hasAoe()).isTrue();
    assertThat(withAoe.getAoeRadius()).isEqualTo(2.5f);
  }

  @Test
  void projectile_shouldUseCustomSpeed() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 100, 0);

    Projectile slowProjectile = new Projectile(source, target, 50, 0, 5f, null);
    Projectile fastProjectile = new Projectile(source, target, 50, 0, 50f, null);

    slowProjectile.update(0.1f);
    fastProjectile.update(0.1f);

    // Fast projectile should have traveled further
    assertThat(fastProjectile.getPosition().getX())
        .isGreaterThan(slowProjectile.getPosition().getX());
  }

  @Test
  void projectile_shouldCarryEffects() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    List<EffectStats> effects = List.of(
        EffectStats.builder().type(StatusEffectType.SLOW).duration(2f).intensity(0.5f).build()
    );

    Projectile projectile = new Projectile(source, target, 50, 0, 15f, effects);

    assertThat(projectile.hasEffects()).isTrue();
    assertThat(projectile.getEffects()).hasSize(1);
    assertThat(projectile.getEffects().get(0).getType()).isEqualTo(StatusEffectType.SLOW);
  }

  @Test
  void resetIdCounter_shouldResetIds() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    Projectile p1 = new Projectile(source, target, 50);
    Projectile p2 = new Projectile(source, target, 50);

    assertThat(p2.getId()).isGreaterThan(p1.getId());

    Projectile.resetIdCounter();

    Projectile p3 = new Projectile(source, target, 50);
    assertThat(p3.getId()).isEqualTo(1);
  }

  private Troop createTroop(Team team, float x, float y) {
    return Troop.builder()
        .name("Test")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(100))
        .build();
  }
}
