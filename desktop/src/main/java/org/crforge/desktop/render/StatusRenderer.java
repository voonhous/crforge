package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import java.util.List;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.battle.UnitStatus;
import org.crforge.desktop.battle.UnitStatus.Kind;

/**
 * Vector status cues. Animation uses battle ticks, so pause, step and replay speed stay aligned.
 */
final class StatusRenderer {
  private static final Color ICE = Color.valueOf("b9edf5");
  private static final Color SPARK = Color.valueOf("ffe28c");
  private static final Color CLONE = Color.valueOf("d5b4ef");
  private static final Color OTHER = Color.valueOf("becbd2");
  private static final float BADGE_RADIUS = 8;
  private static final float BADGE_SPACING = 21;
  private static final int MAX_BADGES = 2;
  private final RenderContext ctx;

  StatusRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  void render(BattleFrame frame, ViewOrientation view) {
    Gdx.gl.glEnable(GL20.GL_BLEND);
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    var shapes = ctx.getShapeRenderer();
    for (EntityView entity : frame.entities()) {
      if (!entity.isCharacter() || entity.statuses().isEmpty()) continue;
      float x = view.px(entity.x());
      float y = view.py(entity.y());
      float radius = Math.max(6, unitsToPixels(entity.radius()));
      shapes.begin(ShapeType.Line);
      if (entity.hasStatus(Kind.CLONE)) {
        shapes.setColor(CLONE);
        for (int i = 0; i < 12; i++) arc(shapes, x, y, radius + 3, i * 30, 16, 3);
      }
      if (entity.hasStatus(Kind.FROZEN)) {
        shapes.setColor(ICE);
        float r = radius + 5;
        // Angular ice shell remains distinct from the clone's dashed circle and air-unit ring.
        for (int i = 0; i < 8; i++) {
          double a = Math.PI * (i + 0.5) / 4;
          double b = Math.PI * (i + 1.5) / 4;
          shapes.line(
              x + r * (float) Math.cos(a),
              y + r * (float) Math.sin(a),
              x + r * (float) Math.cos(b),
              y + r * (float) Math.sin(b));
        }
        shapes.line(x - r * 0.65f, y - r * 0.4f, x - r * 0.25f, y + r * 0.15f);
        shapes.line(x - r * 0.25f, y + r * 0.15f, x - r * 0.45f, y + r * 0.5f);
      }
      if (entity.hasStatus(Kind.STUNNED)) {
        shapes.setColor(SPARK);
        float pulse = (float) Math.sin((frame.tick() + entity.id() % 20) * 0.5f) * 2;
        bolt(shapes, x - radius - 5, y + radius * 0.35f + pulse, 4);
        bolt(shapes, x + radius + 5, y + radius * 0.35f - pulse, 4);
      }
      shapes.end();
      badges(entity, view, x, y - radius - 16);
    }
  }

  private void badges(EntityView entity, ViewOrientation view, float x, float y) {
    List<UnitStatus> statuses = UnitStatus.badges(entity.statuses());
    int visible = Math.min(MAX_BADGES, statuses.size());
    int extra = statuses.size() - visible;
    int slots = visible + (extra > 0 ? 1 : 0);
    float halfWidth = (slots - 1) * BADGE_SPACING / 2;
    float arenaWidth = unitsToPixels(view.widthUnits());
    x = Math.max(halfWidth + 11, Math.min(arenaWidth - halfWidth - 11, x));
    y = Math.max(BOTTOM_UI_HEIGHT + 14, y);
    float start = x - halfWidth;
    var shapes = ctx.getShapeRenderer();
    shapes.begin(ShapeType.Filled);
    shapes.setColor(0.06f, 0.09f, 0.13f, 0.95f);
    for (int i = 0; i < slots; i++) shapes.circle(start + i * BADGE_SPACING, y, BADGE_RADIUS, 20);
    shapes.end();
    shapes.begin(ShapeType.Line);
    for (int i = 0; i < visible; i++) {
      UnitStatus status = statuses.get(i);
      float cx = start + i * BADGE_SPACING;
      Color color = color(status.kind());
      shapes.setColor(color.r, color.g, color.b, 0.25f);
      shapes.circle(cx, y, BADGE_RADIUS + 1, 24);
      shapes.setColor(color);
      if (status.timed())
        arc(shapes, cx, y, BADGE_RADIUS + 1, 90, 360 * status.remainingShare(), 24);
      else shapes.circle(cx, y, BADGE_RADIUS + 1, 24);
      switch (status.kind()) {
        case STUNNED -> bolt(shapes, cx, y, 5);
        case FROZEN -> {
          for (int j = 0; j < 3; j++) {
            float dx = (float) Math.cos(j * Math.PI / 3) * 5;
            float dy = (float) Math.sin(j * Math.PI / 3) * 5;
            shapes.line(cx - dx, y - dy, cx + dx, y + dy);
          }
        }
        case CLONE -> {
          diamond(shapes, cx - 2, y + 1, 3);
          diamond(shapes, cx + 2, y - 1, 3);
        }
        case OTHER -> {
          diamond(shapes, cx, y, 5);
          shapes.line(cx, y - 2, cx, y + 2);
        }
      }
    }
    shapes.end();
    if (extra > 0) {
      var font = ctx.getEntityNameFont();
      String text = "+" + extra;
      ctx.getGlyphLayout().setText(font, text);
      font.setColor(Color.WHITE);
      ctx.getSpriteBatch().begin();
      font.draw(
          ctx.getSpriteBatch(),
          text,
          start + visible * BADGE_SPACING - ctx.getGlyphLayout().width / 2,
          y + ctx.getGlyphLayout().height / 2);
      ctx.getSpriteBatch().end();
    }
  }

  private static Color color(Kind kind) {
    return switch (kind) {
      case FROZEN -> ICE;
      case STUNNED -> SPARK;
      case CLONE -> CLONE;
      case OTHER -> OTHER;
    };
  }

  /** Perimeter only: ShapeRenderer.arc also draws spokes from its centre in line mode. */
  private static void arc(
      ShapeRenderer shapes,
      float x,
      float y,
      float radius,
      float start,
      float sweep,
      int segments) {
    for (int i = 0; i < segments; i++) {
      double a = Math.toRadians(start + sweep * i / segments);
      double b = Math.toRadians(start + sweep * (i + 1) / segments);
      shapes.line(
          x + radius * (float) Math.cos(a), y + radius * (float) Math.sin(a),
          x + radius * (float) Math.cos(b), y + radius * (float) Math.sin(b));
    }
  }

  private static void bolt(ShapeRenderer shapes, float x, float y, float r) {
    shapes.line(x + r * 0.5f, y + r, x - r * 0.5f, y);
    shapes.line(x - r * 0.5f, y, x + r * 0.5f, y);
    shapes.line(x + r * 0.5f, y, x - r * 0.5f, y - r);
  }

  private static void diamond(ShapeRenderer shapes, float x, float y, float r) {
    shapes.line(x, y + r, x + r, y);
    shapes.line(x + r, y, x, y - r);
    shapes.line(x, y - r, x - r, y);
    shapes.line(x - r, y, x, y + r);
  }
}
