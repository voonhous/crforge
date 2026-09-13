package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.core.util.FormationLayout;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that multi-unit deploy formations keep their pre-migration physical layout after moving
 * to integer game units: explicit offsets are converted exactly once, the legacy circular fallback
 * still uses the raw summonRadius / 355 scaling, and deployed units stay inside the arena.
 *
 * <p>These tests intentionally pin the simulator's existing Skeleton Army table. Switching to a
 * different formation layout is a separate behavior change, not part of the unit migration.
 */
class FormationUnitMigrationTest {

  /** Skeleton Army offsets in tiles as shipped in cards.json before the migration (blue side). */
  private static final double[][] LEGACY_SKELETON_ARMY_TILES = {
    {-2.0, 2.0},
    {-2.75, 0.0},
    {-1.75, 0.5},
    {-0.5, 1.25},
    {-1.25, -0.5},
    {-1.75, -1.5},
    {-0.0, 0.0},
    {0.0, -1.5},
    {1.25, 1.5},
    {1.5, 0.5},
    {0.75, -0.75},
    {1.75, -1.0},
    {1.0, -2.0},
    {2.75, -0.25},
    {2.5, -2.5}
  };

  private TroopFactory troopFactory;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    troopFactory = new TroopFactory(new GameState());
  }

  @Test
  void skeletonArmyOffsets_areLegacyTileOffsetsConvertedExactlyOnce() {
    Card skeletonArmy = Objects.requireNonNull(CardRegistry.get("skeletonarmy"));
    List<int[]> offsets = skeletonArmy.getFormationOffsets();

    assertThat(offsets).hasSize(LEGACY_SKELETON_ARMY_TILES.length);
    for (int i = 0; i < offsets.size(); i++) {
      assertThat(offsets.get(i))
          .as("offset %d", i)
          .containsExactly(
              tiles(LEGACY_SKELETON_ARMY_TILES[i][0]), tiles(LEGACY_SKELETON_ARMY_TILES[i][1]));
    }
    // Spot-check magnitudes: a double conversion would give 2,000,000; none would give 2
    assertThat(offsets.get(0)).containsExactly(-2000, 2000);
    assertThat(offsets.get(13)).containsExactly(2750, -250);
  }

  @Test
  void skeletonArmyDeploy_placesEachUnitAtBasePlusOffset_blueAndRedMirrored() {
    Card skeletonArmy = Objects.requireNonNull(CardRegistry.get("skeletonarmy"));
    int total = skeletonArmy.getTotalDeployCount();
    int baseX = tiles(9.5);
    int baseY = tiles(12.5);

    for (int i = 0; i < total; i++) {
      Troop blue = createFormationTroop(Team.BLUE, skeletonArmy, baseX, baseY, i, total);
      Troop red = createFormationTroop(Team.RED, skeletonArmy, baseX, baseY, i, total);

      int dx = tiles(LEGACY_SKELETON_ARMY_TILES[i][0]);
      int dy = tiles(LEGACY_SKELETON_ARMY_TILES[i][1]);
      assertThat(blue.getPosition().getX()).as("blue x %d", i).isEqualTo(baseX + dx);
      assertThat(blue.getPosition().getY()).as("blue y %d", i).isEqualTo(baseY + dy);
      assertThat(red.getPosition().getX()).as("red x %d", i).isEqualTo(baseX - dx);
      assertThat(red.getPosition().getY()).as("red y %d", i).isEqualTo(baseY - dy);
    }
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
      Troop troop = createFormationTroop(Team.BLUE, fallbackCard, baseX, baseY, i, total);
      float[] legacy = legacyDeployOffsetTiles(i, total, fallbackCard.getSummonRadius());

      assertThat(troop.getPosition().getX() - baseX)
          .as("fallback x %d", i)
          .isBetween(tiles(legacy[0]) - 1, tiles(legacy[0]) + 1);
      assertThat(troop.getPosition().getY() - baseY)
          .as("fallback y %d", i)
          .isBetween(tiles(legacy[1]) - 1, tiles(legacy[1]) + 1);
    }
    // Five units at 700 / 355 = 1.972 tiles: the first (odd count) is straight ahead
    Troop first = createFormationTroop(Team.BLUE, fallbackCard, baseX, baseY, 0, total);
    assertThat(first.getPosition().getX()).isEqualTo(baseX);
    assertThat(first.getPosition().getY()).isEqualTo(baseY + 1972);
  }

  @Test
  void skeletonArmyDeployedAtArenaCorner_staysInsideArenaBounds() {
    Card skeletonArmy = Objects.requireNonNull(CardRegistry.get("skeletonarmy"));
    Player blue =
        new Player(
            Team.BLUE, new Deck(new ArrayList<>(Collections.nCopies(8, skeletonArmy))), false);
    Player red =
        new Player(
            Team.RED, new Deck(new ArrayList<>(Collections.nCopies(8, skeletonArmy))), false);
    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(blue);
    match.addPlayer(red);
    GameEngine engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();

    // Bottom-left deployable tile center; most formation offsets point outside the arena
    engine.queueAction(blue, PlayerActionDTO.play(0, tiles(0.5), tiles(1.5)));
    engine.tick(GameEngine.TICKS_PER_SECOND * 2);

    List<Troop> skeletons =
        engine.getGameState().getAliveEntities().stream()
            .filter(e -> e instanceof Troop && e.getTeam() == Team.BLUE)
            .map(e -> (Troop) e)
            .toList();
    assertThat(skeletons).hasSize(skeletonArmy.getTotalDeployCount());
    for (Troop skeleton : skeletons) {
      int r = skeleton.getCollisionRadius();
      assertThat(skeleton.getPosition().getX()).isBetween(r, Arena.WIDTH_UNITS - r);
      assertThat(skeleton.getPosition().getY()).isBetween(r, Arena.HEIGHT_UNITS - r);
    }
  }

  private Troop createFormationTroop(
      Team team, Card card, int baseX, int baseY, int index, int total) {
    return troopFactory.createTroop(
        team,
        card.getUnitStats(),
        baseX,
        baseY,
        null,
        1,
        index,
        total,
        card.getSummonRadius(),
        card.getFormationOffsets());
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
