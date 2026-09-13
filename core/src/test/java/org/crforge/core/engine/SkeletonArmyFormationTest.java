package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.DeployFormation;
import org.crforge.core.card.FormationLayoutType;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Skeleton Army deploys through the integer radial formation helper. Offsets are pre-collision
 * spawn positions (three of the fifteen coincide at the deploy point); physics separates them on
 * the following ticks.
 */
class SkeletonArmyFormationTest {

  /** Blue-side radial offsets for 15 units with the Skeleton's 500-unit collision radius. */
  private static final int[][] RADIAL_OFFSETS = {
    {0, 0},
    {-563, 1266},
    {-2061, 1855},
    {-878, 285},
    {-2297, -241},
    {-400, -231},
    {-1087, -1495},
    {0, 0},
    {288, -1357},
    {1630, -2243},
    {800, -462},
    {2297, -241},
    {439, 142},
    {1374, 1236},
    {0, 0}
  };

  private TroopFactory troopFactory;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    troopFactory = new TroopFactory(new GameState());
  }

  @Test
  void skeletonArmyCard_optsIntoRadialLayoutWithoutExplicitOffsets() {
    Card skeletonArmy = Objects.requireNonNull(CardRegistry.get("skeletonarmy"));

    assertThat(skeletonArmy.getFormationLayout()).isEqualTo(FormationLayoutType.RADIAL);
    assertThat(skeletonArmy.getFormationOffsets()).isNull();
    assertThat(skeletonArmy.getSummonRadius()).isZero();
    assertThat(skeletonArmy.getTotalDeployCount()).isEqualTo(15);
    assertThat(skeletonArmy.getUnitStats().getCollisionRadius()).isEqualTo(500);
  }

  @Test
  void variantsAndOtherMultiUnitCards_keepDefaultLayout() {
    for (String id : List.of("skeletonarmy_ev1", "skeletonarmy_hero", "barbarians", "goblingang")) {
      Card card = Objects.requireNonNull(CardRegistry.get(id), id);
      assertThat(card.getFormationLayout()).as(id).isEqualTo(FormationLayoutType.DEFAULT);
    }
  }

  @Test
  void deploy_blueUsesRadialOffsets_redNegatesBothAxes() {
    Card skeletonArmy = Objects.requireNonNull(CardRegistry.get("skeletonarmy"));
    DeployFormation formation = DeployFormation.of(skeletonArmy);
    int baseX = tiles(9.5);
    int baseY = tiles(12.5);

    for (int i = 0; i < RADIAL_OFFSETS.length; i++) {
      Troop blue = create(Team.BLUE, skeletonArmy, formation, baseX, baseY, i);
      Troop red = create(Team.RED, skeletonArmy, formation, baseX, baseY, i);

      int dx = RADIAL_OFFSETS[i][0];
      int dy = RADIAL_OFFSETS[i][1];
      assertThat(blue.getPosition().getX()).as("blue x %d", i).isEqualTo(baseX + dx);
      assertThat(blue.getPosition().getY()).as("blue y %d", i).isEqualTo(baseY + dy);
      assertThat(red.getPosition().getX()).as("red x %d", i).isEqualTo(baseX - dx);
      assertThat(red.getPosition().getY()).as("red y %d", i).isEqualTo(baseY - dy);
    }
  }

  @Test
  void deployedSkeletons_separateAfterSpawnAndStayInsideArena() {
    GameEngine engine = startMatchWithSkeletonArmy();
    Player blue = engine.getMatch().getPlayers(Team.BLUE).get(0);

    // Open area on blue's side
    engine.queueAction(blue, PlayerActionDTO.play(0, tiles(9.5), tiles(10.5)));
    engine.tick(GameEngine.TICKS_PER_SECOND * 2);

    List<Troop> skeletons = blueTroops(engine);
    assertThat(skeletons).hasSize(15);
    // The three units that spawn on the deploy point are pushed apart by collision resolution
    for (int a = 0; a < skeletons.size(); a++) {
      for (int b = a + 1; b < skeletons.size(); b++) {
        assertThat(skeletons.get(a).getPosition().distanceSquaredTo(skeletons.get(b).getPosition()))
            .as("skeletons %d and %d", a, b)
            .isPositive();
      }
    }
  }

  @Test
  void deployAtArenaCorner_keepsAllSkeletonsInsideBounds() {
    GameEngine engine = startMatchWithSkeletonArmy();
    Player blue = engine.getMatch().getPlayers(Team.BLUE).get(0);

    // Bottom-left deployable tile center; many radial offsets point outside the arena
    engine.queueAction(blue, PlayerActionDTO.play(0, tiles(0.5), tiles(1.5)));
    engine.tick(GameEngine.TICKS_PER_SECOND * 2);

    List<Troop> skeletons = blueTroops(engine);
    assertThat(skeletons).hasSize(15);
    for (Troop skeleton : skeletons) {
      int r = skeleton.getCollisionRadius();
      assertThat(skeleton.getPosition().getX()).isBetween(r, Arena.WIDTH_UNITS - r);
      assertThat(skeleton.getPosition().getY()).isBetween(r, Arena.HEIGHT_UNITS - r);
    }
  }

  private Troop create(
      Team team, Card card, DeployFormation formation, int baseX, int baseY, int index) {
    return troopFactory.createTroop(
        team, card.getUnitStats(), baseX, baseY, null, 1, index, formation);
  }

  private static GameEngine startMatchWithSkeletonArmy() {
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
    return engine;
  }

  private static List<Troop> blueTroops(GameEngine engine) {
    return engine.getGameState().getAliveEntities().stream()
        .filter(e -> e instanceof Troop && e.getTeam() == Team.BLUE)
        .map(e -> (Troop) e)
        .toList();
  }
}
