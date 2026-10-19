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
  void theDefaultsAreTheModelsOnTheRedSide() {
    ModelSettings settings = ModelSettings.parse(new String[] {"--model", "m.onnx"}).orElseThrow();
    assertThat(settings.model()).isEqualTo(Paths.get("m.onnx"));
    assertThat(settings.side()).isEqualTo(1);
    assertThat(settings.deck()).isNull();
    assertThat(settings.slots()).isNull();
    assertThat(settings.level()).isZero();
    assertThat(settings.sample()).isFalse();
    assertThat(settings.seed()).isZero();
  }

  @Test
  void everyArgumentIsRead() {
    ModelSettings settings =
        ModelSettings.parse(
                new String[] {
                  "--model",
                  "m.onnx",
                  "--model-side",
                  "0",
                  "--model-deck",
                  DECK,
                  "--model-slots",
                  "1,2,0,0,0,0,0,0",
                  "--player-deck",
                  DECK,
                  "--player-slots",
                  "0,0,0,0,0,0,0,1",
                  "--level",
                  "16",
                  "--tower-level",
                  "15",
                  "--model-sample",
                  "--seed",
                  "9"
                })
            .orElseThrow();
    assertThat(settings.side()).isZero();
    assertThat(settings.deck()).isEqualTo(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
    assertThat(settings.slots()).containsExactly(1, 2, 0, 0, 0, 0, 0, 0);
    assertThat(settings.playerDeck()).hasSize(8);
    assertThat(settings.playerSlots()).containsExactly(0, 0, 0, 0, 0, 0, 0, 1);
    assertThat(settings.level()).isEqualTo(16);
    assertThat(settings.towerLevel()).isEqualTo(15);
    assertThat(settings.sample()).isTrue();
    assertThat(settings.seed()).isEqualTo(9);
  }

  @Test
  void aBadArgumentIsRefusedNamingIt() {
    assertThatThrownBy(() -> ModelSettings.parse(new String[] {"--model"}))
        .hasMessageContaining("--model needs a value");
    assertThatThrownBy(
            () -> ModelSettings.parse(new String[] {"--model", "m", "--model-side", "2"}))
        .hasMessageContaining("0 or 1");
    assertThatThrownBy(
            () -> ModelSettings.parse(new String[] {"--model", "m", "--model-deck", "A,B"}))
        .hasMessageContaining("8 card row names");
    assertThatThrownBy(() -> ModelSettings.parse(new String[] {"--model", "m", "--level", "0"}))
        .hasMessageContaining("counted from 1");
    assertThatThrownBy(() -> ModelSettings.parse(new String[] {"--model-deck", DECK}))
        .hasMessageContaining("name it with --model");
  }
}
