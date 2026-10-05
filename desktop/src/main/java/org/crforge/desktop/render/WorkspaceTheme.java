package org.crforge.desktop.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
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
    FreeTypeFontGenerator generator =
        new FreeTypeFontGenerator(Gdx.files.classpath("fonts/RobotoMono-Regular.ttf"));
    FreeTypeFontGenerator.FreeTypeFontParameter parameter =
        new FreeTypeFontGenerator.FreeTypeFontParameter();
    parameter.size = 14;
    skin.add("default-font", generator.generateFont(parameter), BitmapFont.class);
    generator.dispose();
    Pixmap pixel = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
    pixel.setColor(Color.WHITE);
    pixel.fill();
    Texture texture = new Texture(pixel);
    pixel.dispose();
    skin.add("pixel", texture);
    skin.add("white", new TextureRegionDrawable(new TextureRegion(texture)), Drawable.class);
    skin.add(
        "default", new Label.LabelStyle(skin.getFont("default-font"), Color.valueOf("e8edf5ff")));
    TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
    button.font = skin.getFont("default-font");
    button.fontColor = Color.valueOf("e8edf5ff");
    button.disabledFontColor = Color.valueOf("78899fff");
    button.up = skin.newDrawable("white", Color.valueOf("263447ff"));
    button.over = skin.newDrawable("white", Color.valueOf("344860ff"));
    button.down = skin.newDrawable("white", Color.valueOf("326e70ff"));
    button.checked = skin.newDrawable("white", Color.valueOf("28575fff"));
    button.disabled = skin.newDrawable("white", Color.valueOf("1c2634ff"));
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
    ProgressBar.ProgressBarStyle bar = new ProgressBar.ProgressBarStyle();
    bar.background = skin.newDrawable("white", PANEL);
    bar.background.setMinHeight(5);
    bar.knobBefore = skin.newDrawable("white", ACCENT);
    bar.knobBefore.setMinHeight(5);
    skin.add("default-horizontal", bar);
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
