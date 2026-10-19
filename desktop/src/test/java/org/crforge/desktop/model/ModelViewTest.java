/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import org.crforge.env.Schema;
import org.junit.jupiter.api.Test;

class ModelViewTest {

  private static final String[] HAND = {"A", "B", "C", "D"};

  /** A decision: the given first choice odds, slot 1's tiles all on two tiles of its frame. */
  private static ModelPolicy.Decision decision(int action, float win, float... firstOdds) {
    float[] first = new float[1 + Schema.HAND_SLOTS + Schema.ABILITY_SLOTS];
    Arrays.fill(first, -1e9f);
    for (int i = 0; i < firstOdds.length; i++) {
      first[i] = firstOdds[i] > 0 ? (float) Math.log(firstOdds[i]) : -1e9f;
    }
    float[][] tiles = new float[Schema.HAND_SLOTS][Schema.TILES];
    for (float[] slot : tiles) {
      Arrays.fill(slot, -1e9f);
    }
    tiles[1][0] = (float) Math.log(0.75);
    tiles[1][Schema.COLS + 1] = (float) Math.log(0.25);
    return new ModelPolicy.Decision(first, tiles, 0.3f, win, action);
  }

  @Test
  void aSeatsTilesLieOnTheBattleTilesItsPlaysLandOn() {
    // Side 0's frame mirrors the width, side 1's the length (each seat's back line at row 0).
    assertThat(ModelView.battleTile(0, 0)).isEqualTo(Schema.COLS - 1);
    assertThat(ModelView.battleTile(0, Schema.COLS + 2)).isEqualTo(2 * Schema.COLS - 3);
    assertThat(ModelView.battleTile(1, 0)).isEqualTo((Schema.ROWS - 1) * Schema.COLS);
    assertThat(ModelView.battleTile(1, Schema.COLS + 2))
        .isEqualTo((Schema.ROWS - 2) * Schema.COLS + 2);
  }

  @Test
  void aPlayShowsItsSlotsTilesAndTheTilePlayed() {
    int action = 1 + Schema.TILES + Schema.COLS + 1; // slot 1, tile (1, 1)
    ModelView view = ModelView.of(decision(action, 0f, 0.5f, 0.1f, 0.4f), 1, HAND, "red", true);
    assertThat(view.slot()).isEqualTo(1);
    assertThat(view.card()).isEqualTo("B");
    assertThat(view.played()).isTrue();
    int corner = ModelView.battleTile(1, 0);
    assertThat(view.probability(corner % Schema.COLS, corner / Schema.COLS))
        .isCloseTo(0.75f, within(1e-6f));
    assertThat(view.chosen()).isEqualTo(ModelView.battleTile(1, Schema.COLS + 1));
    assertThat(view.largest()).isCloseTo(0.75f, within(1e-6f));
    assertThat(view.lines().get(0)).isEqualTo("model (red): wait 50%, A 10%, B 40%, C -, D -");
    assertThat(view.lines().get(1)).startsWith("model win estimate 50% (logit 0.00, reading both");
    assertThat(view.lines().get(2)).contains("B, played");
  }

  @Test
  void aWaitShowsTheMostLikelySlotAndAModelWithoutAWinHeadSaysSo() {
    ModelView view =
        ModelView.of(decision(Schema.WAIT, Float.NaN, 0.7f, 0.05f, 0.25f), 0, HAND, "blue", false);
    assertThat(view.slot()).isEqualTo(1);
    assertThat(view.played()).isFalse();
    assertThat(view.chosen()).isEqualTo(-1);
    assertThat(view.probability(Schema.COLS - 1, 0)).isCloseTo(0.75f, within(1e-6f));
    assertThat(view.lines().get(1)).contains("no win estimate");
    assertThat(view.lines().get(2)).contains("its most likely card");
  }

  @Test
  void noSlotOpenShowsNoTiles() {
    ModelView view = ModelView.of(decision(Schema.WAIT, 0f, 1f), 0, HAND, "blue", false);
    assertThat(view.slot()).isEqualTo(-1);
    assertThat(view.tiles()).isNull();
    assertThat(view.largest()).isZero();
    assertThat(view.lines()).hasSize(2);
  }
}
