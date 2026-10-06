package org.crforge.desktop.render;

import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.battle.UnitStatus;

/** Text presentation of the currently pinned entity. */
final class UnitInspector extends Label {
  UnitInspector(Skin skin) {
    super("Choose Inspect, then click a unit.", skin, "mono");
    setWrap(true);
  }

  private static String statusDetails(EntityView selected, ViewOrientation orientation) {
    StringBuilder text = new StringBuilder("\n\nACTIVE EFFECTS");
    if (selected.statuses().isEmpty()) return text.append("\nNone").toString();
    for (UnitStatus status : selected.statuses()) {
      String label = status.kind() == UnitStatus.Kind.OTHER ? status.name() : status.kind().label();
      text.append("\n").append(label);
      if (status.kind() != UnitStatus.Kind.CLONE) {
        text.append(" - ").append(status.duration());
        if (!label.equals(status.name())) text.append("\n  ").append(status.name());
        if (status.source() != null) text.append("\n  From ").append(status.source());
        if (status.appliedSide() >= 0)
          text.append("\n  Side ")
              .append(status.appliedSide())
              .append(" / ")
              .append(orientation.sideName(status.appliedSide()));
      }
    }
    return text.toString();
  }

  void update(BattleFrame frame, int selectedId, ViewOrientation orientation) {
    EntityView selected =
        frame.entities().stream()
            .filter(entity -> entity.id() == selectedId)
            .findFirst()
            .orElse(null);
    if (selected == null) {
      setText(
          selectedId < 0
              ? "Choose Inspect, then click a unit.\n\nPosition and ranges use game units."
              : "Unit #" + selectedId + " is no longer on the arena.");
    } else {
      setText(
          selected.name()
              + " #"
              + selected.id()
              + "\nSide "
              + selected.side()
              + " / "
              + orientation.sideName(selected.side())
              + "\nHP "
              + selected.hitPoints()
              + " / "
              + selected.maxHitPoints()
              + "\nShield "
              + selected.shield()
              + " / "
              + selected.maxShield()
              + "\nPosition "
              + selected.x()
              + ", "
              + selected.y()
              + "\nState "
              + RouteOverlayRenderer.stateName(selected.state())
              + "\nRange "
              + selected.minimumRange()
              + " - "
              + selected.range()
              + "\nSight "
              + selected.sightRange()
              + "\nTarget "
              + (selected.hasTarget() ? selected.targetX() + ", " + selected.targetY() : "none")
              + (selected.deploying() ? "\nDeploying" : "")
              + (selected.hidden() ? "\nHidden" : "")
              + statusDetails(selected, orientation));
    }
  }
}
