/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A screen's view settings: the replay viewer opens flipped and F flips it back and forth, the
 * Ladder screen stands and F leaves it standing, and T hides and shows the annotations on both.
 */
class ViewStateTest {

  @Test
  @DisplayName("a replay opens flipped, side 1 at the bottom, and F flips it back and forth")
  void aReplayOpensFlipped() {
    ViewState view = ViewState.replay();

    assertThat(view.isFlippable()).isTrue();
    assertThat(view.getOrientation()).isEqualTo(ViewOrientation.FLIPPED);
    assertThat(view.getOrientation().bottomSide()).isEqualTo(1);
    assertThat(view.statusLines())
        .containsExactly("view: side 1 at bottom (F flips)", ViewState.ANNOTATIONS_NOTE);

    assertThat(view.flip()).isTrue();
    assertThat(view.getOrientation()).isEqualTo(ViewOrientation.STANDARD);
    assertThat(view.statusLines()).first().isEqualTo("view: side 0 at bottom (F flips)");

    assertThat(view.flip()).isTrue();
    assertThat(view.getOrientation()).isEqualTo(ViewOrientation.FLIPPED);
  }

  @Test
  @DisplayName("the Ladder screen stands, side 0 at the bottom, and F does not flip it")
  void theLadderScreenStands() {
    ViewState view = ViewState.ladder();

    assertThat(view.isFlippable()).isFalse();
    assertThat(view.getOrientation()).isEqualTo(ViewOrientation.STANDARD);

    assertThat(view.flip()).isFalse();
    assertThat(view.getOrientation()).isEqualTo(ViewOrientation.STANDARD);
    // No orientation line where F does nothing; the annotations note stays.
    assertThat(view.statusLines()).containsExactly(ViewState.ANNOTATIONS_NOTE);
  }

  @Test
  @DisplayName("the annotations are shown at first on both screens, and T hides and shows them")
  void annotationsToggle() {
    for (ViewState view : new ViewState[] {ViewState.replay(), ViewState.ladder()}) {
      assertThat(view.isAnnotations()).isTrue();
      view.toggleAnnotations();
      assertThat(view.isAnnotations()).isFalse();
      view.toggleAnnotations();
      assertThat(view.isAnnotations()).isTrue();
    }
  }

  @Test
  @DisplayName("hiding the annotations leaves the orientation, and flipping leaves the annotations")
  void theTwoSettingsAreIndependent() {
    ViewState view = ViewState.replay();

    view.toggleAnnotations();
    assertThat(view.getOrientation()).isEqualTo(ViewOrientation.FLIPPED);
    view.flip();
    assertThat(view.isAnnotations()).isFalse();
  }
}
