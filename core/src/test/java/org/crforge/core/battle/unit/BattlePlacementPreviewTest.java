package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The placement preview: what a play of a card would come to against the battle as it stands, the
 * same placement the play itself works out when nothing has moved in between, with nothing changed.
 */
class BattlePlacementPreviewTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName("a preview places the card where its play then places it, unit for unit")
  void aPreviewIsThePlaysPlacement() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    DeployCard army = GameData.card("SkeletonArmy");

    // A point off the tile grid, so the snap moves it.
    CardPlacement.Result preview = battle.previewPlacement(army, 0, 3700, 9800);
    battle.play(1, army, LEVEL, 0, 3700, 9800, "army");
    battle.getBattle().step();
    battle.getBattle().step();

    Standard1v1Battle.Play play = battle.getPlays().get(0);
    assertThat(preview.placed()).isTrue();
    assertThat(preview.x()).isEqualTo(play.result().x());
    assertThat(preview.y()).isEqualTo(play.result().y());
    assertThat(preview.units()).hasSameSizeAs(play.units());
    for (int i = 0; i < preview.units().size(); i++) {
      assertThat(preview.units().get(i).x()).isEqualTo(play.result().units().get(i).x());
      assertThat(preview.units().get(i).y()).isEqualTo(play.result().units().get(i).y());
    }
  }

  @Test
  @DisplayName("a preview of a point left of the map is refused and changes nothing")
  void aRefusedPreviewChangesNothing() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(KNIGHTS, KNIGHTS, 0, 0);
    int before = battle.getBattle().getHolder().entities().size();
    int elixir = battle.getMatch().side(0).getElixir();

    CardPlacement.Result preview = battle.previewPlacement(GameData.card("Knight"), 0, -500, 9000);

    assertThat(preview.placed()).isFalse();
    assertThat(battle.getPlays()).isEmpty();
    assertThat(battle.getBattle().getHolder().queued()).isEmpty();
    assertThat(battle.getBattle().getHolder().entities()).hasSize(before);
    assertThat(battle.getMatch().side(0).getElixir()).isEqualTo(elixir);
  }
}
