package org.crforge.desktop.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import lombok.Getter;

/**
 * Shared rendering resources used by all sub-renderers. Holds the ShapeRenderer, SpriteBatch,
 * fonts, and GlyphLayout. All fonts are generated via FreeType at exact pixel sizes for crisp
 * rendering.
 */
@Getter
public class RenderContext {

  private static final String FONT_PATH = "fonts/RobotoMono-Regular.ttf";

  private final ShapeRenderer shapeRenderer;
  private final SpriteBatch spriteBatch;

  /** General HUD text (15px) -- entity counts, elixir, debug status, card cost. */
  private final BitmapFont font;

  /** Entity name labels, card names, HP numbers, spawner timers (11px). */
  private final BitmapFont entityNameFont;

  /** Floating damage popups (13px). */
  private final BitmapFont damageFont;

  /** Game timer display (18px). */
  private final BitmapFont timerFont;

  /** Win/draw headline (30px). */
  private final BitmapFont titleFont;

  private final GlyphLayout glyphLayout;

  public RenderContext() {
    this.shapeRenderer = new ShapeRenderer();
    this.spriteBatch = new SpriteBatch();

    FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.classpath(FONT_PATH));

    this.entityNameFont = generateFont(generator, 9);
    this.damageFont = generateFont(generator, 11);
    this.font = generateFont(generator, 12);
    this.timerFont = generateFont(generator, 15);
    this.titleFont = generateFont(generator, 24);

    generator.dispose();

    this.glyphLayout = new GlyphLayout();
  }

  private static BitmapFont generateFont(FreeTypeFontGenerator generator, int size) {
    FreeTypeFontParameter param = new FreeTypeFontParameter();
    param.size = size;
    param.color = Color.WHITE;
    return generator.generateFont(param);
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
    damageFont.dispose();
    timerFont.dispose();
    titleFont.dispose();
  }
}
