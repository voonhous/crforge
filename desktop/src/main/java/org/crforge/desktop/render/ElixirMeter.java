/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/** Ten segments with a fractional fill, drawn without introducing a second renderer. */
final class ElixirMeter extends Actor {
  private final Drawable pixel;
  private float value;

  ElixirMeter(Drawable pixel) {
    this.pixel = pixel;
  }

  void setValue(float value) {
    this.value = Math.max(0, Math.min(10, value));
  }

  @Override
  public void draw(Batch batch, float parentAlpha) {
    float previous = batch.getPackedColor();
    float segment = (getWidth() - 18) / 10;
    for (int i = 0; i < 10; i++) {
      float x = getX() + i * (segment + 2);
      batch.setColor(0.21f, 0.20f, 0.31f, parentAlpha);
      pixel.draw(batch, x, getY(), segment, getHeight());
      float share = Math.max(0, Math.min(1, value - i));
      batch.setColor(0.70f, 0.47f, 0.94f, parentAlpha);
      pixel.draw(batch, x, getY(), segment * share, getHeight());
    }
    batch.setPackedColor(previous);
  }
}
