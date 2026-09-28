package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a hit writes back into the attacker's targeting component, and what it deals. */
class HitApplicationTest {

  /** Damage of one hit at the attacker's level, as the queries answer it. */
  private static final int DAMAGE = 202;

  /** Damage of the attacker's charged hit at its level, as the queries answer it. */
  private static final int CHARGED_DAMAGE = 783;

  private TargetingState t;
  private TargetView target;
  private RecordingQueries queries;

  /** The hits this attacker dealt: target name, damage, hit id and direction. */
  private static final class RecordingQueries implements HitQueries {

    private final List<String> dealt = new ArrayList<>();
    private boolean forbidden;
    private int hitCounter;
    private int charge = NO_CHARGE;
    private int resets;

    @Override
    public int damage() {
      return DAMAGE;
    }

    @Override
    public int chargeProgress() {
      return charge;
    }

    @Override
    public int chargedDamage() {
      return CHARGED_DAMAGE;
    }

    @Override
    public void resetCharge() {
      resets++;
    }

    @Override
    public boolean attackForbidden() {
      return forbidden;
    }

    @Override
    public int nextHitId() {
      return ++hitCounter;
    }

    @Override
    public void dealDamage(TargetView hit, int damage, int hitId, int directionX, int directionY) {
      dealt.add(
          "%s %d %d %d %d"
              .formatted(hit.getEntity().getName(), damage, hitId, directionX, directionY));
    }

    /** The targets the buff on damage was asked for, in order; "none" for a hit on nothing. */
    private final List<String> buffed = new ArrayList<>();

    @Override
    public void buffOnDamage(TargetView hit) {
      buffed.add(hit == null ? "none" : hit.getEntity().getName());
    }
  }

