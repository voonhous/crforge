/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkspaceViewportTest {
  @Test
  void fitsPortraitArenaWithoutStretchingAtWideAndTallWindowSizes() {
    assertThat(WorkspaceViewport.fit(14, 100, 800, 768))
        .isEqualTo(new WorkspaceViewport(198, 100, 432, 768));
    assertThat(WorkspaceViewport.fit(20, 50, 216, 600))
        .isEqualTo(new WorkspaceViewport(20, 158, 216, 384));
  }

  @Test
  void onlyArenaPixelsAcceptInputNotLetterboxingOrAdjacentControls() {
    WorkspaceViewport viewport = WorkspaceViewport.fit(14, 100, 800, 768);
    assertThat(viewport.contains(198, 999 - 100, 1000)).isTrue();
    assertThat(viewport.contains(629, 999 - 867, 1000)).isTrue();
    assertThat(viewport.contains(197, 500, 1000)).isFalse();
    assertThat(viewport.contains(630, 500, 1000)).isFalse();
    assertThat(viewport.contains(300, 999 - 99, 1000)).isFalse();
    assertThat(viewport.contains(300, 999 - 868, 1000)).isFalse();
  }
}
