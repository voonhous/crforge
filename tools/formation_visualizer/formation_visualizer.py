#!/usr/bin/env python3
"""Visual editor for formation_offsets.json -- drag unit circles to fine-tune spawn positions."""

import argparse
import json
import math
import os
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

try:
    from PIL import Image, ImageTk

    HAS_PIL = True
except ImportError:
    HAS_PIL = False

TILE_SCALE = 1000
MIN_UNIT_RADIUS_PX = 6
CANVAS_SIZE = 600
CANVAS_HALF = CANVAS_SIZE // 2
MIN_TILE_PX = 25
MAX_TILE_PX = 120
PADDING_TILES = 1.5
MIN_OVERLAY_OPACITY = 0.05
MAX_OVERLAY_OPACITY = 1.0
MIN_OVERLAY_SCALE = 0.10
MAX_OVERLAY_SCALE = 10.0
MIN_VIEW_ZOOM = 0.2
MAX_VIEW_ZOOM = 5.0
ZOOM_FACTOR = 1.1
CROSSHAIR_SIZE = 8

COLORS = [
    "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
    "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990",
    "#dcbeff", "#9a6324", "#800000", "#aaffc3", "#808000",
]


class FormationVisualizer(tk.Tk):
    def __init__(self, data_dir):
        super().__init__()
        self.title(f"Formation Offset Editor - {data_dir}")
        self.data_dir = os.path.abspath(data_dir)
        self.modified = False
        self.drag_state = None
        self.coord_entries = []  # list of (x_entry, y_entry) or (r_entry, angle_entry) per unit
        self.tile_px = MIN_TILE_PX

        # overlay state
        self._overlay_pil = None
        self._overlay_tk = None
        self._overlay_scale_x = 1.0
        self._overlay_scale_y = 1.0
        self._overlay_opacity = 1.0
        self._overlay_offset = [0, 0]
        self._overlay_drag_state = None

        # view transform state
        self._view_pan = [0.0, 0.0]
        self._view_zoom = 1.0
        self._pan_drag_state = None

        # debounce state for scroll/pan redraws
        self._redraw_after_id = None
        self._overlay_fast = False

        # calibration state
        self._calibrating = False
        self._calib_rect = None
        self._calib_canvas_id = None

        self.load_data()
        self.build_card_list()
        self.create_widgets()

        if self.card_keys:
            self.combo.current(0)
            self.on_card_selected()

        self.protocol("WM_DELETE_WINDOW", self.on_close)

    # ── data ──────────────────────────────────────────────────────────

    def load_data(self):
        offsets_path = os.path.join(self.data_dir, "formation_offsets.json")
        cards_path = os.path.join(self.data_dir, "parsed_cards.json")

        units_path = os.path.join(self.data_dir, "parsed_units.json")

        with open(offsets_path) as f:
            self.offsets = json.load(f)

        self.card_info = {}
        with open(cards_path) as f:
            cards = json.load(f)
        for c in cards:
            self.card_info[c["id"]] = c

        with open(units_path) as f:
            self.units = json.load(f)

    def build_card_list(self):
        items = []
        for key in sorted(self.offsets.keys()):
            info = self.card_info.get(key)
            if info:
                count = info.get("count", 1)
                unit = info.get("unit", "?")
                label = f"{key} ({count}x {unit}"
                sec_unit = info.get("secondaryUnit")
                sec_count = info.get("secondaryCount")
                if sec_unit and sec_count:
                    label += f" + {sec_count}x {sec_unit}"
                label += ")"
            else:
                n = len(self.offsets[key])
                label = f"{key} ({n} units)"
            items.append((key, label))
        self.card_keys = [k for k, _ in items]
        self.card_labels = [l for _, l in items]

    # ── widgets ───────────────────────────────────────────────────────

    def _create_stepper_with_entry(self, parent, label, from_, to, step,
                                   on_change, initial, fmt=".2f",
                                   label_padx=(0, 0), state="normal"):
        """Create a labeled -/+ stepper with entry field. Returns (minus_btn, plus_btn, entry)."""
        tk.Label(parent, text=label, font=("Menlo", 9)).pack(side=tk.LEFT, padx=label_padx)

        def do_step(delta):
            val = self._parse_numeric(entry.get().strip())
            if val is None:
                return
            val = self._clamp(val + delta, from_, to)
            self._set_entry(entry, f"{val:{fmt}}")
            on_change()

        minus_btn = tk.Button(parent, text="-", width=2, state=state, repeatdelay=400,
                              repeatinterval=80, command=lambda: do_step(-step))
        minus_btn.pack(side=tk.LEFT)

        entry = tk.Entry(parent, font=("Menlo", 10), width=5, justify=tk.RIGHT, state=state)
        if state == "disabled":
            entry.config(state="normal")
        entry.insert(0, f"{initial:{fmt}}")
        if state == "disabled":
            entry.config(state="disabled")
        entry.pack(side=tk.LEFT)
        entry.bind("<Return>", lambda e: on_change())
        entry.bind("<FocusOut>", lambda e: on_change())

        plus_btn = tk.Button(parent, text="+", width=2, state=state, repeatdelay=400,
                             repeatinterval=80, command=lambda: do_step(step))
        plus_btn.pack(side=tk.LEFT)

        return minus_btn, plus_btn, entry

    def _create_top_bar(self):
        self.top_frame = tk.Frame(self)
        self.top_frame.pack(fill=tk.X, padx=8, pady=4)

        self.combo = ttk.Combobox(self.top_frame, values=self.card_labels, state="readonly", width=40)
        self.combo.pack(side=tk.LEFT)
        self.combo.bind("<<ComboboxSelected>>", lambda e: self.on_card_selected())

        self.circle_mode = tk.BooleanVar(value=False)
        tk.Checkbutton(self.top_frame, text="Circle Mode", variable=self.circle_mode,
                       command=self.on_card_selected).pack(side=tk.LEFT, padx=(12, 0))

        self.status_label = tk.Label(self.top_frame, text="", fg="green")
        self.status_label.pack(side=tk.RIGHT, padx=8)

        tk.Button(self.top_frame, text="Save", command=self.save).pack(side=tk.RIGHT, padx=4)
        tk.Button(self.top_frame, text="Help", command=self._show_help).pack(side=tk.RIGHT, padx=4)

    def _create_distribute_controls(self):
        self.distribute_frame = tk.Frame(self)
        self.distribute_frame.pack(fill=tk.X, padx=8, pady=(0, 4))
        tk.Label(self.distribute_frame, text="Radius:", font=("Menlo", 9)).pack(side=tk.LEFT)
        self.dist_radius_entry = tk.Entry(self.distribute_frame, font=("Menlo", 10), width=6)
        self.dist_radius_entry.pack(side=tk.LEFT, padx=2)
        tk.Label(self.distribute_frame, text="Start angle:", font=("Menlo", 9)).pack(side=tk.LEFT, padx=(8, 0))
        self.dist_angle_entry = tk.Entry(self.distribute_frame, font=("Menlo", 10), width=6)
        self.dist_angle_entry.insert(0, "90")
        self.dist_angle_entry.pack(side=tk.LEFT, padx=2)
        tk.Button(self.distribute_frame, text="Apply", command=self.apply_distribute).pack(side=tk.LEFT, padx=4)
        self.distribute_frame.pack_forget()

    def _create_overlay_controls(self):
        if HAS_PIL:
            self.overlay_frame = tk.Frame(self)
            self.overlay_frame.pack(fill=tk.X, padx=8, pady=(0, 4))
            tk.Button(self.overlay_frame, text="Load Image",
                      command=self._load_overlay_image).pack(side=tk.LEFT)

            self.overlay_controls_frame = tk.Frame(self)

            _, _, self._opacity_entry = self._create_stepper_with_entry(
                self.overlay_controls_frame, "Opacity:",
                MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY, 0.05,
                self._on_overlay_entry_commit, self._overlay_opacity)

            _, _, self._scale_x_entry = self._create_stepper_with_entry(
                self.overlay_controls_frame, "Scale X:",
                MIN_OVERLAY_SCALE, MAX_OVERLAY_SCALE, 0.05,
                self._on_overlay_entry_commit, self._overlay_scale_x,
                label_padx=(8, 2))

            _, _, self._scale_y_entry = self._create_stepper_with_entry(
                self.overlay_controls_frame, "Y:",
                MIN_OVERLAY_SCALE, MAX_OVERLAY_SCALE, 0.05,
                self._on_overlay_entry_commit, self._overlay_scale_y,
                label_padx=(4, 2))

            tk.Button(self.overlay_controls_frame, text="Calibrate",
                      command=self._start_calibration).pack(side=tk.LEFT, padx=(8, 0))
            tk.Button(self.overlay_controls_frame, text="Clear",
                      command=self._clear_overlay).pack(side=tk.LEFT, padx=(4, 0))

            self.overlay_controls_frame.pack_forget()

            self.grid_frame = tk.Frame(self)

            self._grid_locked = tk.BooleanVar(value=False)
            tk.Checkbutton(self.grid_frame, text="Lock Grid", variable=self._grid_locked,
                           command=self._on_grid_lock_changed).pack(side=tk.LEFT)

            self._tile_px_minus, self._tile_px_plus, self._tile_px_entry = self._create_stepper_with_entry(
                self.grid_frame, "Tile px:",
                MIN_TILE_PX, MAX_TILE_PX, 1,
                self._on_tile_px_entry_commit, MIN_TILE_PX,
                fmt=".0f", label_padx=(8, 2), state="disabled")

            tk.Button(self.grid_frame, text="Reset View",
                      command=self._reset_view).pack(side=tk.LEFT, padx=(8, 0))

            self.grid_frame.pack_forget()
        else:
            self._grid_locked = tk.BooleanVar(value=False)

    def _create_canvas(self):
        self.canvas = tk.Canvas(self, width=CANVAS_SIZE, height=CANVAS_SIZE, bg="white")
        self.canvas.pack(padx=8, pady=4)
        self.canvas.bind("<Button-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)

        # pan: right/middle click drag (no shift = pan both, shift = overlay only)
        pan_bindings = [
            (("<Button-2>", "<Button-3>"), self._on_pan_press),
            (("<B2-Motion>", "<B3-Motion>"), self._on_pan_drag),
            (("<ButtonRelease-2>", "<ButtonRelease-3>"), self._on_pan_release),
        ]
        for events, handler in pan_bindings:
            for event in events:
                self.canvas.bind(event, handler)

        # scroll zoom (macOS/Windows + Linux)
        self.canvas.bind("<MouseWheel>", self._on_scroll_zoom)
        self.canvas.bind("<Button-4>", self._on_scroll_zoom)
        self.canvas.bind("<Button-5>", self._on_scroll_zoom)

    def _create_coord_panel(self):
        coord_outer = tk.Frame(self)
        coord_outer.pack(fill=tk.BOTH, expand=True, padx=8, pady=4)
        self.coord_frame = tk.Frame(coord_outer)
        self.coord_frame.pack(fill=tk.BOTH, expand=True)

        self.bind("<Control-s>", lambda e: self.save())

    def create_widgets(self):
        self._create_top_bar()
        self._create_distribute_controls()
        self._create_overlay_controls()
        self._create_canvas()
        self._create_coord_panel()

    # ── help ───────────────────────────────────────────────────────────

    def _show_help(self):
        dlg = tk.Toplevel(self)
        dlg.title("Controls")
        dlg.transient(self)
        dlg.resizable(False, False)

        text = tk.Text(dlg, font=("Menlo", 10), width=62, height=38,
                       wrap=tk.WORD, padx=12, pady=12, relief=tk.FLAT,
                       bg=dlg.cget("bg"))
        text.pack(padx=4, pady=4)

        bold = ("Menlo", 10, "bold")
        text.tag_configure("h", font=bold)

        def h(s):
            text.insert(tk.END, s + "\n", "h")

        def p(s):
            text.insert(tk.END, s + "\n")

        h("Canvas")
        p("  Left-click drag     Move a unit circle")
        p("  Right/middle drag   Pan the view")
        p("  Shift + right drag  Move overlay image only")
        p("  Scroll wheel        Zoom in/out (centered on cursor)")
        p("")
        h("Top Bar")
        p("  Card selector       Pick a card to edit")
        p("  Circle Mode         Switch coordinates to r / angle")
        p("  Save (Ctrl+S)       Write formation_offsets.json")
        p("")
        h("Circle Mode")
        p("  Radius / Start angle / Apply")
        p("    Distribute all units evenly around a circle")
        p("    with the given radius and starting angle.")
        p("")
        h("Overlay Image")
        p("  Load Image          Open a reference screenshot")
        p("  Opacity -/+         Blend amount (0.05 - 1.00)")
        p("  Scale X / Y -/+    Resize the overlay independently")
        p("  Calibrate           Draw a rectangle over known tiles,")
        p("                      then enter tile dimensions to auto-")
        p("                      scale the overlay to match the grid.")
        p("  Clear               Remove the overlay image")
        p("")
        h("Grid")
        p("  Lock Grid           Keep tile_px fixed when switching cards")
        p("  Tile px -/+         Manual tile size (only when locked)")
        p("  Reset View          Reset pan and zoom to defaults")
        p("")
        h("Coordinate Panel")
        p("  x / y entries       Edit unit position in tiles")
        p("  r / angle entries   Edit position in polar (circle mode)")
        p("  ^ / v buttons       Reorder units within their group")
        p("  Supports math expressions (e.g. 1/3, 0.5*2)")

        text.config(state=tk.DISABLED)

        tk.Button(dlg, text="Close", command=dlg.destroy).pack(pady=(0, 8))
        dlg.bind("<Escape>", lambda e: dlg.destroy())

    # ── helpers ─────────────────────────────────────────────────────────

    @staticmethod
    def _clamp(val, lo, hi):
        return max(lo, min(hi, val))

    @staticmethod
    def _set_entry(entry, text):
        entry.delete(0, tk.END)
        entry.insert(0, text)

    def _show_status(self, text, color="green", timeout_ms=0):
        self.status_label.config(text=text, fg=color)
        if timeout_ms > 0:
            self.after(timeout_ms, lambda: self.status_label.config(text="", fg="green"))

    @staticmethod
    def _bind_commit(entry, callback):
        entry.bind("<Return>", lambda e: callback())
        entry.bind("<FocusOut>", lambda e: callback())

    # ── scale ─────────────────────────────────────────────────────────

    def compute_scale(self, offsets, summon_radius_tiles):
        max_ext = 1.0
        for gx, gy in offsets:
            max_ext = max(max_ext, abs(gx), abs(gy))
        if summon_radius_tiles:
            max_ext = max(max_ext, summon_radius_tiles)
        tile_px = (CANVAS_HALF - 20) / (max_ext + PADDING_TILES)
        return self._clamp(tile_px, MIN_TILE_PX, MAX_TILE_PX)

    def tile_to_pixel(self, gx, gy):
        """game x (lateral) -> canvas right, game y (forward) -> canvas up"""
        z = self._view_zoom
        px = (CANVAS_HALF + self._view_pan[0]) + gx * self.tile_px * z
        py = (CANVAS_HALF + self._view_pan[1]) - gy * self.tile_px * z
        return px, py

    def pixel_to_tile(self, px, py):
        z = self._view_zoom
        gx = (px - CANVAS_HALF - self._view_pan[0]) / (self.tile_px * z)
        gy = (CANVAS_HALF + self._view_pan[1] - py) / (self.tile_px * z)
        return round(gx, 3), round(gy, 3)

    # ── drawing ───────────────────────────────────────────────────────

    def draw_grid(self):
        ox = CANVAS_HALF + self._view_pan[0]
        oy = CANVAS_HALF + self._view_pan[1]
        eff = self.tile_px * self._view_zoom

        # visible tile range from canvas edges
        tl_gx, tl_gy = self.pixel_to_tile(0, 0)
        br_gx, br_gy = self.pixel_to_tile(CANVAS_SIZE, CANVAS_SIZE)
        min_t = int(min(tl_gx, br_gx)) - 2
        max_t = int(max(tl_gx, br_gx)) + 2
        min_ty = int(min(tl_gy, br_gy)) - 2
        max_ty = int(max(tl_gy, br_gy)) + 2

        skip_labels = eff < 50

        # deploy-tile highlight: the cell around (0,0)
        x0, y0 = self.tile_to_pixel(-0.5, 0.5)
        x1, y1 = self.tile_to_pixel(0.5, -0.5)
        self.canvas.create_rectangle(x0, y0, x1, y1, fill="#e8f0fe", outline="")

        # axis lines through origin
        self.canvas.create_line(ox, 0, ox, CANVAS_SIZE, fill="#999", width=2)
        self.canvas.create_line(0, oy, CANVAS_SIZE, oy, fill="#999", width=2)

        # grid lines at half-integer positions (tile boundaries)
        for t in range(min_t, max_t + 1):
            h = t + 0.5
            cx = ox + h * eff
            self.canvas.create_line(cx, 0, cx, CANVAS_SIZE, fill="#ddd", width=1)
        for t in range(min_ty, max_ty + 1):
            h = t + 0.5
            cy = oy - h * eff
            self.canvas.create_line(0, cy, CANVAS_SIZE, cy, fill="#ddd", width=1)

        # tile-center labels at integer positions
        for t in range(min_t, max_t + 1):
            if t == 0 or (skip_labels and t % 2 != 0):
                continue
            cx = ox + t * eff
            self.canvas.create_text(cx, oy + 12, text=str(t),
                                    font=("Menlo", 8), fill="#888")
        for t in range(min_ty, max_ty + 1):
            if t == 0 or (skip_labels and t % 2 != 0):
                continue
            cy = oy - t * eff
            self.canvas.create_text(ox - 14, cy, text=str(t),
                                    font=("Menlo", 8), fill="#888")

        # axis labels
        self.canvas.create_text(ox - 14, 8, text="y", font=("Menlo", 9, "bold"), fill="#666")
        self.canvas.create_text(CANVAS_SIZE - 10, oy + 12, text="x",
                                font=("Menlo", 9, "bold"), fill="#666")

        # crosshair at origin
        self.canvas.create_line(ox - CROSSHAIR_SIZE, oy, ox + CROSSHAIR_SIZE, oy, fill="#333", width=1.5)
        self.canvas.create_line(ox, oy - CROSSHAIR_SIZE, ox, oy + CROSSHAIR_SIZE, fill="#333", width=1.5)

    def _draw_debounced(self):
        """Fast redraw during rapid interaction, with a delayed quality pass."""
        if self._redraw_after_id is not None:
            self.after_cancel(self._redraw_after_id)
        self._overlay_fast = True
        self.draw_formation()
        self._redraw_after_id = self.after(150, self._draw_quality)

    def _draw_quality(self):
        """Redraw with high-quality overlay after interaction settles."""
        self._redraw_after_id = None
        self._overlay_fast = False
        self.draw_formation()

    def draw_formation(self):
        self.canvas.delete("all")
        idx = self.combo.current()
        if idx < 0:
            return
        key = self.card_keys[idx]
        offsets = self.offsets[key]

        info = self.card_info.get(key, {})
        sr = info.get("summonRadius")
        sr_tiles = sr / TILE_SCALE if sr else None

        if not self._grid_locked.get():
            self.tile_px = self.compute_scale(offsets, sr_tiles)
            if HAS_PIL:
                self._sync_tile_px_entry()

        # overlay image first so grid lines draw on top
        if self._overlay_pil is not None:
            self._draw_overlay()

        self.draw_grid()

        # summon radius circle
        if sr_tiles and sr_tiles * self.tile_px * self._view_zoom >= 2:
            r_px = sr_tiles * self.tile_px * self._view_zoom
            cx, cy = self.tile_to_pixel(0, 0)
            self.canvas.create_oval(cx - r_px, cy - r_px, cx + r_px, cy + r_px,
                                    outline="#aaa", width=1.5, dash=(6, 4))
            label = f"summon_radius = {sr_tiles:.4g}"
            self.canvas.create_text(cx, cy - r_px - 10, text=label,
                                    font=("Menlo", 8), fill="#aaa")

        # guide circle in circle mode
        if self.circle_mode.get() and offsets:
            radii = [math.sqrt(gx ** 2 + gy ** 2) for gx, gy in offsets]
            mean_r = sum(radii) / len(radii)
            if mean_r > 0:
                r_px = mean_r * self.tile_px * self._view_zoom
                cx, cy = self.tile_to_pixel(0, 0)
                self.canvas.create_oval(cx - r_px, cy - r_px, cx + r_px, cy + r_px,
                                        outline="#ccc", width=1, dash=(4, 4))

        # unit circles
        for i, (gx, gy) in enumerate(offsets):
            self.draw_unit_circle(i, gx, gy)

    def get_unit_name_for_index(self, unit_idx):
        """Return the unit name for a given offset index (primary or secondary)."""
        idx = self.combo.current()
        if idx < 0:
            return None
        key = self.card_keys[idx]
        info = self.card_info.get(key, {})
        primary_count = info.get("count", 1)
        if unit_idx < primary_count:
            return info.get("unit")
        sec_unit = info.get("secondaryUnit")
        if sec_unit:
            return sec_unit
        return info.get("unit")

    def get_unit_radius_px(self, unit_idx=0):
        """Get the pixel radius for unit circles based on collisionRadius."""
        unit_name = self.get_unit_name_for_index(unit_idx)
        if unit_name and unit_name in self.units:
            cr = self.units[unit_name].get("collisionRadius", 0)
            if cr > 0:
                return max(MIN_UNIT_RADIUS_PX, cr * self.tile_px * self._view_zoom)
        return MIN_UNIT_RADIUS_PX

    def draw_unit_circle(self, i, gx, gy):
        px, py = self.tile_to_pixel(gx, gy)
        r = self.get_unit_radius_px(i)
        color = COLORS[i % len(COLORS)]
        tag = f"unit_{i}"
        self.canvas.delete(tag)
        self.canvas.create_oval(px - r, py - r, px + r, py + r,
                                fill=color, outline="white", width=1.5, tags=tag)
        self.canvas.create_text(px, py, text=str(i), fill="white",
                                font=("Menlo", 10, "bold"), tags=tag)

    # ── coordinate panel ──────────────────────────────────────────────

    def _get_primary_count(self, key):
        info = self.card_info.get(key, {})
        return info.get("count", 1)

    def _polar_from_xy(self, gx, gy):
        r = math.sqrt(gx ** 2 + gy ** 2)
        angle_deg = math.degrees(math.atan2(gy, gx)) % 360
        return round(r, 3), round(angle_deg, 1)

    def _xy_from_polar(self, r, angle_deg):
        angle_rad = math.radians(angle_deg)
        x = round(r * math.cos(angle_rad), 3)
        y = round(r * math.sin(angle_rad), 3)
        return x, y

    def _create_coord_row(self, row, i, gx, gy, polar, primary_count, total):
        """Create one row of the coordinate panel for unit i."""
        color = COLORS[i % len(COLORS)]

        # color swatch
        swatch = tk.Canvas(self.coord_frame, width=14, height=14,
                           highlightthickness=0, bg=color)
        swatch.grid(row=row, column=0, padx=(4, 2), pady=1, sticky="e")

        tk.Label(self.coord_frame, text=f"Unit {i}:", font=("Menlo", 9)).grid(
            row=row, column=1, padx=(0, 4), sticky="w")

        if polar:
            r_val, angle_val = self._polar_from_xy(gx, gy)
            r_entry = tk.Entry(self.coord_frame, font=("Menlo", 10), width=8, justify=tk.RIGHT)
            self._set_entry(r_entry, f"{r_val:.3f}")
            r_entry.grid(row=row, column=2, padx=2, pady=1)
            a_entry = tk.Entry(self.coord_frame, font=("Menlo", 10), width=8, justify=tk.RIGHT)
            self._set_entry(a_entry, f"{angle_val:.1f}")
            a_entry.grid(row=row, column=3, padx=2, pady=1)
            self._bind_commit(r_entry, lambda idx=i: self.on_polar_entry_commit(idx, "r"))
            self._bind_commit(a_entry, lambda idx=i: self.on_polar_entry_commit(idx, "angle"))
            entries = (r_entry, a_entry)
        else:
            x_entry = tk.Entry(self.coord_frame, font=("Menlo", 10), width=8, justify=tk.RIGHT)
            self._set_entry(x_entry, f"{gx:+.3f}")
            x_entry.grid(row=row, column=2, padx=2, pady=1)
            y_entry = tk.Entry(self.coord_frame, font=("Menlo", 10), width=8, justify=tk.RIGHT)
            self._set_entry(y_entry, f"{gy:+.3f}")
            y_entry.grid(row=row, column=3, padx=2, pady=1)
            self._bind_commit(x_entry, lambda idx=i: self.on_entry_commit(idx, "x"))
            self._bind_commit(y_entry, lambda idx=i: self.on_entry_commit(idx, "y"))
            entries = (x_entry, y_entry)

        # reorder buttons
        reorder_frame = tk.Frame(self.coord_frame)
        reorder_frame.grid(row=row, column=4, padx=2)
        if i < primary_count:
            group_start, group_end = 0, primary_count - 1
        else:
            group_start, group_end = primary_count, total - 1
        if i > group_start:
            tk.Button(reorder_frame, text="^", width=2, font=("Menlo", 8),
                      command=lambda idx=i: self.reorder_unit(idx, -1)).pack(side=tk.LEFT)
        if i < group_end:
            tk.Button(reorder_frame, text="v", width=2, font=("Menlo", 8),
                      command=lambda idx=i: self.reorder_unit(idx, +1)).pack(side=tk.LEFT)

        # unit type label
        type_name = self.get_unit_name_for_index(i) or "?"
        tk.Label(self.coord_frame, text=type_name, font=("Menlo", 9), fg="#666").grid(
            row=row, column=5, padx=(4, 0), sticky="w")

        return entries

    def update_coord_panel(self):
        for w in self.coord_frame.winfo_children():
            w.destroy()
        self.coord_entries = []

        idx = self.combo.current()
        if idx < 0:
            return
        key = self.card_keys[idx]
        offsets = self.offsets[key]
        polar = self.circle_mode.get()

        # show/hide distribute controls
        if polar:
            self.distribute_frame.pack(fill=tk.X, padx=8, pady=(0, 4),
                                       after=self.top_frame)
            # pre-fill radius with mean of current radii
            radii = [math.sqrt(gx ** 2 + gy ** 2) for gx, gy in offsets]
            mean_r = sum(radii) / len(radii) if radii else 1.0
            self._set_entry(self.dist_radius_entry, f"{mean_r:.3f}")
        else:
            self.distribute_frame.pack_forget()

        # header
        col_hdr_a = "r (tiles)" if polar else "x (lat)"
        col_hdr_b = "angle (deg)" if polar else "y (fwd)"
        tk.Label(self.coord_frame, text="Unit", font=("Menlo", 9, "bold"), width=8).grid(row=0, column=0)
        tk.Label(self.coord_frame, text=col_hdr_a, font=("Menlo", 9, "bold"), width=10).grid(row=0, column=2)
        tk.Label(self.coord_frame, text=col_hdr_b, font=("Menlo", 9, "bold"), width=10).grid(row=0, column=3)
        tk.Label(self.coord_frame, text="Type", font=("Menlo", 9, "bold"), width=16).grid(row=0, column=5)

        # collision radius info
        info = self.card_info.get(key, {})
        unit_name = info.get("unit")
        cr = None
        if unit_name and unit_name in self.units:
            cr = self.units[unit_name].get("collisionRadius")
        cr_text = f"collision_radius = {cr}" if cr is not None else "collision_radius = ?"
        sec_unit = info.get("secondaryUnit")
        sec_cr = None
        if sec_unit and sec_unit in self.units:
            sec_cr = self.units[sec_unit].get("collisionRadius")
        if sec_unit:
            sec_cr_text = f", {sec_unit} cr = {sec_cr}" if sec_cr is not None else ""
            cr_text += sec_cr_text
        tk.Label(self.coord_frame, text=cr_text, font=("Menlo", 9), fg="#666").grid(
            row=0, column=6, padx=(8, 0))

        primary_count = self._get_primary_count(key)
        total = len(offsets)

        for i, (gx, gy) in enumerate(offsets):
            entries = self._create_coord_row(i + 1, i, gx, gy, polar, primary_count, total)
            self.coord_entries.append(entries)

        self.coord_frame.update_idletasks()

    def update_coord_entries(self, unit_idx, gx, gy):
        if unit_idx >= len(self.coord_entries):
            return
        if self.circle_mode.get():
            r_val, angle_val = self._polar_from_xy(gx, gy)
            r_e, a_e = self.coord_entries[unit_idx]
            self._set_entry(r_e, f"{r_val:.3f}")
            self._set_entry(a_e, f"{angle_val:.1f}")
        else:
            x_e, y_e = self.coord_entries[unit_idx]
            self._set_entry(x_e, f"{gx:+.3f}")
            self._set_entry(y_e, f"{gy:+.3f}")

    def on_entry_commit(self, unit_idx, axis):
        card_idx = self.combo.current()
        if card_idx < 0:
            return
        key = self.card_keys[card_idx]
        offsets = self.offsets[key]
        if unit_idx >= len(offsets):
            return

        x_e, y_e = self.coord_entries[unit_idx]
        entry = x_e if axis == "x" else y_e
        text = entry.get().strip()
        old_val = offsets[unit_idx][0 if axis == "x" else 1]

        val = self._parse_numeric(text)

        if val is None or not math.isfinite(val):
            entry.config(bg="#ffcccc")
            self.after(500, lambda: entry.config(bg="white"))
            self._set_entry(entry, f"{old_val:+.3f}")
            return

        val = round(val, 3)
        if axis == "x":
            offsets[unit_idx][0] = val
        else:
            offsets[unit_idx][1] = val

        self.modified = True
        self._set_entry(entry, f"{val:+.3f}")
        self.draw_formation()

    def on_polar_entry_commit(self, unit_idx, field):
        card_idx = self.combo.current()
        if card_idx < 0:
            return
        key = self.card_keys[card_idx]
        offsets = self.offsets[key]
        if unit_idx >= len(offsets):
            return

        r_e, a_e = self.coord_entries[unit_idx]
        entry = r_e if field == "r" else a_e

        text = entry.get().strip()
        val = self._parse_numeric(text)

        # compute old polar values for revert
        old_r, old_angle = self._polar_from_xy(*offsets[unit_idx])

        if val is None or not math.isfinite(val):
            entry.config(bg="#ffcccc")
            self.after(500, lambda: entry.config(bg="white"))
            old_display = old_r if field == "r" else old_angle
            fmt = ".3f" if field == "r" else ".1f"
            self._set_entry(entry, f"{old_display:{fmt}}")
            return

        if field == "r":
            r = max(0.0, round(val, 3))
            angle = old_angle
        else:
            r = old_r
            angle = round(val % 360, 1)

        x, y = self._xy_from_polar(r, angle)
        offsets[unit_idx] = [x, y]
        self.modified = True

        # refresh entry display
        self._set_entry(r_e, f"{r:.3f}")
        self._set_entry(a_e, f"{angle:.1f}")
        self.draw_formation()

    def _parse_numeric(self, text):
        """Parse a string as float or simple math expression."""
        try:
            return float(text)
        except ValueError:
            try:
                val = eval(text, {"__builtins__": {}}, {"math": math, "round": round, "abs": abs})
                return float(val)
            except Exception:
                return None

    def reorder_unit(self, unit_idx, direction):
        card_idx = self.combo.current()
        if card_idx < 0:
            return
        key = self.card_keys[card_idx]
        offsets = self.offsets[key]
        target = unit_idx + direction
        if target < 0 or target >= len(offsets):
            return
        offsets[unit_idx], offsets[target] = offsets[target], offsets[unit_idx]
        self.modified = True
        self.update_coord_panel()
        self.draw_formation()

    def apply_distribute(self):
        card_idx = self.combo.current()
        if card_idx < 0:
            return
        key = self.card_keys[card_idx]
        offsets = self.offsets[key]

        radius = self._parse_numeric(self.dist_radius_entry.get().strip())
        start_angle = self._parse_numeric(self.dist_angle_entry.get().strip())
        if radius is None or start_angle is None:
            return
        radius = max(0.0, radius)

        total = len(offsets)

        # distribute all units evenly around the circle as a single group
        if total > 0:
            step = 360.0 / total
            for i in range(total):
                angle = (start_angle + i * step) % 360
                x, y = self._xy_from_polar(radius, angle)
                offsets[i] = [x, y]

        self.modified = True
        self.draw_formation()
        self.update_coord_panel()

    # ── overlay ─────────────────────────────────────────────────────

    def _load_overlay_image(self):
        img_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "formation_visualizer_img")
        if not os.path.isdir(img_dir):
            img_dir = os.path.dirname(os.path.abspath(__file__))
        path = filedialog.askopenfilename(
            title="Select reference image",
            initialdir=img_dir,
            filetypes=[("Image files", "*.jpg *.jpeg *.png *.bmp *.gif"), ("All files", "*.*")])
        if not path:
            return
        self._overlay_pil = Image.open(path).convert("RGBA")
        self._overlay_offset = [0, 0]
        self.overlay_controls_frame.pack(fill=tk.X, padx=8, pady=(0, 4),
                                         after=self.overlay_frame)
        self.grid_frame.pack(fill=tk.X, padx=8, pady=(0, 4),
                             after=self.overlay_controls_frame)
        self.draw_formation()

    def _clear_overlay(self):
        self._overlay_pil = None
        self._overlay_tk = None
        self._overlay_offset = [0, 0]
        self.overlay_controls_frame.pack_forget()
        self.grid_frame.pack_forget()
        self.draw_formation()

    def _on_overlay_entry_commit(self):
        sx = self._parse_numeric(self._scale_x_entry.get().strip())
        sy = self._parse_numeric(self._scale_y_entry.get().strip())
        op = self._parse_numeric(self._opacity_entry.get().strip())
        if sx is not None and sx > 0:
            self._overlay_scale_x = sx
        if sy is not None and sy > 0:
            self._overlay_scale_y = sy
        if op is not None and op > 0:
            self._overlay_opacity = self._clamp(op, MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY)
        self._sync_overlay_entries()
        if self._overlay_pil is not None:
            self.draw_formation()

    def _sync_overlay_entries(self):
        for entry, val in ((self._scale_x_entry, self._overlay_scale_x),
                           (self._scale_y_entry, self._overlay_scale_y),
                           (self._opacity_entry, self._overlay_opacity)):
            self._set_entry(entry, f"{val:.2f}")

    def _draw_overlay(self):
        z = self._view_zoom
        eff_scale_x = self._overlay_scale_x * z
        eff_scale_y = self._overlay_scale_y * z
        opacity = self._overlay_opacity
        src = self._overlay_pil

        sw = max(1, int(src.width * eff_scale_x))
        sh = max(1, int(src.height * eff_scale_y))
        resample = Image.NEAREST if self._overlay_fast else Image.LANCZOS
        scaled = src.resize((sw, sh), resample)

        # center of the image on the canvas (offset stored in unzoomed grid-space)
        cx = CANVAS_HALF + self._view_pan[0] + self._overlay_offset[0] * z
        cy = CANVAS_HALF + self._view_pan[1] + self._overlay_offset[1] * z

        # top-left of the scaled image
        img_x0 = cx - sw / 2
        img_y0 = cy - sh / 2

        # clip to canvas bounds
        clip_x0 = max(0, -img_x0)
        clip_y0 = max(0, -img_y0)
        clip_x1 = min(sw, CANVAS_SIZE - img_x0)
        clip_y1 = min(sh, CANVAS_SIZE - img_y0)

        if clip_x1 <= clip_x0 or clip_y1 <= clip_y0:
            return

        cropped = scaled.crop((int(clip_x0), int(clip_y0), int(clip_x1), int(clip_y1)))

        # alpha blend with white background
        cw, ch = cropped.size
        white = Image.new("RGBA", (cw, ch), (255, 255, 255, 255))
        blended = Image.blend(white, cropped, opacity)
        blended_rgb = blended.convert("RGB")

        self._overlay_tk = ImageTk.PhotoImage(blended_rgb)

        place_x = max(0, int(img_x0 + clip_x0))
        place_y = max(0, int(img_y0 + clip_y0))
        self.canvas.create_image(place_x, place_y, image=self._overlay_tk,
                                 anchor=tk.NW, tags="overlay")

    def _on_pan_press(self, event):
        # Shift held -> overlay-only drag; otherwise pan both grid+overlay
        overlay_only = bool(event.state & 0x1)
        self._pan_drag_state = {
            "start_x": event.x,
            "start_y": event.y,
            "overlay_only": overlay_only,
            "orig_pan": list(self._view_pan),
            "orig_offset": list(self._overlay_offset),
        }

    def _on_pan_drag(self, event):
        if not self._pan_drag_state:
            return
        dx = event.x - self._pan_drag_state["start_x"]
        dy = event.y - self._pan_drag_state["start_y"]
        if self._pan_drag_state["overlay_only"]:
            # convert screen delta to unzoomed offset
            z = self._view_zoom
            self._overlay_offset[0] = self._pan_drag_state["orig_offset"][0] + dx / z
            self._overlay_offset[1] = self._pan_drag_state["orig_offset"][1] + dy / z
        else:
            self._view_pan[0] = self._pan_drag_state["orig_pan"][0] + dx
            self._view_pan[1] = self._pan_drag_state["orig_pan"][1] + dy
        self._draw_debounced()

    def _on_pan_release(self, event):
        self._pan_drag_state = None
        # final quality redraw on release
        self._overlay_fast = False
        self.draw_formation()

    # ── zoom ───────────────────────────────────────────────────────────

    def _on_scroll_zoom(self, event):
        # determine scroll direction
        if event.num == 4:
            delta = 1
        elif event.num == 5:
            delta = -1
        else:
            delta = 1 if event.delta > 0 else -1

        factor = ZOOM_FACTOR if delta > 0 else 1 / ZOOM_FACTOR
        old_zoom = self._view_zoom
        new_zoom = self._clamp(old_zoom * factor, MIN_VIEW_ZOOM, MAX_VIEW_ZOOM)
        if new_zoom == old_zoom:
            return

        # mouse-centered zoom: keep point under cursor fixed
        mx, my = event.x, event.y
        self._view_pan[0] = mx - (mx - CANVAS_HALF - self._view_pan[0]) * new_zoom / old_zoom - CANVAS_HALF
        self._view_pan[1] = my - (my - CANVAS_HALF - self._view_pan[1]) * new_zoom / old_zoom - CANVAS_HALF
        self._view_zoom = new_zoom
        self._draw_debounced()

    def _reset_view(self):
        self._view_pan = [0.0, 0.0]
        self._view_zoom = 1.0
        self.draw_formation()

    # ── calibration ────────────────────────────────────────────────────

    def _start_calibration(self):
        self._calibrating = True
        self._calib_rect = None
        self.canvas.config(cursor="crosshair")
        self._show_status("Draw rectangle over known tiles...", "blue")

    def _finish_calibration(self):
        self.canvas.config(cursor="")
        self._calibrating = False
        if not self._calib_rect:
            self._show_status("")
            return
        x0, y0, x1, y1 = self._calib_rect
        rect_w = abs(x1 - x0)
        rect_h = abs(y1 - y0)
        if rect_w < 10 or rect_h < 10:
            self._show_status("Rectangle too small", "red", 2000)
            return
        # clean up the dashed rect
        if self._calib_canvas_id:
            self.canvas.delete(self._calib_canvas_id)
            self._calib_canvas_id = None
        self._show_calibration_dialog(rect_w, rect_h)

    def _show_calibration_dialog(self, rect_w_px, rect_h_px):
        dlg = tk.Toplevel(self)
        dlg.title("Calibrate Grid")
        dlg.transient(self)
        dlg.grab_set()
        dlg.resizable(False, False)

        tk.Label(dlg, text=f"Rectangle: {rect_w_px:.0f} x {rect_h_px:.0f} px",
                 font=("Menlo", 10)).pack(padx=12, pady=(12, 4))

        row = tk.Frame(dlg)
        row.pack(padx=12, pady=4)
        tk.Label(row, text="Width in tiles:", font=("Menlo", 9)).pack(side=tk.LEFT)
        w_entry = tk.Entry(row, font=("Menlo", 10), width=6)
        w_entry.insert(0, "1")
        w_entry.pack(side=tk.LEFT, padx=4)

        row2 = tk.Frame(dlg)
        row2.pack(padx=12, pady=4)
        tk.Label(row2, text="Height in tiles:", font=("Menlo", 9)).pack(side=tk.LEFT)
        h_entry = tk.Entry(row2, font=("Menlo", 10), width=6)
        h_entry.insert(0, "1")
        h_entry.pack(side=tk.LEFT, padx=4)

        btn_row = tk.Frame(dlg)
        btn_row.pack(padx=12, pady=(4, 12))

        def apply_cb():
            tw = self._parse_numeric(w_entry.get().strip())
            th = self._parse_numeric(h_entry.get().strip())
            if tw and th and tw > 0 and th > 0:
                self._apply_calibration(rect_w_px, rect_h_px, tw, th)
                dlg.destroy()
                self._show_status("Calibration applied", timeout_ms=3000)

        def cancel_cb():
            dlg.destroy()
            self._show_status("")

        tk.Button(btn_row, text="Apply", command=apply_cb).pack(side=tk.LEFT, padx=4)
        tk.Button(btn_row, text="Cancel", command=cancel_cb).pack(side=tk.LEFT, padx=4)

        dlg.bind("<Return>", lambda e: apply_cb())
        dlg.bind("<Escape>", lambda e: cancel_cb())
        w_entry.focus_set()

    def _apply_calibration(self, rect_w_px, rect_h_px, tiles_w, tiles_h):
        z = self._view_zoom
        target = self.tile_px * z  # grid's px-per-tile on screen
        screen_tile_x = rect_w_px / tiles_w  # overlay's current px-per-tile (x)
        screen_tile_y = rect_h_px / tiles_h  # overlay's current px-per-tile (y)

        # scale overlay so its tiles match the grid's square tiles
        ratio_x = target / screen_tile_x
        ratio_y = target / screen_tile_y
        self._overlay_scale_x *= ratio_x
        self._overlay_scale_y *= ratio_y

        # normalize: adjust tile_px and zoom so overlay scales stay near 1.0
        # visual invariant: tile_px * zoom stays constant, so the grid looks the same
        avg_scale = math.sqrt(self._overlay_scale_x * self._overlay_scale_y)
        if avg_scale > 0:
            self.tile_px /= avg_scale
            self._view_zoom *= avg_scale
            self._overlay_scale_x /= avg_scale
            self._overlay_scale_y /= avg_scale

        self._sync_overlay_entries()

        # lock grid so tile_px stays fixed across card switches
        self._grid_locked.set(True)
        self._set_tile_px_widgets_state("normal")
        self._sync_tile_px_entry()
        self.draw_formation()

    def _set_tile_px_widgets_state(self, state):
        self._tile_px_minus.config(state=state)
        self._tile_px_plus.config(state=state)
        self._tile_px_entry.config(state=state)

    def _on_grid_lock_changed(self):
        if self._grid_locked.get():
            self._set_tile_px_widgets_state("normal")
            self._sync_tile_px_entry()
        else:
            self._set_tile_px_widgets_state("disabled")
            self.draw_formation()

    def _on_tile_px_entry_commit(self):
        if not self._grid_locked.get():
            return
        val = self._parse_numeric(self._tile_px_entry.get().strip())
        if val is not None and val >= MIN_TILE_PX:
            self.tile_px = val
        self._sync_tile_px_entry()
        self.draw_formation()

    def _sync_tile_px_entry(self):
        self._tile_px_entry.config(state="normal")
        self._set_entry(self._tile_px_entry, f"{self.tile_px:.0f}")
        if not self._grid_locked.get():
            self._tile_px_entry.config(state="disabled")

    # ── card selection ────────────────────────────────────────────────

    def on_card_selected(self):
        self.draw_formation()
        self.update_coord_panel()

    # ── drag ──────────────────────────────────────────────────────────

    def on_press(self, event):
        if self._calibrating:
            self._calib_rect = (event.x, event.y, event.x, event.y)
            return
        items = self.canvas.find_overlapping(event.x - 2, event.y - 2,
                                             event.x + 2, event.y + 2)
        for item in items:
            tags = self.canvas.gettags(item)
            for tag in tags:
                if tag.startswith("unit_"):
                    unit_idx = int(tag.split("_")[1])
                    card_idx = self.combo.current()
                    if card_idx < 0:
                        return
                    key = self.card_keys[card_idx]
                    gx, gy = self.offsets[key][unit_idx]
                    px, py = self.tile_to_pixel(gx, gy)
                    self.drag_state = {
                        "unit_idx": unit_idx,
                        "offset_x": event.x - px,
                        "offset_y": event.y - py,
                    }
                    self.canvas.tag_raise(tag)
                    return

    def on_drag(self, event):
        if self._calibrating and self._calib_rect:
            x0, y0 = self._calib_rect[0], self._calib_rect[1]
            self._calib_rect = (x0, y0, event.x, event.y)
            if self._calib_canvas_id:
                self.canvas.delete(self._calib_canvas_id)
            self._calib_canvas_id = self.canvas.create_rectangle(
                x0, y0, event.x, event.y,
                outline="#2196F3", width=2, dash=(6, 4))
            return
        if not self.drag_state:
            return
        card_idx = self.combo.current()
        if card_idx < 0:
            return
        key = self.card_keys[card_idx]
        uid = self.drag_state["unit_idx"]

        px = event.x - self.drag_state["offset_x"]
        py = event.y - self.drag_state["offset_y"]
        gx, gy = self.pixel_to_tile(px, py)

        self.offsets[key][uid] = [gx, gy]
        self.modified = True
        self.draw_unit_circle(uid, gx, gy)
        self.update_coord_entries(uid, gx, gy)

    def on_release(self, event):
        if self._calibrating:
            self._finish_calibration()
            return
        self.drag_state = None

    # ── save ──────────────────────────────────────────────────────────

    def format_json(self, data):
        lines = ["{"]
        keys = list(data.keys())
        for i, key in enumerate(keys):
            pairs = data[key]
            pair_strs = [f"[{x}, {y}]" for x, y in pairs]
            inner = ", ".join(pair_strs)
            comma = "," if i < len(keys) - 1 else ""
            lines.append(f'  "{key}": [{inner}]{comma}')
        lines.append("}")
        return "\n".join(lines) + "\n"

    def save(self):
        path = os.path.join(self.data_dir, "formation_offsets.json")
        text = self.format_json(self.offsets)
        with open(path, "w") as f:
            f.write(text)
        self.modified = False
        self._show_status(f"Saved to {self.data_dir}", timeout_ms=4000)

    def on_close(self):
        if self.modified:
            result = messagebox.askyesnocancel(
                "Unsaved Changes", "Save changes before closing?")
            if result is None:
                return
            if result:
                self.save()
        self.destroy()


def main():
    parser = argparse.ArgumentParser(description="Visual editor for formation_offsets.json spawn positions.")
    parser.add_argument("data_dir",
                        help="Directory containing formation_offsets.json, parsed_cards.json, and parsed_units.json")
    args = parser.parse_args()
    app = FormationVisualizer(args.data_dir)
    app.mainloop()


if __name__ == "__main__":
    main()
