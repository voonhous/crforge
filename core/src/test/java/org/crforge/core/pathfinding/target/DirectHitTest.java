package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a landed hit reaches its target: the two damages, the hit id and the direction. */
class DirectHitTest {

  private TargetingState t;
  private TargetView target;
  private final List<String> dealt = new ArrayList<>();
  private final HitQueries queries =
      new HitQueries() {
        private int hitCounter;

        @Override
        public int damage() {
          return 202;
        }

        @Override
        public int nextHitId() {
          return ++hitCounter;
        }

        @Override
        public void dealDamage(
            TargetView hit, int damage, int hitId, int directionX, int directionY) {
          dealt.add("%d %d %d %d".formatted(damage, hitId, directionX, directionY));
        }
      };

  @BeforeEach
  void setUp() {
    GridEntity owner = new GridEntity();
    owner.setName("owner");
    owner.setX(1000);
    owner.setY(1000);
    t = new TargetingState();
    t.setOwner(owner);
    t.setConfig(TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    t.setLastReferenceX(1000);
    t.setLastReferenceY(3000);
    GridEntity entity = new GridEntity();
    entity.setName("target");
    entity.setX(1000);
    entity.setY(3000);
    target = new TargetView(entity, TargetingConfig.forUnit(0, 0, 0, 0, 0, true, true));
  }

  @Test
  @DisplayName("an ordinary target takes the plain damage, from the owner towards the reference")
  void anOrdinaryTargetTakesThePlainDamage() {
    DirectHit.resolve(t, target, 202, false, queries);

    assertThat(dealt).containsExactly("202 1 0 2000");
  }

  @Test
  @DisplayName("a crown tower takes the crown-tower damage")
  void aCrownTowerTakesTheCrownTowerDamage() {
    t.setConfig(t.getConfig().toBuilder().crownTowerDamagePercent(-70).build());
    target.setCrownTowerTarget(true);

    DirectHit.resolve(t, target, 202, false, queries);

    assertThat(dealt).as("30 percent of 202, rounded up").containsExactly("61 1 0 2000");
  }

  @Test
  @DisplayName("an ordinary target is not spared by the owner's crown-tower percentage")
  void anOrdinaryTargetIgnoresTheCrownTowerPercentage() {
    t.setConfig(t.getConfig().toBuilder().crownTowerDamagePercent(-70).build());

    DirectHit.resolve(t, target, 202, false, queries);

    assertThat(dealt).containsExactly("202 1 0 2000");
  }

  @Test
  @DisplayName("a cancelled hit and a hit with no target still count a hit id and touch nothing")
  void aCancelledHitTouchesNothing() {
    DirectHit.resolve(t, target, 202, true, queries);
    DirectHit.resolve(t, null, 202, false, queries);

    assertThat(dealt).isEmpty();
    assertThat(queries.nextHitId()).as("both hits took an id before they stopped").isEqualTo(3);
  }

  @Test
  @DisplayName("the crown-tower rule rounds up and never takes more than the whole damage")
  void theCrownTowerRuleRoundsUp() {
    assertThat(DirectHit.crownTowerDamage(0, 202)).isEqualTo(202);
    assertThat(DirectHit.crownTowerDamage(-70, 202)).isEqualTo(61);
    assertThat(DirectHit.crownTowerDamage(-100, 202)).isZero();
    assertThat(DirectHit.crownTowerDamage(-250, 202)).as("floored at -100").isZero();
    assertThat(DirectHit.crownTowerDamage(50, 202)).as("a raise is allowed too").isEqualTo(303);
  }
}
