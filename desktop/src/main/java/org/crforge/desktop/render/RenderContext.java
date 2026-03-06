package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import lombok.Getter;

/**
 * Shared rendering resources used by all sub-renderers.
 * Holds the ShapeRenderer, SpriteBatch, fonts, and GlyphLayout.
 */
@Getter
public class RenderContext {

  private final ShapeRenderer shapeRenderer;
  private final SpriteBatch spriteBatch;
  private final BitmapFont font;
  private final BitmapFont entityNameFont;
  private final GlyphLayout glyphLayout;

  public RenderContext() {
    this.shapeRenderer = new ShapeRenderer();
    this.spriteBatch = new SpriteBatch();

    this.font = new BitmapFont();
    this.font.setColor(Color.WHITE);

    this.entityNameFont = new BitmapFont();
    this.entityNameFont.setColor(Color.WHITE);
    this.entityNameFont.getData().setScale(RenderConstants.ENTITY_NAME_FONT_SCALE);

    this.glyphLayout = new GlyphLayout();
  }

  /** Sets the projection matrix on both the ShapeRenderer and SpriteBatch. */
  public void setProjection(OrthographicCamera camera) {
    shapeRenderer.setProjectionMatrix(camera.combined);
    spriteBatch.setProjectionMatrix(camera.combined);
  }

  public void dispose() {
    shapeRenderer.dispose();
    spriteBatch.dispose();
    font.dispose();
    entityNameFont.dispose();
  }
}