  @BeforeEach
  void setUp() {
    GridEntity owner = new GridEntity();
    owner.setName("owner");
    t = new TargetingState();
    t.setOwner(owner);
    t.setConfig(TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    t.setLoadTimerMs(150);
    target = targetAt(2000);
    t.setReference(target);
    queries = new RecordingQueries();
  }

  /** A target standing the given distance up the arena from the owner. */
  private static TargetView targetAt(int y) {
    GridEntity entity = new GridEntity();
    entity.setName("target");
    entity.setY(y);
    return new TargetView(entity, TargetingConfig.forUnit(0, 0, 0, 0, 0, true, true));
  }

  @Test
  @DisplayName("a hit marks the hit started, reloads the countdown, counts itself and lands")
  void aHitIsRecordedOnTheAttackerAndLands() {
    t.setLastReferenceY(2000);

    boolean nothingLanded = HitApplication.apply(t, target, queries);

    assertThat(nothingLanded).isFalse();
    assertThat(t.isHitStarted()).isTrue();
    assertThat(t.getLoadTimerMs()).isEqualTo(700);
    assertThat(t.getSpecialChargeTimerMs()).as("one hit counted").isEqualTo(1);
    assertThat(t.getAttackBlockTimerMs()).as("no stop time after an attack").isZero();
    assertThat(t.isSpecialLoadPending()).isFalse();
    assertThat(queries.dealt)
        .as("the damage at the owner's level, with the first hit id and the hit's direction")
        .containsExactly("target 202 1 0 2000");
  }

  @Test
  @DisplayName("a loaded special hit clears the charge and the pending load, and deals its damage")
  void aLoadedSpecialHitClearsTheCharge() {
    t.setSpecialLoadPending(true);
    t.setSpecialChargeTimerMs(3);
    // A loaded special attack is fought at the special range, which this unit does not have, so
    // the target has to stand at the owner's feet to be hit at all.
    TargetView close = targetAt(0);

    HitApplication.apply(t, close, queries);

    assertThat(t.getSpecialChargeTimerMs()).as("a special hit clears the count").isZero();
    assertThat(t.isSpecialLoadPending()).isFalse();
    assertThat(queries.dealt)
        .as("the special damage, which this unit answers as its own")
        .hasSize(1);
  }

  @Test
  @DisplayName("a stop time after the attack is stored into the attack block timer")
  void aStopTimeBlocksTheNextAttack() {
    t.setConfig(t.getConfig().toBuilder().stopTimeAfterAttack(300).build());

    HitApplication.apply(t, target, queries);

    assertThat(t.getAttackBlockTimerMs()).isEqualTo(300);
  }

  @Test
  @DisplayName("a unit charging a special attack does not count hits in the charge")
  void aChargingUnitDoesNotCountHits() {
    t.setConfig(t.getConfig().toBuilder().specialChargeTime(2000).build());
    t.setSpecialChargeTimerMs(400);

    HitApplication.apply(t, target, queries);

    assertThat(t.getSpecialChargeTimerMs()).isEqualTo(400);
  }

  @Test
  @DisplayName("a target that walked far past the attack range is missed and takes nothing")
  void aTargetTooFarAwayIsMissed() {
    // The attack range is 1700, the published allowance 1500 more; 3300 is past both.
    TargetView far = targetAt(3300);

    boolean nothingLanded = HitApplication.apply(t, far, queries);

    assertThat(nothingLanded).isTrue();
    assertThat(queries.dealt).isEmpty();
    assertThat(t.isHitStarted()).as("the hit still counts as started").isTrue();
    assertThat(t.getLoadTimerMs()).as("an ordinary unit reloads even so").isEqualTo(700);
  }

  @Test
  @DisplayName("the buff on damage follows a direct hit that lands, not a missed one or a shot")
  void theBuffOnDamageFollowsALandedDirectHit() {
    HitApplication.apply(t, target, queries);
    assertThat(queries.buffed).as("after the damage").containsExactly("target");

    queries.buffed.clear();
    HitApplication.apply(t, targetAt(3300), queries);
    assertThat(queries.buffed).as("a hit cancelled for distance").isEmpty();

    t.setConfig(t.getConfig().toBuilder().hasProjectile(true).build());
    HitApplication.apply(t, target, queries);
    assertThat(queries.buffed).as("a unit that fires hands its hit to the projectile").isEmpty();
  }

  @Test
  @DisplayName("a target just inside the allowance is still hit")
  void aTargetInsideTheAllowanceIsHit() {
    TargetView edge = targetAt(3200);

    assertThat(HitApplication.apply(t, edge, queries)).isFalse();
    assertThat(queries.dealt).hasSize(1);
  }

  @Test
  @DisplayName("a building never cancels a hit for distance")
  void aBuildingNeverCancels() {
    t.setConfig(t.getConfig().toBuilder().isBuilding(true).build());

    assertThat(HitApplication.apply(t, targetAt(30000), queries)).isFalse();
    assertThat(queries.dealt).hasSize(1);
  }

  @Test
  @DisplayName("a dasher immune after its dash never cancels a hit for distance; one without does")
  void aDasherImmuneAfterItsDashNeverCancels() {
    t.setConfig(t.getConfig().toBuilder().dashCooldown(800).dashImmuneToDamageTime(100).build());
    assertThat(HitApplication.apply(t, targetAt(30000), queries)).isFalse();
    assertThat(queries.dealt).hasSize(1);

    t.setConfig(t.getConfig().toBuilder().dashImmuneToDamageTime(0).build());
    assertThat(HitApplication.apply(t, targetAt(30000), queries)).isTrue();
    assertThat(queries.dealt).hasSize(1);
  }

  @Test
  @DisplayName("a wind-up-first unit whose hit missed stays loaded when the match says so")
  void aMissedWindUpFirstHitKeepsTheLoad() {
    t.setConfig(t.getConfig().toBuilder().loadFirstHit(true).build());
    t.setGlobals(
        TargetingGlobals.standard1v1().toBuilder()
            .loadFirstHitKeepLoadedAfterDiscard(true)
            .build());

    boolean nothingLanded = HitApplication.apply(t, targetAt(3300), queries);

    assertThat(nothingLanded).isTrue();
    assertThat(t.getLoadTimerMs()).as("the countdown is not reloaded").isEqualTo(150);
    assertThat(t.isHitStarted()).as("the hit still counts as started").isTrue();
  }

  @Test
  @DisplayName("a unit that may not attack discards the hit before anything else")
  void aForbiddenAttackIsDiscarded() {
    queries.forbidden = true;

    assertThat(HitApplication.apply(t, target, queries)).isTrue();
    assertThat(t.isHitStarted()).as("the flag is raised before the guard").isTrue();
    assertThat(t.getLoadTimerMs()).as("nothing after the guard runs").isEqualTo(150);
    assertThat(queries.dealt).isEmpty();
  }

  @Test
  @DisplayName("a unit that fires a projectile records the hit and deals nothing directly")
  void aProjectileUnitDealsNothingDirectly() {
    t.setConfig(t.getConfig().toBuilder().hasProjectile(true).build());

    assertThat(HitApplication.apply(t, target, queries)).isFalse();
    assertThat(t.getLoadTimerMs()).as("the attacker's own bookkeeping still runs").isEqualTo(700);
    assertThat(queries.dealt).isEmpty();
  }

  @Test
  @DisplayName("a hit with no target left touches nothing but still counts on the attacker")
  void aHitWithoutATargetTouchesNothing() {
    assertThat(HitApplication.apply(t, null, queries)).isFalse();
    assertThat(t.isHitStarted()).isTrue();
    assertThat(queries.dealt).isEmpty();
  }

  @Test
  @DisplayName("a fully charged unit's direct hit deals its charged damage and resets the charge")
  void aChargedHitDealsItsChargedDamage() {
    t.setLastReferenceY(2000);
    queries.charge = 10080;

    HitApplication.apply(t, target, queries);

    assertThat(queries.dealt).containsExactly("target 783 1 0 2000");
    assertThat(queries.resets).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a unit charging but not yet full deals its ordinary damage and still loses the charge")
  void aHalfChargedHitResetsTheCharge() {
    t.setLastReferenceY(2000);
    queries.charge = 5000;

    HitApplication.apply(t, target, queries);

    assertThat(queries.dealt).containsExactly("target 202 1 0 2000");
    assertThat(queries.resets).isEqualTo(1);
  }

  @Test
  @DisplayName("a row that keeps charging after an attack deals the charged damage and keeps it")
  void aRowThatKeepsChargingKeepsTheCharge() {
    t.setLastReferenceY(2000);
    t.setConfig(t.getConfig().toBuilder().keepChargingAfterAttack(true).build());
    queries.charge = 10000;

    HitApplication.apply(t, target, queries);

    assertThat(queries.dealt).containsExactly("target 783 1 0 2000");
    assertThat(queries.resets).isZero();
  }

  @Test
  @DisplayName("a unit that tracks no charge deals its ordinary damage and resets nothing")
  void aUnitWithoutAChargeIsUntouched() {
    t.setLastReferenceY(2000);
    HitApplication.apply(t, target, queries);

    assertThat(queries.dealt).containsExactly("target 202 1 0 2000");
    assertThat(queries.resets).isZero();
  }
}
