package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.OrthographicCamera;
import lombok.Getter;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.match.Match;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;

/**
 * Orchestrator for the debug renderer. Delegates all drawing to focused sub-renderers
 * and controls the render pass ordering.
 */
public class DebugRenderer {

  private final RenderContext ctx;
  private final ArenaRenderer arenaRenderer;
  private final EntityRenderer entityRenderer;
  private final ProjectileRenderer projectileRenderer;
  private final HealthBarRenderer healthBarRenderer;
  private final DebugOverlayRenderer debugOverlayRenderer;
  private final HudRenderer hudRenderer;

  @Getter
  private boolean drawPaths = false;
  @Getter
  private boolean drawRanges = false;

  public DebugRenderer() {
    this.ctx = new RenderContext();
    this.arenaRenderer = new ArenaRenderer(ctx);
    this.entityRenderer = new EntityRenderer(ctx);
    this.projectileRenderer = new ProjectileRenderer(ctx);
    this.healthBarRenderer = new HealthBarRenderer(ctx);
    this.debugOverlayRenderer = new DebugOverlayRenderer(ctx);
    this.hudRenderer = new HudRenderer(ctx);
  }

  public void toggleDrawPaths() {
    drawPaths = !drawPaths;
  }

  public void toggleDrawRanges() {
    drawRanges = !drawRanges;
  }

  public void render(GameEngine engine, OrthographicCamera camera,
                     int hoverX, int hoverY,
                     int selectedHandIndex, Team selectedTeam) {
    GameState state = engine.getGameState();
    Arena arena = engine.getArena();
    Match match = engine.getMatch();

    ctx.setProjection(camera);

    // 0. UI backgrounds
    hudRenderer.renderBackgrounds(camera);

    // 1. Arena tiles
    arenaRenderer.renderTiles(arena);

    // 2. Grid lines
    arenaRenderer.renderGrid(arena);

    // 3. Hover highlight
    if (hoverX >= 0 && hoverX < Arena.WIDTH && hoverY >= 0 && hoverY < Arena.HEIGHT) {
      Player player = getPlayer(match, selectedTeam);
      Card selectedCard = getSelectedCard(player, selectedHandIndex);
      arenaRenderer.renderHover(hoverX, hoverY, selectedCard, player, match, selectedHandIndex);
    }

    // 4. Entities
    entityRenderer.render(state);

    // 5. Projectiles
    projectileRenderer.render(state);

    // 6. Health bars
    healthBarRenderer.render(state);

    // 7. Targeting lines
    debugOverlayRenderer.renderTargetingLines(state);

    // 8. Path lines
    if (drawPaths) {
      debugOverlayRenderer.renderPathLines(state);
    }

    // 9. Attack range circles
    if (drawRanges) {
      debugOverlayRenderer.renderAttackRanges(state);
    }

    // 10. Entity names
    debugOverlayRenderer.renderEntityNames(state);

    // 11. Area effect zones
    debugOverlayRenderer.renderAreaEffects(state);

    // 12. Ability indicators (charge, dash, hook, reflect, variable damage)
    debugOverlayRenderer.renderAbilityIndicators(state);

    // 13. Spawner timers
    debugOverlayRenderer.renderSpawnerTimers(state);

    // 14. HUD (timer, cards, elixir)
    hudRenderer.render(engine, match, camera, selectedHandIndex, selectedTeam,
        drawPaths, drawRanges);
  }

  private Player getPlayer(Match match, Team selectedTeam) {
    if (match == null || selectedTeam == null) {
      return null;
    }
    for (Player p : match.getAllPlayers()) {
      if (p.getTeam() == selectedTeam) {
        return p;
      }
    }
    return null;
  }

  private Card getSelectedCard(Player player, int selectedHandIndex) {
    if (player == null || selectedHandIndex == -1) {
      return null;
    }
    return player.getHand().getCard(selectedHandIndex);
  }

  public void dispose() {
    ctx.dispose();
  }
}
