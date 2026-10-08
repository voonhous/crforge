package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.InitialDelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wizard cards of a newer data version name their unit in a list with a deploy delay, deploy as
 * a spell and make their area effect as well: the card places the wizard as a troop card does, but
 * its search does not snap to the unit; the wizard waits its listed delay before it deploys; and
 * the card's area effect is made at the placed point after the wizard. Each scene plays the Electro
 * Wizard of the configured tables rewritten in that form: listing ElectroWizard with offset 0 and a
 * 50 ms delay, its area effect no longer making the wizard as it starts. The wizard's row and the
 * Knight's it is compared with are written with a deploy time of 1000 ms, and the wizard's with 279
 * hit points.
 */
class BattleWizardDeployTest {

  /** The level the scene's cards are played at, as the recorded play's items give it. */
  private static final int LEVEL = 1;

  /** The tick the play runs on. */
  private static final int PLAY_TICK = 221;

  /** The deploy time written into the wizard's and the Knight's rows. */
  private static final int DEPLOY_TIME = 1000;

  /** The hit points written into the wizard's row. */
  private static final int WIZARD_HIT_POINTS = 279;

  /** The configured tables with the Electro Wizard card in the listed form. */
  private static GameTables listedWizard(Path folder) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    GameData.altered(
        folder,
        "spells_characters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "ElectroWizard");
          columns.putArray("SummonCharactersList").add("ElectroWizard");
          columns.putArray("SummonCharactersOffsetsX").add(0);
          columns.putArray("SummonCharactersOffsetsY").add(0);
          columns.putArray("SummonCharactersDelayList").add(50);
          columns.put("SummonNumber", 1);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "ElectroWizard")
              .put("DeployTime", DEPLOY_TIME)
              .put("Hitpoints", WIZARD_HIT_POINTS);
          GameData.columns(rows, "Knight").put("DeployTime", DEPLOY_TIME);
        });
    Path file = folder.resolve("area_effect_objects.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    GameData.columns((ObjectNode) document.get("rows"), "ElectroWizardZap")
        .remove("OnStartingAction");
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }

  /**
   * The tick in whose step a played unit leaves its deploying state, stepping up to the given tick;
   * a step runs the tick the battle's counter shows before it.
   */
  private static int deployEnd(Standard1v1Battle match, CharacterEntity unit, int last) {
    while (match.getBattle().getTick() < last) {
      match.getBattle().step();
      if (unit.getView().getState() != InitialDelay.DEPLOYING) {
        return match.getBattle().getTick() - 1;
      }
    }
    return -1;
  }

  @Test
  @DisplayName(
      "the listed wizard card is a troop card that deploys as a spell and makes its area effect")
  void theCardIsATroopCard(@TempDir Path folder) throws IOException {
    DeployCard card =
        new Standard1v1Battle(listedWizard(folder)).getWorld().getRecords().card("ElectroWizard");
    assertThat(card.spell()).isFalse();
    assertThat(card.unit().name()).isEqualTo("ElectroWizard");
    assertThat(card.listed()).singleElement().extracting(DeployCard.Listed::delayMs).isEqualTo(50);
    assertThat(card.areaEffect()).isEqualTo("ElectroWizardZap");
    assertThat(card.spellAsDeploy()).isTrue();
  }

  @Test
  @DisplayName(
      "the wizard stands on the tile centre less one, waits 50 ms and then deploys, one tick behind"
          + " a Knight, and the zap is made after it")
  void theWizardWaitsItsDelay(@TempDir Path folder) throws IOException {
    GameTables tables = listedWizard(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.play(
        PLAY_TICK,
        match.getWorld().getRecords().card("ElectroWizard"),
        LEVEL,
        0,
        3300,
        12100,
        "wizard");
    // What the play's command pass has handed the holder, read by a command queued after it.
    List<String> made = new ArrayList<>();
    match
        .getBattle()
        .queue(
            new BattleCommand() {
              @Override
              public int tick() {
                return PLAY_TICK;
              }

              @Override
              public void execute(Battle target) {
                for (BattleEntity entity : target.getHolder().queued()) {
                  made.add(
                      entity instanceof AreaEffectEntity a
                          ? a.getData().name()
                          : ((WorldEntity) entity).getData().name());
                }
              }
            });
    while (match.getBattle().getTick() <= PLAY_TICK) {
      match.getBattle().step();
    }
    assertThat(made)
        .as("the zap is made after the wizard")
        .containsExactly("ElectroWizard", "ElectroWizardZap");
    CharacterEntity wizard = match.getPlays().get(0).units().get(0);
    assertThat(wizard.getView().getX()).isEqualTo(3499);
    assertThat(wizard.getView().getY()).isEqualTo(12500);
    assertThat(wizard.getHitPoints().getHitPoints())
        .as("its row's, at the first level")
        .isEqualTo(WIZARD_HIT_POINTS);

    // Both deploy the 1000 ms written into their rows, twenty ticks; the wizard one tick later.
    Standard1v1Battle knights = new Standard1v1Battle(tables, LEVEL, false);
    knights.play(
        PLAY_TICK, knights.getWorld().getRecords().card("Knight"), LEVEL, 0, 3300, 12100, "knight");
    while (knights.getBattle().getTick() <= PLAY_TICK) {
      knights.getBattle().step();
    }
    CharacterEntity knight = knights.getPlays().get(0).units().get(0);
    assertThat(deployEnd(knights, knight, 300)).isEqualTo(240);
    assertThat(deployEnd(match, wizard, 300)).isEqualTo(241);
  }
}
