package org.crforge.core.entity.projectile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.Collections;
import java.util.List;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.AbstractEntity;
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

    assertThat(projectile.getPosition().getX()).isEqualTo(tiles(5));
    assertThat(projectile.getPosition().getY()).isEqualTo(tiles(5));
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
    int initialX = projectile.getPosition().getX();

    projectile.update(0.1f);

    assertThat(projectile.getPosition().getX()).isGreaterThan(initialX);
    assertThat(projectile.isActive()).isTrue();
  }

  @Test
  void update_shouldReturnTrueWhenHittingTarget() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 1, 0); // Very close

    Projectile projectile =
        new Projectile(source, target, 50, 0, tiles(100), null); // Fast projectile

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
    Projectile withAoe = new Projectile(source, target, 50, tiles(2.5));

    assertThat(noAoe.hasAoe()).isFalse();
    assertThat(withAoe.hasAoe()).isTrue();
    assertThat(withAoe.getAoeRadius()).isEqualTo(tiles(2.5));
  }

  @Test
  void projectile_shouldUseCustomSpeed() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 100, 0);

    Projectile slowProjectile = new Projectile(source, target, 50, 0, tiles(5), null);
    Projectile fastProjectile = new Projectile(source, target, 50, 0, tiles(50), null);

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

    List<EffectStats> effects =
        List.of(
            EffectStats.builder().type(StatusEffectType.SLOW).duration(2f).intensity(0.5f).build());

    Projectile projectile = new Projectile(source, target, 50, 0, tiles(15), effects);

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

  @Test
  void positionTargeted_shouldStartAtGivenPosition() {
    Projectile projectile =
        new Projectile(
            Team.BLUE,
            tiles(5),
            tiles(20),
            tiles(5),
            tiles(10),
            100,
            tiles(2.5),
            tiles(8),
            Collections.emptyList());

    assertThat(projectile.getPosition().getX()).isEqualTo(tiles(5));
    assertThat(projectile.getPosition().getY()).isEqualTo(tiles(20));
    assertThat(projectile.isPositionTargeted()).isTrue();
    assertThat(projectile.isActive()).isTrue();
    assertThat(projectile.isHit()).isFalse();
    assertThat(projectile.getSource()).isNull();
    assertThat(projectile.getTarget()).isNull();
    assertThat(projectile.getTeam()).isEqualTo(Team.BLUE);
  }

  @Test
  void positionTargeted_shouldMoveTowardDestination() {
    Projectile projectile =
        new Projectile(
            Team.BLUE,
            tiles(5),
            tiles(20),
            tiles(5),
            tiles(10),
            100,
            tiles(2.5),
            tiles(10),
            Collections.emptyList());

    int initialY = projectile.getPosition().getY();
    projectile.update(0.1f);

    // Should have moved toward y=10 (downward from y=20)
    assertThat(projectile.getPosition().getY()).isLessThan(initialY);
    assertThat(projectile.isActive()).isTrue();
  }

  @Test
  void positionTargeted_shouldHitWhenReachingDestination() {
    // Short distance with fast speed to ensure arrival
    Projectile projectile =
        new Projectile(
            Team.RED,
            tiles(5),
            tiles(11),
            tiles(5),
            tiles(10),
            200,
            tiles(3),
            tiles(50),
            Collections.emptyList());

    boolean hit = projectile.update(1.0f);

    assertThat(hit).isTrue();
    assertThat(projectile.isHit()).isTrue();
    assertThat(projectile.isActive()).isFalse();
    // Should snap to target position
    assertThat(projectile.getPosition().getX()).isEqualTo(tiles(5));
    assertThat(projectile.getPosition().getY()).isEqualTo(tiles(10));
  }

  @Test
  void positionTargeted_shouldNotDeactivateFromNullTarget() {
    // Position-targeted projectiles have null target — they should NOT deactivate
    Projectile projectile =
        new Projectile(
            Team.BLUE,
            tiles(5),
            tiles(20),
            tiles(5),
            tiles(10),
            100,
            tiles(2.5),
            tiles(5),
            Collections.emptyList());

    assertThat(projectile.getTarget()).isNull();

    boolean hit = projectile.update(0.1f);

    // Should still be active and moving, not deactivated due to null target
    assertThat(hit).isFalse();
    assertThat(projectile.isActive()).isTrue();
  }

  @Test
  void nonHomingProjectile_shouldFlyToOriginalTargetPosition() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    Projectile projectile = new Projectile(source, target, 50, tiles(2.0), tiles(15), null);
    projectile.setHoming(false);

    // Move the target to a completely different position after firing
    target.getPosition().set(tiles(10), tiles(10));

    // Update the projectile
    projectile.update(0.1f);

    // Non-homing projectile should move toward the ORIGINAL position (10, 0),
    // not the target's current position (10, 10)
    assertThat(projectile.getPosition().getX()).isGreaterThan(0);
    assertThat(projectile.getPosition().getY()).isZero();
  }

  @Test
  void nonHomingProjectile_shouldContinueFlyingWhenTargetDies() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    Projectile projectile = new Projectile(source, target, 50, tiles(2.0), tiles(15), null);
    projectile.setHoming(false);

    // Kill the target while projectile is in flight
    target.getHealth().takeDamage(1000);
    assertThat(target.isAlive()).isFalse();

    // Non-homing projectile should keep flying to the fixed position
    boolean hit = projectile.update(0.1f);
    assertThat(projectile.isActive()).isTrue();
    assertThat(projectile.getPosition().getX()).isGreaterThan(0);
  }

  @Test
  void nonHomingProjectile_shouldHitAtFixedPosition() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 1, 0); // Very close

    Projectile projectile = new Projectile(source, target, 50, tiles(2.0), tiles(100), null);
    projectile.setHoming(false);

    // Move target far away
    target.getPosition().set(tiles(50), tiles(50));

    // Projectile should reach the original position (1, 0) and hit
    boolean hit = projectile.update(1.0f);
    assertThat(hit).isTrue();
    assertThat(projectile.isHit()).isTrue();
    assertThat(projectile.getPosition().getX()).isEqualTo(tiles(1));
    assertThat(projectile.getPosition().getY()).isZero();
  }

  @Test
  void homingProjectile_shouldTrackMovingTarget() {
    Troop source = createTroop(Team.BLUE, 0, 0);
    Troop target = createTroop(Team.RED, 10, 0);

    // Default is homing=true
    Projectile projectile = new Projectile(source, target, 50, 0, tiles(15), null);

    // Move target upward
    target.getPosition().set(tiles(10), tiles(10));

    projectile.update(0.1f);

    // Homing projectile should be moving toward the NEW position (10, 10),
    // so Y should be positive
    assertThat(projectile.getPosition().getY()).isGreaterThan(0);
  }

  /** Creates a troop at tile coordinates (converted to game units). */
  private Troop createTroop(Team team, float x, float y) {
    return Troop.builder()
        .name("Test")
        .team(team)
        .position(new Position(tiles(x), tiles(y)))
        .health(new Health(100))
        .build();
  }
}
