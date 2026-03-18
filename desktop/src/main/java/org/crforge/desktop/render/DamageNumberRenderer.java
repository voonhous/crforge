package org.crforge.desktop.render;

import static org.crforge.desktop.render.RenderConstants.*;

import com.badlogic.gdx.Gdx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.crforge.core.component.Health;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;

/**
 * Manages floating damage number popups. Detects damage by comparing health snapshots between
 * render frames -- no changes to core module required.
 */
public class DamageNumberRenderer {

  private static final float POPUP_DURATION = 0.8f;
  private static final float FLOAT_SPEED = 30f;

  private final RenderContext ctx;
  private final List<DamagePopup> activePopups = new ArrayList<>();
  private final Map<Long, HealthSnapshot> previousHealth = new HashMap<>();

  public DamageNumberRenderer(RenderContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Detect damage by comparing current health to previous frame snapshot, and age existing popups.
   * Must be called every frame (even when rendering is toggled off) to keep snapshots current.
   */
  public void update(GameState state) {
    float deltaTime = Gdx.graphics.getDeltaTime();

    // Detect damage for each alive entity
    List<Entity> alive = state.getAliveEntities();
    Map<Long, HealthSnapshot> currentSnapshots = new HashMap<>(alive.size());

    for (Entity entity : alive) {
      Health health = entity.getHealth();
      if (health == null) {
        continue;
      }

      long id = entity.getId();
      int currentHp = health.getCurrent();
      int currentShield = health.getShield();

      HealthSnapshot prev = previousHealth.get(id);
      if (prev != null) {
        // Check for HP damage
        int hpLost = prev.hp - currentHp;
        if (hpLost > 0) {
          float worldX = entity.getPosition().getX() * TILE_PIXELS;
          float worldY = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
          activePopups.add(new DamagePopup(worldX, worldY, hpLost, false));
        }

        // Check for shield damage (shield decreased but HP unchanged)
        int shieldLost = prev.shield - currentShield;
        if (shieldLost > 0 && hpLost == 0) {
          float worldX = entity.getPosition().getX() * TILE_PIXELS;
          float worldY = entity.getPosition().getY() * TILE_PIXELS + BOTTOM_UI_HEIGHT;
          activePopups.add(new DamagePopup(worldX, worldY, shieldLost, true));
        }
      }

      currentSnapshots.put(id, new HealthSnapshot(currentHp, currentShield));
    }

    // Replace old snapshots with current ones (also cleans up dead entities)
    previousHealth.clear();
    previousHealth.putAll(currentSnapshots);

    // Age existing popups and remove expired ones
    Iterator<DamagePopup> it = activePopups.iterator();
    while (it.hasNext()) {
      DamagePopup popup = it.next();
      popup.elapsed += deltaTime;
      if (popup.elapsed >= POPUP_DURATION) {
        it.remove();
      }
    }
  }

  /** Render all active damage popups as floating text. */
  public void render() {
    if (activePopups.isEmpty()) {
      return;
    }

    ctx.getSpriteBatch().begin();

    for (DamagePopup popup : activePopups) {
      float yOffset = popup.elapsed * FLOAT_SPEED;
      float alpha = 1.0f - (popup.elapsed / POPUP_DURATION);

      if (popup.isShieldDamage) {
        ctx.getDamageFont()
            .setColor(
                COLOR_SHIELD_DAMAGE_NUMBER.r,
                COLOR_SHIELD_DAMAGE_NUMBER.g,
                COLOR_SHIELD_DAMAGE_NUMBER.b,
                alpha);
      } else {
        ctx.getDamageFont()
            .setColor(COLOR_DAMAGE_NUMBER.r, COLOR_DAMAGE_NUMBER.g, COLOR_DAMAGE_NUMBER.b, alpha);
      }

      String text = "-" + popup.amount;
      ctx.getGlyphLayout().setText(ctx.getDamageFont(), text);
      float drawX = popup.x - ctx.getGlyphLayout().width / 2;
      float drawY = popup.y + yOffset + 20; // offset above entity center
      ctx.getDamageFont().draw(ctx.getSpriteBatch(), text, drawX, drawY);
    }

    ctx.getDamageFont().setColor(1f, 1f, 1f, 1f);
    ctx.getSpriteBatch().end();
  }

  /** Snapshot of an entity's health at a given frame. */
  private record HealthSnapshot(int hp, int shield) {}

  /** A floating damage number popup. */
  private static class DamagePopup {
    final float x;
    final float y;
    final int amount;
    final boolean isShieldDamage;
    float elapsed;

    DamagePopup(float x, float y, int amount, boolean isShieldDamage) {
      this.x = x;
      this.y = y;
      this.amount = amount;
      this.isShieldDamage = isShieldDamage;
      this.elapsed = 0f;
    }
  }
}
