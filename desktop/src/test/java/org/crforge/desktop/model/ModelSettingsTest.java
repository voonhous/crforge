/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelSettingsTest {

  private static final String DECK = "A,B,C,D,E,F,G,H";

  @Test
  void noModelNamedPlaysNoModel() {
    assertThat(ModelSettings.parse(new String[] {})).isEmpty();
    assertThat(ModelSettings.parse(new String[] {"--replay", "x.json"})).isEmpty();
  }

  @Test
  void theDefaultsAreTheModelsOnTheRedSideAtTheTrainedLevel() {
    ModelSettings settings = ModelSettings.parse(new String[] {"--model", "m.onnx"}).orElseThrow();
    assertThat(settings.model()).isEqualTo(Paths.get("m.onnx"));
    assertThat(settings.side()).isEqualTo(1);
    assertThat(settings.deckFile()).isNull();
    assertThat(settings.modelDeck()).isNull();
    assertThat(settings.myDeck()).isNull();
    assertThat(settings.level()).isEqualTo(ModelSettings.LEVEL);
    assertThat(settings.towerLevel()).isEqualTo(ModelSettings.LEVEL);
    assertThat(settings.sample()).isFalse();
    assertThat(settings.seed()).isZero();
  }

  @Test
  void everyArgumentIsRead() {
    ModelSettings settings =
        ModelSettings.parse(
                new String[] {
                  "--model", "m.onnx", "--model-side", "0", "--deck-file", "d.json",
                  "--model-deck", "first", "--my-deck", DECK, "--my-slots", "0,0,0,0,0,0,0,1",
                  "--level", "14", "--model-sample", "--seed", "9"
                })
            .orElseThrow();
    assertThat(settings.side()).isZero();
    assertThat(settings.deckFile()).isEqualTo(Paths.get("d.json"));
    assertThat(settings.modelDeck()).isEqualTo("first");
    assertThat(settings.myDeck()).isEqualTo(DECK);
    assertThat(settings.mySlots()).containsExactly(0, 0, 0, 0, 0, 0, 0, 1);
    assertThat(settings.level()).isEqualTo(14);
    assertThat(settings.towerLevel()).as("the card level by default").isEqualTo(14);
    assertThat(settings.sample()).isTrue();
    assertThat(settings.seed()).isEqualTo(9);
    assertThat(
            ModelSettings.parse(new String[] {"--model", "m", "--tower-level", "12"})
                .orElseThrow()
                .towerLevel())
        .isEqualTo(12);
    assertThat(ModelSettings.cards(DECK, "x"))
        .isEqualTo(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
  }

  @Test
  void aBadArgumentIsRefusedNamingIt() {
    assertThatThrownBy(() -> ModelSettings.parse(new String[] {"--model"}))
        .hasMessageContaining("--model needs a value");
    assertThatThrownBy(
            () -> ModelSettings.parse(new String[] {"--model", "m", "--model-side", "2"}))
        .hasMessageContaining("0 or 1");
    assertThatThrownBy(() -> ModelSettings.cards("A,B", "--model-deck"))
        .hasMessageContaining("8 card row names");
    assertThatThrownBy(() -> ModelSettings.parse(new String[] {"--model", "m", "--level", "0"}))
        .hasMessageContaining("counted from 1");
    assertThatThrownBy(() -> ModelSettings.parse(new String[] {"--model-deck", DECK}))
        .hasMessageContaining("name it with --model");
    assertThatThrownBy(
            () ->
                ModelSettings.parse(
                    new String[] {
                      "--model", "m", "--model-deck", "top", "--model-slots", "0,0,0,0,0,0,0,0"
                    }))
        .hasMessageContaining("has its own slots");
  }
}
