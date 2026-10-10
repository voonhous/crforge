/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Disposable;

/** Shared workspace palette, skin, and widget construction. Owns the skin resources. */
final class WorkspaceTheme implements Disposable {
  static final Color BACKGROUND = Color.valueOf("10151fff");
  static final Color PANEL = Color.valueOf("192230ff");
  static final Color MUTED = Color.valueOf("a1b1c6ff");
  static final Color ACCENT = Color.valueOf("76d7cbff");
  final Skin skin = new Skin();
  private final Stage stage;

  WorkspaceTheme(Stage stage) {
    this.stage = stage;
    createSkin();
  }

  private void createSkin() {
    font("default-font", "Lato-Regular.ttf", 15);
    font("heading-font", "Lato-Bold.ttf", 13);
    font("title-font", "Lato-Bold.ttf", 19);
    font("mono-font", "RobotoMono-Regular.ttf", 13);
    Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
    pixel.setColor(Color.WHITE);
    pixel.fill();
    Texture texture = new Texture(pixel);
    pixel.dispose();
    skin.add("pixel", texture);
    skin.add("white", new TextureRegionDrawable(new TextureRegion(texture)), Drawable.class);
    skin.add(
        "default", new Label.LabelStyle(skin.getFont("default-font"), Color.valueOf("e8edf5ff")));
    skin.add("heading", new Label.LabelStyle(skin.getFont("heading-font"), Color.WHITE));
    skin.add("title", new Label.LabelStyle(skin.getFont("title-font"), Color.valueOf("e8edf5ff")));
    skin.add("mono", new Label.LabelStyle(skin.getFont("mono-font"), MUTED));
    TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
    button.font = skin.getFont("default-font");
    button.fontColor = Color.valueOf("e8edf5ff");
    button.disabledFontColor = Color.valueOf("78899fff");
    button.up = surface("button-up", "243142", "354358");
    button.over = surface("button-over", "30435a", "647c92");
    button.down = surface("button-down", "234c53", "76d7cb");
    button.checked = surface("button-checked", "23454e", "76d7cb");
    button.disabled = surface("button-disabled", "18222f", "273344");
    skin.add("default", button);
    ScrollPane.ScrollPaneStyle scroll = new ScrollPane.ScrollPaneStyle();
    scroll.vScrollKnob = skin.newDrawable("white", Color.valueOf("42526aff"));
    scroll.vScrollKnob.setMinWidth(5);
    skin.add("default", scroll);
    com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle list =
        new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle();
    list.font = skin.getFont("default-font");
    list.fontColorSelected = Color.WHITE;
    list.fontColorUnselected = MUTED;
    list.selection = skin.newDrawable("white", Color.valueOf("28575fff"));
    list.background = skin.newDrawable("white", PANEL);
    SelectBox.SelectBoxStyle select = new SelectBox.SelectBoxStyle();
    select.font = list.font;
    select.fontColor = Color.WHITE;
    select.background = skin.newDrawable("white", Color.valueOf("263447ff"));
    select.listStyle = list;
    select.scrollStyle = scroll;
    skin.add("default", select);
    // A list of aligned columns, such as a crawl's replays, in the monospaced font.
    com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle mono =
        new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(list);
    mono.font = skin.getFont("mono-font");
    skin.add("mono", mono);
    ProgressBar.ProgressBarStyle bar = new ProgressBar.ProgressBarStyle();
    bar.background = skin.newDrawable("white", PANEL);
    bar.background.setMinHeight(5);
    bar.knobBefore = skin.newDrawable("white", ACCENT);
    bar.knobBefore.setMinHeight(5);
    skin.add("default-horizontal", bar);
  }

  private void font(String name, String path, int size) {
    FreeTypeFontGenerator generator =
        new FreeTypeFontGenerator(Gdx.files.classpath("fonts/" + path));
    try {
      FreeTypeFontGenerator.FreeTypeFontParameter parameter =
          new FreeTypeFontGenerator.FreeTypeFontParameter();
      parameter.size = size;
      parameter.minFilter = Texture.TextureFilter.Linear;
      parameter.magFilter = Texture.TextureFilter.Linear;
      skin.add(name, generator.generateFont(parameter), BitmapFont.class);
    } finally {
      generator.dispose();
    }
  }

  private Drawable surface(String name, String fill, String border) {
    Pixmap pixmap = new Pixmap(9, 9, Pixmap.Format.RGBA8888);
    pixmap.setColor(Color.valueOf(border));
    pixmap.fillRectangle(1, 0, 7, 9);
    pixmap.fillRectangle(0, 1, 9, 7);
    pixmap.setColor(Color.valueOf(fill));
    pixmap.fillRectangle(1, 1, 7, 7);
    Texture texture = new Texture(pixmap);
    pixmap.dispose();
    skin.add(name, texture);
    return new NinePatchDrawable(new NinePatch(texture, 3, 3, 3, 3));
  }

  Label label(String text) {
    return new Label(text, skin);
  }

  Label wrapped(String text) {
    Label label = label(text);
    label.setWrap(true);
    return label;
  }

  TextButton button(String text, Runnable action) {
    TextButton button = new TextButton(text, skin);
    button.pad(0, 6, 0, 6);
    button.setProgrammaticChangeEvents(false);
    button.addListener(
        new ChangeListener() {
          @Override
          public void changed(ChangeEvent event, Actor actor) {
            if (button.isDisabled()) return;
            action.run();
            button.setChecked(false);
            stage.setKeyboardFocus(null);
          }
        });
    return button;
  }

  ScrollPane scroll(Actor actor) {
    ScrollPane pane = new ScrollPane(actor, skin);
    pane.setScrollingDisabled(true, false);
    pane.setFadeScrollBars(false);
    return pane;
  }

  @Override
  public void dispose() {
    skin.dispose();
  }
}
