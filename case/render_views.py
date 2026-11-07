"""Depth-buffered previews and true cross-sections. Matplotlib's 3D view draws
surfaces in the wrong order, so it can't be trusted for checking a model's shape."""
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import PathPatch
from matplotlib.path import Path as MplPath

COLOURS = {
    "front_shell": "#3b4252", "back_lid": "#5a6680", "clamp_upper": "#7f8ca8",
    "clamp_lower": "#9aa6c0", "display": "#2e9fd6", "xiao": "#3aa76d", "bar": "#c8ccd4",
    "lens": "#bfe3f5", "bracket": "#4f8a8b", "latch_clip": "#e08a3c",
}


def _rotation(elev, azim):
    a, e = np.radians(azim), np.radians(elev)
    rz = np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1]])
    rx = np.array([[1, 0, 0], [0, np.cos(e), -np.sin(e)], [0, np.sin(e), np.cos(e)]])
    return rx @ rz


def render(path, parts, title, elev, azim, size=760, up_z=False):
    """Orthographic z-buffer. Screen x/y come from the rotated x/y; larger z is nearer."""
    rot = _rotation(elev, azim)
    if up_z:
        # Put the model's +Z up on screen, for views along the clamp.
        rot = rot @ np.array([[1, 0, 0], [0, 0, 1], [0, -1, 0]])
    data = []
    for name, m in parts:
        v = m.to_mesh()
        data.append((name, v.vert_properties[:, :3] @ rot.T, v.tri_verts))
    allv = np.vstack([d[1] for d in data])
    lo, hi = allv.min(0), allv.max(0)
    scale = (size * 0.92) / max(hi[0] - lo[0], hi[1] - lo[1])
    off = np.array([size / 2 - scale * (lo[0] + hi[0]) / 2, size / 2 - scale * (lo[1] + hi[1]) / 2])

    image = np.full((size, size, 3), 1.0)
    depth = np.full((size, size), -np.inf)
    light = np.array([0.35, 0.55, 0.76])
    for name, verts, tris in data:
        base = np.array(matplotlib.colors.to_rgb(COLOURS.get(name, "#888888")))
        pts = verts[:, :2] * scale + off
        zs = verts[:, 2]
        for tri in tris:
            a, b, c = pts[tri[0]], pts[tri[1]], pts[tri[2]]
            area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            if area <= 1e-9:
                continue  # facing away, or too small to see
            n = np.cross(verts[tri[1]] - verts[tri[0]], verts[tri[2]] - verts[tri[0]])
            n /= np.linalg.norm(n) + 1e-12
            shade = 0.38 + 0.62 * max(0.0, float(n @ light))
            colour = np.clip(base * shade + 0.05, 0, 1)
            x0, x1 = max(int(min(a[0], b[0], c[0])), 0), min(int(max(a[0], b[0], c[0])) + 1, size - 1)
            y0, y1 = max(int(min(a[1], b[1], c[1])), 0), min(int(max(a[1], b[1], c[1])) + 1, size - 1)
            if x1 < x0 or y1 < y0:
                continue
            xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            w0 = ((b[0] - xs) * (c[1] - ys) - (b[1] - ys) * (c[0] - xs)) / area
            w1 = ((c[0] - xs) * (a[1] - ys) - (c[1] - ys) * (a[0] - xs)) / area
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-9) & (w1 >= -1e-9) & (w2 >= -1e-9)
            if not inside.any():
                continue
            z = w0 * zs[tri[0]] + w1 * zs[tri[1]] + w2 * zs[tri[2]]
            row, col = np.nonzero(inside)
            row_abs, col_abs = row + y0, col + x0
            nearer = z[row, col] > depth[row_abs, col_abs]
            depth[row_abs[nearer], col_abs[nearer]] = z[row, col][nearer]
            image[row_abs[nearer], col_abs[nearer]] = colour
    fig, ax = plt.subplots(figsize=(size / 100, size / 100), dpi=100)
    ax.imshow(image, origin="lower")
    ax.set_title(title)
    ax.axis("off")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


def _section(ax, parts, to_plane, title, xlabel, ylabel):
    for name, m in parts:
        polys = to_plane(m).slice(0.0).to_polygons()
        if not polys:
            continue
        verts, codes = [], []
        for p in polys:
            p = np.asarray(p)
            verts += list(p) + [p[0]]
            codes += [MplPath.MOVETO] + [MplPath.LINETO] * (len(p) - 1) + [MplPath.CLOSEPOLY]
        ax.add_patch(PathPatch(MplPath(np.array(verts), codes), facecolor=COLOURS.get(name, "#888"),
                               edgecolor="black", linewidth=0.4, label=name))
    ax.set_aspect("equal")
    ax.autoscale_view()
    ax.grid(alpha=0.3)
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)


def latch_sections(path, parts, tooth_z, arm_z):
    fig, axes = plt.subplots(1, 2, figsize=(13, 7))
    _section(axes[0], parts, lambda m: m.translate((0, 0, -tooth_z)),
             f"Slice at z = {tooth_z:.1f} (through the teeth and sleeve)", "X (mm)", "Y (mm)")
    _section(axes[1], parts, lambda m: m.translate((0, 0, -arm_z)),
             f"Slice at z = {arm_z:.1f} (through the latch's arms)", "X (mm)", "Y (mm)")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=len(labels))
    fig.savefig(path, bbox_inches="tight", dpi=90)
    plt.close(fig)


def sections(path, parts, post_x, behind_pcb_z):
    fig, axes = plt.subplots(2, 2, figsize=(13, 13))
    # Slice at x, drawn as z across and y up.
    _section(axes[0, 0], parts, lambda m: m.rotate((0, -90, 0)), "Slice at x = 0 (along the case centre)",
             "Z, depth from the front face (mm)", "Y (mm)")
    _section(axes[0, 1], parts, lambda m: m.translate((-post_x, 0, 0)).rotate((0, -90, 0)),
             f"Slice at x = {post_x} (through the display screws)", "Z (mm)", "Y (mm)")
    _section(axes[1, 0], parts, lambda m: m.translate((0, 0, -1.0)), "Slice at z = 1 (front face, around the window)", "X (mm)", "Y (mm)")
    _section(axes[1, 1], parts, lambda m: m.translate((0, 0, -behind_pcb_z)),
             f"Slice at z = {behind_pcb_z:.1f} (just behind the display PCB)", "X (mm)", "Y (mm)")
    handles, labels = axes[0, 0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=len(labels))
    fig.savefig(path, bbox_inches="tight", dpi=90)
    plt.close(fig)
