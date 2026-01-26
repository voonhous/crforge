package org.crforge.core.arena;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GridDeploymentTest {

  @Test
  void shouldSnapToGridCenter() {
    float rawX = 5.8f;
    float rawY = 10.2f;

    // Simulate the logic in DebugGameScreen
    float snappedX = (float) Math.floor(rawX) + 0.5f;
    float snappedY = (float) Math.floor(rawY) + 0.5f;

    assertThat(snappedX).isEqualTo(5.5f);
    assertThat(snappedY).isEqualTo(10.5f);
  }

  @Test
  void shouldHandleEdgeCases() {
    // Left edge
    float rawX1 = 0.0f;
    float snappedX1 = (float) Math.floor(rawX1) + 0.5f;
    assertThat(snappedX1).isEqualTo(0.5f);

    // Right edge (Arena width is 18, so valid indices are 0-17)
    // 17.9 should map to 17.5
    float rawX2 = 17.9f;
    float snappedX2 = (float) Math.floor(rawX2) + 0.5f;
    assertThat(snappedX2).isEqualTo(17.5f);
  }
}
