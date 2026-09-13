package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.card.DeployFormation;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that default-layout multi-unit deploy formations keep their pre-migration physical
 * layout in integer game units: explicit offsets are converted exactly once and the legacy circular
 * fallback still uses the raw summonRadius / 355 scaling. Skeleton Army uses the radial layout and
 * is covered by {@link SkeletonArmyFormationTest}.
 */
class FormationUnitMigrationTest {

  private TroopFactory troopFactory;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    troopFactory = new TroopFactory(new GameState());
  }

  @Test
  void barbariansOffsets_keepThreeDecimalTilePrecision() {
    Card barbarians = Objects.requireNonNull(CardRegistry.get("barbarians"));

    // cards.json: [[0.0, 1.2], [1.141, 0.371], [0.705, -0.971], [-0.705, -0.971], [-1.141, 0.371]]
    assertThat(barbarians.getFormationOffsets().get(0)).containsExactly(0, 1200);
    assertThat(barbarians.getFormationOffsets().get(1)).containsExactly(1141, 371);
    assertThat(barbarians.getFormationOffsets().get(2)).containsExactly(705, -971);
    assertThat(barbarians.getFormationOffsets().get(3)).containsExactly(-705, -971);
    assertThat(barbarians.getFormationOffsets().get(4)).containsExactly(-1141, 371);
  }

  @Test
  void circularFallback_matchesLegacyTileAlgorithmWithinOneUnit() {
    // Barbarians without explicit offsets exercises the raw summonRadius (700) fallback
    Card barbarians = Objects.requireNonNull(CardRegistry.get("barbarians"));
    Card fallbackCard = barbarians.toBuilder().formationOffsets(null).build();
    int total = fallbackCard.getTotalDeployCount();
    int baseX = tiles(9.5);
    int baseY = tiles(10.5);

    for (int i = 0; i < total; i++) {
      Troop troop = createFormationTroop(Team.BLUE, fallbackCard, baseX, baseY, i);
      float[] legacy = legacyDeployOffsetTiles(i, total, fallbackCard.getSummonRadius());

      assertThat(troop.getPosition().getX() - baseX)
          .as("fallback x %d", i)
          .isBetween(tiles(legacy[0]) - 1, tiles(legacy[0]) + 1);
      assertThat(troop.getPosition().getY() - baseY)
          .as("fallback y %d", i)
          .isBetween(tiles(legacy[1]) - 1, tiles(legacy[1]) + 1);
    }
    // Five units at 700 / 355 = 1.972 tiles: the first (odd count) is straight ahead
    Troop first = createFormationTroop(Team.BLUE, fallbackCard, baseX, baseY, 0);
    assertThat(first.getPosition().getX()).isEqualTo(baseX);
    assertThat(first.getPosition().getY()).isEqualTo(baseY + 1972);
  }

  private Troop createFormationTroop(Team team, Card card, int baseX, int baseY, int index) {
    return troopFactory.createTroop(
        team, card.getUnitStats(), baseX, baseY, null, 1, index, DeployFormation.of(card));
  }

  /**
   * The pre-migration fallback, reproduced verbatim in tile floats: radius = summonRadius / 355,
   * circle starting at pi/2 for odd counts, rounded to three decimals.
   */
  private static float[] legacyDeployOffsetTiles(int index, int total, float summonRadius) {
    float r = summonRadius / 355.0f;
    float startAngle = (total % 2 == 0) ? 0f : (float) (Math.PI / 2.0);
    float step = (float) (2.0 * Math.PI / total);
    float angle = startAngle + index * step;
    float x = Math.round(r * (float) Math.cos(angle) * 1000f) / 1000f;
    float y = Math.round(r * (float) Math.sin(angle) * 1000f) / 1000f;
    return new float[] {x, y};
  }

  @Test
  void legacyDivisor_isNamedAsLegacyNotCoordinateScale() {
    assertThat(FormationLayout.LEGACY_SUMMON_RADIUS_DIVISOR).isEqualTo(355.0f);
  }
}
