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
 * Orchestrator for the debug renderer. Delegates all drawing to focused sub-renderers and controls
 * the render pass ordering.
 */
public class DebugRenderer {

  private final RenderContext ctx;
  private final ArenaRenderer arenaRenderer;
  private final EntityRenderer entityRenderer;
  private final ProjectileRenderer projectileRenderer;
  private final HealthBarRenderer healthBarRenderer;
  private final DebugOverlayRenderer debugOverlayRenderer;
  private final DeployOverlayRenderer deployOverlayRenderer;
  private final AreaEffectOverlayRenderer areaEffectOverlayRenderer;
  private final AbilityOverlayRenderer abilityOverlayRenderer;
  private final HudRenderer hudRenderer;
  private final DamageNumberRenderer damageNumberRenderer;
  private final AoeDamageRenderer aoeDamageRenderer;

  @Getter private boolean drawPaths = false;
  @Getter private boolean drawRanges = false;
  @Getter private boolean drawDamageNumbers = false;
  @Getter private boolean drawAoeDamage = true;
  @Getter private boolean drawHpNumbers = false;

  public DebugRenderer() {
    this.ctx = new RenderContext();
    this.arenaRenderer = new ArenaRenderer(ctx);
    this.entityRenderer = new EntityRenderer(ctx);
    this.projectileRenderer = new ProjectileRenderer(ctx);
    this.healthBarRenderer = new HealthBarRenderer(ctx);
    this.debugOverlayRenderer = new DebugOverlayRenderer(ctx);
    this.deployOverlayRenderer = new DeployOverlayRenderer(ctx);
    this.areaEffectOverlayRenderer = new AreaEffectOverlayRenderer(ctx);
    this.abilityOverlayRenderer = new AbilityOverlayRenderer(ctx);
    this.hudRenderer = new HudRenderer(ctx);
    this.damageNumberRenderer = new DamageNumberRenderer(ctx);
    this.aoeDamageRenderer = new AoeDamageRenderer(ctx);
  }

  public void toggleDrawPaths() {
    drawPaths = !drawPaths;
  }

  public void toggleDrawRanges() {
    drawRanges = !drawRanges;
  }

  public void toggleDrawDamageNumbers() {
    drawDamageNumbers = !drawDamageNumbers;
  }

  public void toggleDrawAoeDamage() {
    drawAoeDamage = !drawAoeDamage;
  }

  public void toggleDrawHpNumbers() {
    drawHpNumbers = !drawHpNumbers;
  }

  public void render(
      GameEngine engine,
      OrthographicCamera camera,
      int hoverX,
      int hoverY,
      int selectedHandIndex,
      Team selectedTeam) {
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

    // 3. Pending deployment ghosts (sync delay silhouettes, rendered under entities)
    deployOverlayRenderer.renderPendingDeployments(
        engine.getDeploymentSystem().getPendingDeployments());

    // 4. Entities
    entityRenderer.render(state);

    // 5. Hover highlight + spell overlay (rendered above entities so spell radius is visible)
    Player hoverPlayer = null;
    Card hoverCard = null;
    if (hoverX >= 0 && hoverX < Arena.WIDTH && hoverY >= 0 && hoverY < Arena.HEIGHT) {
      hoverPlayer = getPlayer(match, selectedTeam);
      hoverCard = getSelectedCard(hoverPlayer, selectedHandIndex);
      arenaRenderer.renderHover(hoverX, hoverY, hoverCard, hoverPlayer, match, selectedHandIndex);
    }

    // 5.5. Hover range circles (attack range + minimum range preview during placement)
    if (hoverX >= 0 && hoverX < Arena.WIDTH && hoverY >= 0 && hoverY < Arena.HEIGHT) {
      arenaRenderer.renderHoverRanges(hoverX, hoverY, hoverCard);
    }

    // 6. Deploy timers (radial countdown overlay on deploying entities)
    deployOverlayRenderer.renderDeployTimers(state);

    // 7. Projectiles
    projectileRenderer.render(state);

    // 8. Health bars
    healthBarRenderer.render(state, drawHpNumbers);

    // 9. Targeting lines
    debugOverlayRenderer.renderTargetingLines(state);

    // 10. Path lines
    if (drawPaths) {
      debugOverlayRenderer.renderPathLines(state);
    }

    // 11. Attack range circles
    if (drawRanges) {
      debugOverlayRenderer.renderAttackRanges(state);
    }

    // 12. Entity names
    debugOverlayRenderer.renderEntityNames(state);

    // 12.5. Damage numbers (always update to keep snapshots current; only render when toggled on)
    damageNumberRenderer.update(state);
    if (drawDamageNumbers) {
      damageNumberRenderer.render();
    }

    // 12.6. AOE damage indicators (always update to consume events; only render when toggled on)
    aoeDamageRenderer.update(state);
    if (drawAoeDamage) {
      aoeDamageRenderer.render();
    }

    // 12.7. HP numbers
    if (drawHpNumbers) {
      debugOverlayRenderer.renderHpNumbers(state);
    }

    // 13. Area effect zones
    areaEffectOverlayRenderer.renderAreaEffects(state);

    // 13.5. Laser ball overlays (beam lines, tier/scan labels, pulse rings)
    areaEffectOverlayRenderer.renderLaserBallOverlays(state);

    // 14. Ability indicators (charge, dash, hook, reflect, variable damage)
    abilityOverlayRenderer.renderAbilityIndicators(state);

    // 15. Spawner timers
    debugOverlayRenderer.renderSpawnerTimers(state);

    // 16. HUD (timer, cards, elixir)
    hudRenderer.render(
        engine,
        match,
        camera,
        selectedHandIndex,
        selectedTeam,
        drawPaths,
        drawRanges,
        drawDamageNumbers,
        drawAoeDamage,
        drawHpNumbers);
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
