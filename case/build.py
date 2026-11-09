#!/usr/bin/env python3
"""MapMirror handlebar case: parametric model, fit checks, STL export and previews.

Run:  .venv/Scripts/python build.py

Assembly coordinates, in mm: the display faces -Z and z=0 is the front face.
X runs along the handlebar, and Y is up on the display when the case is level.

The display screws to a bracket. The XIAO tucks under lips on the bracket's back, and a
strap screws down over its far end. The bracket drops into the shell and screws down onto
two ledges, and four M3 screws close the lid. Everything inside is screwed, not snapped. The box then slides down onto a handlebar
mount, and a two-button latch on the mount locks it there. You can print a mount for each
vehicle: the box carries nothing that stays on the bike, apart from its USB cable.
Change the parameters below, then run it again.
"""
import math
import sys
from pathlib import Path

import trimesh
from manifold3d import JoinType, Manifold

from render_views import latch_sections, render, sections

# ---------------------------------------------------------------- parts you own
BAR_D = 22.2           # handlebar: 22.2 (7/8") is common, 25.4 (1") and 28.6 (1-1/8") also exist
RUBBER = 1.0           # rubber strip between clamp and bar

# Waveshare 1.69" LCD Module, from its published 2D drawing. All Z values start at the glass.
DISPLAY_W, DISPLAY_H = 31.5, 39.0
GLASS_W, GLASS_H = 30.07, 37.43
GLASS_T = 1.9          # glass and backlight, in front of the PCB (estimated from the drawing)
PCB_BACK_Z = 3.4       # glass front to back of PCB
STANDOFF_TIP_Z = 5.9   # glass front to tips of the M2 brass standoffs
CONNECTOR_TIP_Z = 7.1  # glass front to top of the connector
STANDOFF_X, STANDOFF_Y = 12.15, 15.9   # 24.3 x 31.8 mm hole spacing, centred on the PCB
STANDOFF_OD = 3.6      # brass standoff outside diameter (estimated)
ACTIVE_W, ACTIVE_H = 27.97, 32.63      # visible picture area
# The ribbon end is the bottom. The picture sits 1.92 mm from the top of the PCB and 4.45 mm
# from the bottom, so its centre is 1.27 mm above the PCB's centre.
ACTIVE_OFFSET_Y = (4.45 - 1.92) / 2

XIAO_W, XIAO_L = 17.8, 21.0        # XIAO ESP32-C3 board
XIAO_PCB_T = 1.0
XIAO_TOP = 4.0         # tallest part above the board, including the USB-C socket
USB_W, USB_H, USB_OVERHANG = 9.0, 3.2, 1.5   # USB-C socket
PLUG_W, PLUG_H = 12.5, 6.9         # opening for a USB-C plug's moulded body. Measure your cable.

# ---------------------------------------------------------------- fit and printing
FIT = 0.2              # clearance per side for parts dropped into a pocket
CAVITY_FIT = 0.5       # around the display and bracket in the shell, for printers that print tight
CAVITY_EXTRA_H = 1.0   # more along the display's length, which a test print showed it needs
GLASS_GAP = 0.3        # between glass and the lens pocket's back, so the lens never presses on the glass
LENS_W, LENS_H, LENS_T = 31.8, 39.3, 1.5   # clear acrylic or polycarbonate window, fitted from inside

# ---------------------------------------------------------------- printing without support
LAYER = 0.2
SELF_SUPPORTING = 0.45 # a wider unsupported strip in one layer counts as an overhang
FRONT_CHAMFER = 0.6    # 45 degrees, and it stops elephant's foot showing on the bed face

# ---------------------------------------------------------------- box
OUTER_W, OUTER_H = 45.0, 51.0      # wider than the cavity needs, for the M3 screws and the bracket's ledges
CORNER_R = 3.5
BEZEL_T = 1.2          # solid front face around the window, printed on the bed
LENS_DEPTH = LENS_T + 0.1
BODY_DEPTH = 18.5      # front face to the lid
LID_T = 3.0
WIRE_ROOM = 0.6        # space under the XIAO for the display's plug and wires
PORT_CHAMFER = 1.0     # top corners of the USB port, ahead of the short bridge across it
MIN_WALL = 1.5         # solid wall left behind a groove

# ---------------------------------------------------------------- lid screws
SCREW_X, SCREW_Y = 19.0, 22.0      # concentric with the box's rounded corners
M3_CLEAR = 3.4
M3_HEAD_D, M3_HEAD_H = 5.7, 1.65   # button head
M3_RECESS_D = 6.2
M3_LENGTH = 8.0       # the latch's screw, tapping into the mount
# The lid screws tap their own threads straight into the shell's corners, with no inserts.
M3_PILOT_D = 2.6       # pilot hole for an M3 screw to cut its thread in printed plastic
M3_LID_LENGTH = 10.0
M3_BITE = 6.0          # thread the lid screws need in the shell, twice their diameter
M3_HOLE_DEPTH = 11.0
UNDER_HEAD = 2.0       # plastic under each lid screw's head

# ---------------------------------------------------------------- display bracket
BRACKET_T = 2.0
M2_CLEAR, M2_HEAD_D, M2_HEAD_H = 2.3, 3.8, 1.6
M2_LENGTH = 4.0
M2_MAX_THREAD = 2.5    # brass standoff height, so screws must not go further in than this
# The bracket drops into the shell. Ears on its long sides rest on the floors of two slots
# in the side walls, and four M2 screws hold them down, tapping straight into the ledges.
LEDGE_DEPTH = 4.5      # slots into the side walls, beyond the cavity
LEDGE_HALF_L = 7.0     # half the ears' length
EAR_CLEAR = 0.4        # at each end of each ear in its slot
EAR_TIP_CLEAR = 0.25   # beyond each ear's tip
EAR_SCREW_Y = 4.5      # two screws in each ear, at +-this
M2_INSERT_D, M2_INSERT_L = 3.2, 3.0   # M2 x 3 heat-set insert, about 3.2 mm hole
M2_PILOT_D = 1.7       # pilot hole for an M2 screw to cut its thread in printed plastic
M2_PILOT_HOLE = 6.5    # depth of the blind pilot holes in the ledges
M2_EAR_LENGTH = 8.0    # M2 screws for the ears, into the ledges
M2_BITE = 4.0          # thread the ear screws need in the ledges, twice their diameter
M2_CLAMP_LENGTH = 6.0  # M2 screws for the XIAO's strap, into inserts in the bracket
# A flat printed strap clamps the XIAO's far end: two tabs press the board's corners, and
# two M2 screws pull it down onto bosses on the bracket, into heat-set inserts.
STRAP_T = 2.5
STRAP_W = 5.0          # the bar beyond the board's end
STRAP_SCREW_X = 12.3
STRAP_BOSS_D = 6.5     # the bracket's bosses, and the round pads around the strap's screw holes
STRAP_PRELOAD = 0.05   # the tabs reach this far below the board's top, so the screws clamp it
CUTOUT_W, CUTOUT_Y0, CUTOUT_Y1 = 17.0, -11.0, -1.5   # opening for the connector and wires
# The XIAO covers the opening, so it reaches past the board's long edges, level with the
# pins the display uses (2nd to 6th from the USB end). The wires come up there to the pins.
WIRE_SLOT, WIRE_SLOT_Y0 = 2.0, -13.8
XIAO_LIP = 0.8         # lips over the XIAO's long edges, at its USB end

# ---------------------------------------------------------------- quick release, on the lid
SLEEVE_H = 3.0         # the dovetail channel's height
SLEEVE_HALF_W = 14.6   # outside
CHANNEL_HALF_W = 12.5  # inside, at the lid. It narrows at 45 degrees towards the mount.
SLEEVE_STOP_Y = 20.05  # closed end of the channel, which the rail stops against
LEAD_IN = 0.6          # the open end flares by this much, to find the rail easily
LEAD_IN_L = 3.5
RAMP_DEPTH = 0.8       # the lid's back is cut down at the open end, to ride over the rubber pad

# ---------------------------------------------------------------- quick release, on the mount
RAIL_GAP = 0.2         # rail's top to the lid, before the pad pushes them apart
QR_FIT = 0.4           # per side, for the quick release's sliding parts. 0.2 printed too tight
RAIL_CLEAR = QR_FIT    # across the dovetail, per side
RAIL_STOP_Y, RAIL_BOTTOM_Y = 20.0, -22.0
RAIL_UPPER_Y, RAIL_LOWER_Y = 15.0, -15.0   # the rail's full dovetail is above and below these
ISLAND_HALF_W = 4.6    # centre of the rail, which also limits how far the latch can bend
MOUNT_GAP = 0.3        # lid's sleeve to the mount's plate
MOUNT_PLATE_W, MOUNT_PLATE_T = 30.0, 4.7
PAD_HALF_W, PAD_Y0, PAD_Y1 = 4.0, -5.0, 13.0   # rubber pad that takes up the dovetail's play
PAD_PROUD = 0.9        # 1 mm rubber stands this far above the rail

# ---------------------------------------------------------------- latch
LATCH_T = 3.0          # base thickness, which prints flat
LATCH_ROOT_HALF_W, LATCH_ROOT_Y0, LATCH_ROOT_Y1 = 9.4, -14.5, -7.5
LATCH_ARM_X0, LATCH_ARM_X1 = 7.4, 9.4
TOOTH_Y0, TOOTH_Y1 = 9.0, 14.0
TOOTH_OUTER_X = 12.0   # at rest
TOOTH_TOP = 22.6       # how far the tooth rises from the latch, towards the lid
CATCH_ANGLE = 15.0     # slopes the tooth's catch, so its spring pulls the box firmly home
PRELOAD = 0.6          # the arm stays bent by this much with the box locked on
BUTTON_X0, BUTTON_X1, BUTTON_Y0, BUTTON_Y1 = 17.6, 21.0, 6.0, 17.0
BUTTON_TOP = 22.5      # clears the lid's raised corners as the box slides past
LATCH_SCREW_Y = -11.0
LATCH_HOLE_DEPTH = 8.0 # pilot hole in the mount, below the latch's pocket

# ---------------------------------------------------------------- handlebar clamp
CLAMP_W = 17.0         # along the bar. 22 was too wide for the gap on a Karizma XMR
CLAMP_WALL = 5.0       # around the bar on the lower half
PARTING_GAP = 1.5      # clamp halves stop this far apart, so the bolts can squeeze
M4_CLEAR = 4.5
M4_HEAD_D, M4_HEAD_H = 7.6, 4.5
# The clamp bolts tap their own threads into the upper mount, with no inserts.
M4_PILOT_D = 3.5       # pilot hole for an M4 bolt to cut its thread in printed plastic
M4_BITE = 8.0          # thread the bolts need in the mount, twice their diameter
M4_HOLE_DEPTH = 11.0   # straight part of the hole, which then ends in a 45 degree cone
BOLT_MARGIN = 5.0      # bar surface to bolt centre
M4_SEAT = 6.0          # bar centre to the bolt head seat, which sets the wing thickness
M4_LENGTH = 16.0
BAR_RELIEF = 0.8       # flat above the top of the bar, so the saddle prints as a short bridge
# A solid neck, as narrow along the bar as the clamp, that lifts the rail plate and box
# above whatever sits beside the clamp on the bar, such as the switch block on clip-ons.
# SIDE_H is how far the tallest part beside the clamp stands above the top of the bar, on the
# side the box faces. 0 means nothing beside the clamp is in the way. The 16 mm default is an
# estimate, from photos, for the left switch block of a Hero Karizma XMR 210: measure yours.
SIDE_H = 16.0
SIDE_GAP = 0.0         # along the bar, from the clamp's edge to that part. 0 is the worst case
SIDE_CLEAR = 3.0       # margin above it, for vibration and the switch block's own play
PLATE_SLOPE = (MOUNT_PLATE_W - CLAMP_W) / 2   # the plate's 45 degree underside, beside the clamp
MOUNT_RISE = (max(0.0, SIDE_H + SIDE_CLEAR + max(0.0, PLATE_SLOPE - SIDE_GAP) - BAR_RELIEF - RUBBER)
              if SIDE_H > 0 else 0.0)

SEG = 96


# ---------------------------------------------------------------- helpers
def box(x0, x1, y0, y1, z0, z1):
    return Manifold.cube((x1 - x0, y1 - y0, z1 - z0)).translate((x0, y0, z0))


def rounded(w, h, r, z0, z1):
    """Rounded rectangle prism centred on the Z axis."""
    posts = [
        Manifold.cylinder(z1 - z0, r, circular_segments=SEG).translate((sx * (w / 2 - r), sy * (h / 2 - r), z0))
        for sx in (-1, 1) for sy in (-1, 1)
    ]
    return Manifold.batch_hull(posts)


def zcyl(d, z0, z1, x=0.0, y=0.0):
    return Manifold.cylinder(z1 - z0, d / 2, circular_segments=SEG).translate((x, y, z0))


def xcyl(d, x0, x1, y=0.0, z=0.0):
    return Manifold.cylinder(x1 - x0, d / 2, circular_segments=SEG).rotate((0, 90, 0)).translate((x0, y, z))


def wedge(points):
    return Manifold.hull_points([tuple(p) for p in points])


def prism(outline, z0, z1):
    """A convex XY outline, extruded along Z."""
    return wedge([(x, y, z) for x, y in outline for z in (z0, z1)])


def mirrored(sx, outline):
    return [(sx * x, y) for x, y in outline]


def standoffs():
    return [(sx * STANDOFF_X, sy * STANDOFF_Y) for sx in (-1, 1) for sy in (-1, 1)]


def screws():
    return [(sx * SCREW_X, sy * SCREW_Y) for sx in (-1, 1) for sy in (-1, 1)]


def combine(items):
    return sum(items[1:], items[0])


def strain(thickness, deflection, length):
    """Peak bending strain of a cantilever, as a percentage."""
    return 1.5 * thickness * deflection / length ** 2 * 100


def teardrop_z(d, z0, z1, x=0.0, y=0.0):
    """Hole along Z with a 45 degree peak towards +X, for parts printed with +X up."""
    peak = d / 2 * math.sqrt(2)
    tip = box(peak - 0.01, peak, -0.005, 0.005, z0, z1)
    return Manifold.batch_hull([zcyl(d, z0, z1), tip]).translate((x, y, 0))


def bar_saddle():
    """The bar's bore, with 45 degree sides and a short flat towards the lid."""
    half = BAR_R * math.sqrt(2) - (BAR_R + BAR_RELIEF)
    z = BAR_Z - BAR_R - BAR_RELIEF
    span = OUTER_W + 2
    return Manifold.batch_hull([xcyl(BAR_R * 2, -span / 2, span / 2, z=BAR_Z),
                                box(-span / 2, span / 2, -half, half, z, z + 0.01)])


def channel_half_w(z):
    """The dovetail channel's half width at depth z."""
    return CHANNEL_HALF_W - (z - LID_BACK)


def dovetail(half_w, y0, y1, z0, z1):
    """A dovetail along Y, narrowing at 45 degrees as z increases. half_w is a function of z."""
    return wedge([(sx * half_w(z), y, z) for sx in (-1, 1) for y in (y0, y1) for z in (z0, z1)])


# ---------------------------------------------------------------- derived sizes
CAVITY_W = DISPLAY_W + 2 * CAVITY_FIT
CAVITY_H = DISPLAY_H + 2 * CAVITY_FIT + CAVITY_EXTRA_H
CAVITY_R = 0.5         # small, so the display's square corners clear it wherever the ears let it sit
WINDOW_W, WINDOW_H = ACTIVE_W + 1.5, ACTIVE_H + 1.5
LENS_BACK = BEZEL_T + LENS_DEPTH
GLASS_Z = LENS_BACK + GLASS_GAP          # front of the display glass
BRACKET_FRONT = GLASS_Z + STANDOFF_TIP_Z
BRACKET_BACK = BRACKET_FRONT + BRACKET_T
XIAO_Z = max(BRACKET_BACK, GLASS_Z + CONNECTOR_TIP_Z) + WIRE_ROOM   # back of the XIAO's board
XIAO_PCB_TOP = XIAO_Z + XIAO_PCB_T
XIAO_MAX = XIAO_PCB_TOP + XIAO_TOP
USB_LIP_Y = -CAVITY_H / 2 + EAR_CLEAR + 0.2   # the socket's mouth, just inside the wall
XIAO_Y0 = USB_LIP_Y + USB_OVERHANG       # the board's USB end
XIAO_Y1 = XIAO_Y0 + XIAO_L
USB_Z = XIAO_PCB_TOP + USB_H / 2
EAR_X = CAVITY_W / 2 + LEDGE_DEPTH - EAR_TIP_CLEAR   # the ears' tips
EAR_SCREW_X = CAVITY_W / 2 + 2.2           # midway across the ledge, with plastic both sides of the thread
STRAP_Z = XIAO_PCB_TOP - STRAP_PRELOAD   # the strap's underside, and its bosses' tops
STRAP_Y0 = XIAO_Y1 + 0.3                 # the strap's bar starts just beyond the board's end
STRAP_TAB_Y0 = XIAO_Y1 - 1.5             # its tabs reach back over the board's corners
STRAP_TAB_X0, STRAP_TAB_X1 = XIAO_W / 2 - XIAO_LIP, XIAO_W / 2 + FIT
USB_LIP_L = 1.6                          # length of the lips at the USB end
STOP_TOP = XIAO_Z + 0.6                  # far-end stops catch the board's edge, below its top
TILT = 3.0                               # degrees the XIAO's far end is raised while it goes in

LID_BACK = BODY_DEPTH + LID_T
SCREW_SEAT = BODY_DEPTH + UNDER_HEAD     # lid screw head's underside
BOSS_TOP = SCREW_SEAT + M3_HEAD_H + 0.05
SLEEVE_BACK = LID_BACK + SLEEVE_H
RAIL_TOP = LID_BACK + RAIL_GAP
MOUNT_TOP = SLEEVE_BACK + MOUNT_GAP
MOUNT_BACK = MOUNT_TOP + MOUNT_PLATE_T
LATCH_Z0 = MOUNT_TOP + 0.3               # latch's lid side, just below the plate's surface
LATCH_Z1 = LATCH_Z0 + LATCH_T
POCKET_FLOOR = LATCH_Z1 + 0.1
BAR_R = BAR_D / 2 + RUBBER
BAR_Z = MOUNT_BACK + MOUNT_RISE + BAR_RELIEF + BAR_R
BOLT_Y = BAR_R + BOLT_MARGIN
CLAMP_H = 2 * (BOLT_Y + CLAMP_WALL)
PARTING_UPPER = BAR_Z - PARTING_GAP / 2
CATCH_SLOPE = math.tan(math.radians(CATCH_ANGLE))


# ---------------------------------------------------------------- parts
def front_shell():
    """Printed face down. The lens fits from inside, so the front is one flat bed-side surface."""
    body = Manifold.batch_hull([
        rounded(OUTER_W - 2 * FRONT_CHAMFER, OUTER_H - 2 * FRONT_CHAMFER, CORNER_R - FRONT_CHAMFER, 0, 0.01),
        rounded(OUTER_W, OUTER_H, CORNER_R, FRONT_CHAMFER, BODY_DEPTH)])

    # With this much clearance, the display's square corners clear the rounded ones.
    cavity = rounded(CAVITY_W, CAVITY_H, CAVITY_R, BEZEL_T, BODY_DEPTH + 1)
    window = rounded(WINDOW_W, WINDOW_H, 4.5, -1, BEZEL_T + 0.1).translate((0, ACTIVE_OFFSET_Y, 0))

    # USB-C port in the bottom wall, sized for the plug's moulded body. Its top corners
    # are 45 degrees, so only a short bridge remains.
    px, pz0, pz1, c = PLUG_W / 2, USB_Z - PLUG_H / 2, USB_Z + PLUG_H / 2, PORT_CHAMFER
    port = wedge([(x, y, z)
                  for x, z in [(-px, pz0), (px, pz0), (px, pz1 - c), (px - c, pz1), (c - px, pz1), (-px, pz1 - c)]
                  for y in (-OUTER_H / 2 - 1, -CAVITY_H / 2 + 1)])

    cuts = [cavity, window, port]
    # Slots in the side walls, open to the rim, for the bracket's ears. Their floors are the
    # ledges the bracket is screwed down onto, which set the display's distance from the window.
    for sx in (-1, 1):
        xs = sorted((sx * (CAVITY_W / 2 - 0.1), sx * (CAVITY_W / 2 + LEDGE_DEPTH)))
        cuts.append(box(xs[0], xs[1], -LEDGE_HALF_L - EAR_CLEAR, LEDGE_HALF_L + EAR_CLEAR,
                        BRACKET_FRONT, BODY_DEPTH + 1))
        for sy in (-1, 1):
            cuts.append(zcyl(M2_PILOT_D, BRACKET_FRONT - M2_PILOT_HOLE, BRACKET_FRONT + 0.1,
                             sx * EAR_SCREW_X, sy * EAR_SCREW_Y))
    # Blind pilot holes for the lid screws, so no screw hole opens into the box.
    for x, y in screws():
        cuts.append(zcyl(M3_PILOT_D, BODY_DEPTH - M3_HOLE_DEPTH, BODY_DEPTH + 1, x, y))
    return body - combine(cuts)


def display_bracket():
    """The display screws to the front, and the XIAO snaps onto the back."""
    w = DISPLAY_W - 0.1
    plate = rounded(w, DISPLAY_H - 0.1, 3.0, BRACKET_FRONT, BRACKET_BACK)
    plate -= box(-CUTOUT_W / 2, CUTOUT_W / 2, CUTOUT_Y0, CUTOUT_Y1, BRACKET_FRONT - 1, BRACKET_BACK + 1)
    slot_x = XIAO_W / 2 + WIRE_SLOT
    plate -= box(-slot_x, slot_x, WIRE_SLOT_Y0, CUTOUT_Y1, BRACKET_FRONT - 1, BRACKET_BACK + 1)
    plate -= combine([zcyl(M2_CLEAR, BRACKET_FRONT - 1, BRACKET_BACK + 1, x, y) for x, y in standoffs()])

    # Ears on the long edges, screwed down onto the ledges in the shell's side walls.
    for sx in (-1, 1):
        xs = sorted((sx * (w / 2 - 0.1), sx * EAR_X))
        plate += box(xs[0], xs[1], -LEDGE_HALF_L, LEDGE_HALF_L, BRACKET_FRONT, BRACKET_BACK)
        for sy in (-1, 1):
            plate -= zcyl(M2_CLEAR, BRACKET_FRONT - 1, BRACKET_BACK + 1, sx * EAR_SCREW_X, sy * EAR_SCREW_Y)

    # Beside and beyond the XIAO's far end: posts that keep it central, and low stops that
    # catch its edge when a USB plug is pushed in. Both stay below the strap.
    for sx in (-1, 1):
        xs = sorted((sx * (XIAO_W / 2 + FIT), sx * (XIAO_W / 2 + FIT + 1.0)))
        plate += box(xs[0], xs[1], XIAO_Y1 - 1.6, XIAO_Y1 + 1.2, BRACKET_BACK - 0.1, STRAP_Z - 0.3)
        xs = sorted((sx * 3.0, sx * (XIAO_W / 2 + FIT + 0.01)))
        plate += box(xs[0], xs[1], XIAO_Y1 + FIT, XIAO_Y1 + 1.2, BRACKET_BACK - 0.1, STOP_TOP)
    # Bosses for the strap's screws, with holes right through for M2 inserts.
    for sx in (-1, 1):
        x, y = sx * STRAP_SCREW_X, STRAP_Y0 + STRAP_W / 2
        plate += zcyl(STRAP_BOSS_D, BRACKET_BACK - 0.1, STRAP_Z, x, y)
        plate -= zcyl(M2_INSERT_D, BRACKET_FRONT - 1, STRAP_Z + 1, x, y)

    # Pads to rest the XIAO on, and lips at the USB end for it to tuck under. The lips'
    # undersides are 45 degrees. They stop short of the pins the display's wires use.
    for sx in (-1, 1):
        y = XIAO_Y0 + 0.5
        post = XIAO_W / 2 + FIT
        lip_reach = post - (XIAO_W / 2 - XIAO_LIP)
        lip_top = XIAO_PCB_TOP + 0.15 + lip_reach + 0.1
        x0, x1 = sorted((sx * post, sx * (post + 0.95)))
        plate += box(x0, x1, y, y + USB_LIP_L, BRACKET_BACK - 0.1, lip_top)
        plate += wedge([(sx * x, yy, z)
                        for yy in (y, y + USB_LIP_L)
                        for x, z in [(post + 0.01, XIAO_PCB_TOP + 0.15),
                                     (post - lip_reach, XIAO_PCB_TOP + 0.15 + lip_reach),
                                     (post - lip_reach, lip_top), (post + 0.01, lip_top)]])
        pad0, pad1 = sorted((sx * (XIAO_W / 2 - 3.0), sx * (XIAO_W / 2 + FIT)))
        plate += box(pad0, pad1, y, y + 2.0, BRACKET_BACK - 0.1, XIAO_Z)
        plate += box(pad0, pad1, XIAO_Y1 - 2.5, XIAO_Y1 - 0.5, BRACKET_BACK - 0.1, XIAO_Z)
    # End stop at the USB end.
    for sx in (-1, 1):
        x0, x1 = sorted((sx * 6.0, sx * (XIAO_W / 2)))
        plate += box(x0, x1, XIAO_Y0 - 1.0, XIAO_Y0 - 0.1, BRACKET_BACK - 0.1, XIAO_PCB_TOP + 0.4)
    return plate


def xiao_strap():
    """A flat plate, printed either way up. Its tabs press the XIAO's far corners, clear of
    the pins the display's wires use, and of the parts on top of the board."""
    y1 = STRAP_Y0 + STRAP_W
    strap = box(-STRAP_SCREW_X, STRAP_SCREW_X, STRAP_Y0, y1, STRAP_Z, STRAP_Z + STRAP_T)
    for sx in (-1, 1):
        strap += zcyl(STRAP_BOSS_D, STRAP_Z, STRAP_Z + STRAP_T, sx * STRAP_SCREW_X, STRAP_Y0 + STRAP_W / 2)
    for sx in (-1, 1):
        xs = sorted((sx * STRAP_TAB_X0, sx * STRAP_TAB_X1))
        strap += box(xs[0], xs[1], STRAP_TAB_Y0, STRAP_Y0 + 0.1, STRAP_Z, STRAP_Z + STRAP_T)
        strap -= zcyl(M2_CLEAR, STRAP_Z - 1, STRAP_Z + STRAP_T + 1, sx * STRAP_SCREW_X, STRAP_Y0 + STRAP_W / 2)
    return strap


def catch_edge_y(x):
    """The lid's catch, which the tooth rests against with the box locked on."""
    return TOOTH_Y0 + PRELOAD * CATCH_SLOPE + (x - LATCH_ARM_X1) * CATCH_SLOPE


def box_lid():
    """A solid plate with a closed-ended dovetail channel on its back. Printed with its
    inner face on the bed, which gives a flat sealing face."""
    lid = rounded(OUTER_W, OUTER_H, CORNER_R, BODY_DEPTH, LID_BACK)
    # Raised corners, so the screw heads don't need to weaken the lid.
    for x, y in screws():
        lid += zcyl(2 * CORNER_R, LID_BACK - 0.1, BOSS_TOP, x, y)
    sleeve = box(-SLEEVE_HALF_W, SLEEVE_HALF_W, -OUTER_H / 2, OUTER_H / 2, LID_BACK - 0.1, SLEEVE_BACK)
    lid += sleeve

    y_low = -OUTER_H / 2 - 1
    z_end = SLEEVE_BACK + 0.1
    lid -= dovetail(channel_half_w, y_low, SLEEVE_STOP_Y, LID_BACK, z_end)
    # Flare the open end, so the channel finds the rail easily.
    lead = dovetail(channel_half_w, -OUTER_H / 2 + LEAD_IN_L, -OUTER_H / 2 + LEAD_IN_L + 0.01, LID_BACK, z_end)
    lead += dovetail(lambda z: channel_half_w(z) + LEAD_IN, y_low, y_low + 0.01, LID_BACK, z_end)
    lid -= Manifold.batch_hull([lead])
    # Slope the channel's floor at the open end, to ride over the rubber pad.
    lid -= wedge([(sx * CHANNEL_HALF_W, y, z)
                  for sx in (-1, 1)
                  for y, z in [(y_low, LID_BACK - RAMP_DEPTH), (y_low, LID_BACK + 0.01),
                               (-OUTER_H / 2 + LEAD_IN_L, LID_BACK + 0.01), (-OUTER_H / 2 + LEAD_IN_L, LID_BACK)]])
    # Notches for the latch's teeth. Each is cut only as far as the dovetail's slope, and
    # opens towards the mount, so it adds no overhang and doesn't go through the sleeve.
    for sx in (-1, 1):
        x_in, x_out = LATCH_ARM_X0, TOOTH_OUTER_X + 0.3
        z0 = LID_BACK + (CHANNEL_HALF_W - x_out)
        lid -= prism(mirrored(sx, [(x_in, catch_edge_y(x_in)), (x_out, catch_edge_y(x_out)),
                                   (x_out, TOOTH_Y1 + 0.4), (x_in, TOOTH_Y1 + 0.4)]), z0, z_end)
    for x, y in screws():
        lid -= zcyl(M3_CLEAR, BODY_DEPTH - 1, BOSS_TOP + 1, x, y)
        lid -= zcyl(M3_RECESS_D, SCREW_SEAT, BOSS_TOP + 1, x, y)
    return lid


def rail_half_w(z):
    return channel_half_w(z) - RAIL_CLEAR


def mount_upper():
    """The rail, and the upper half of the clamp. Printed with the clamp's flat faces on the bed."""
    plate = rounded(MOUNT_PLATE_W, CLAMP_H, 3.0, MOUNT_TOP, MOUNT_BACK)
    saddle = box(-CLAMP_W / 2, CLAMP_W / 2, -CLAMP_H / 2, CLAMP_H / 2, MOUNT_BACK - 0.1, PARTING_UPPER)
    # Printed upside down, the plate overhangs the saddle, so slope out to it at 45 degrees.
    rise = (MOUNT_PLATE_W - CLAMP_W) / 2
    slopes = Manifold.batch_hull([
        rounded(MOUNT_PLATE_W, CLAMP_H, 3.0, MOUNT_BACK - 0.01, MOUNT_BACK),
        box(-CLAMP_W / 2, CLAMP_W / 2, -CLAMP_H / 2, CLAMP_H / 2, MOUNT_BACK + rise, MOUNT_BACK + rise + 0.01)])
    part = plate + saddle + slopes - bar_saddle()

    # The rail is a full dovetail at each end, with the latch's room between them.
    z_join = MOUNT_TOP + 0.1
    part += dovetail(rail_half_w, RAIL_UPPER_Y, RAIL_STOP_Y, RAIL_TOP, z_join)
    part += dovetail(rail_half_w, RAIL_BOTTOM_Y, RAIL_LOWER_Y, RAIL_TOP, z_join)
    part += box(-ISLAND_HALF_W, ISLAND_HALF_W, LATCH_ROOT_Y1 + 0.2, RAIL_UPPER_Y + 0.1, RAIL_TOP, z_join)

    cuts = []
    # Pockets for the latch, open towards the lid.
    cuts.append(box(-LATCH_ROOT_HALF_W - QR_FIT, LATCH_ROOT_HALF_W + QR_FIT, LATCH_ROOT_Y0 - QR_FIT, LATCH_ROOT_Y1 + QR_FIT,
                    MOUNT_TOP - 0.1, POCKET_FLOOR))
    for sx in (-1, 1):
        xs = sorted((sx * ISLAND_HALF_W, sx * (LATCH_ARM_X1 + QR_FIT)))
        cuts.append(box(xs[0], xs[1], LATCH_ROOT_Y1 + 0.1, TOOTH_Y1 + QR_FIT, MOUNT_TOP - 0.1, POCKET_FLOOR))
        # A slot through to the plate's side, for the button's neck. It also takes the
        # load if the box is pulled upwards.
        xs = sorted((sx * LATCH_ARM_X1, sx * (MOUNT_PLATE_W / 2 + 1)))
        cuts.append(box(xs[0], xs[1], TOOTH_Y0 - QR_FIT, TOOTH_Y1 + QR_FIT, MOUNT_TOP - 0.1, POCKET_FLOOR))
    cuts.append(latch_pilot())
    # Shallow recess for the rubber pad, which takes up the dovetail's play.
    recess = 1.0 - PAD_PROUD
    cuts.append(box(-PAD_HALF_W, PAD_HALF_W, PAD_Y0, PAD_Y1, RAIL_TOP - 0.1, RAIL_TOP + recess))
    # Blind pilot holes the bolts tap into, so the clamp's sides stay solid. The
    # cone at the end prints without support.
    for sy in (-1, 1):
        y = sy * BOLT_Y
        hole_end = PARTING_UPPER - M4_HOLE_DEPTH
        cuts.append(zcyl(M4_PILOT_D, hole_end, PARTING_UPPER + 1, 0, y))
        cuts.append(Manifold.cylinder(M4_PILOT_D / 2, 0, M4_PILOT_D / 2, circular_segments=SEG).translate(
            (0, y, hole_end - M4_PILOT_D / 2)))
    return part - combine(cuts)


def latch_pilot():
    """The latch screw's blind pilot hole, below its pocket."""
    return zcyl(M3_PILOT_D, POCKET_FLOOR - 0.1, POCKET_FLOOR + LATCH_HOLE_DEPTH, 0, LATCH_SCREW_Y)


def latch_clip():
    """The two-button latch. Printed flat, so its arms bend along the layers. It's a separate
    part because arms built into the mount would hang in the air, and it's easy to replace."""
    z0, z1 = LATCH_Z0, LATCH_Z1
    h = LATCH_ROOT_HALF_W
    part = box(-h, h, LATCH_ROOT_Y0, LATCH_ROOT_Y1, z0, z1)
    for sx in (-1, 1):
        def xbox(x0, x1, y0, y1, a, b):
            xs = sorted((sx * x0, sx * x1))
            return box(xs[0], xs[1], y0, y1, a, b)

        part += xbox(LATCH_ARM_X0, LATCH_ARM_X1, LATCH_ROOT_Y1 - 0.1, TOOTH_Y1, z0, z1)
        part += xbox(LATCH_ARM_X0, BUTTON_X0 + 0.1, TOOTH_Y0, TOOTH_Y1, z0, z1)
        part += xbox(BUTTON_X0, BUTTON_X1, BUTTON_Y0, BUTTON_Y1, BUTTON_TOP, z1)
        # The tooth. Its catch slopes back at CATCH_ANGLE, and its ramp faces the box coming down.
        rise = (TOOTH_OUTER_X - LATCH_ARM_X1) * CATCH_SLOPE
        part += prism(mirrored(sx, [(LATCH_ARM_X0, TOOTH_Y0), (LATCH_ARM_X1, TOOTH_Y0),
                                    (TOOTH_OUTER_X, TOOTH_Y0 + rise), (TOOTH_OUTER_X, TOOTH_Y0 + rise + 1.0),
                                    (LATCH_ARM_X1 - 0.2, TOOTH_Y1), (LATCH_ARM_X0, TOOTH_Y1)]), TOOTH_TOP, z0 + 0.1)
    part -= zcyl(M3_CLEAR, z0 - 1, z1 + 1, 0, LATCH_SCREW_Y)
    part -= zcyl(M3_RECESS_D, z0 - 1, z0 + M3_HEAD_H + 0.05, 0, LATCH_SCREW_Y)
    return part


def clamp_lower():
    z0 = BAR_Z + PARTING_GAP / 2
    z1 = BAR_Z + BAR_R + CLAMP_WALL
    outer = box(-CLAMP_W / 2, CLAMP_W / 2, -CLAMP_H / 2, CLAMP_H / 2, z0, z1)
    # Round the outside, leaving the bolt seats flat.
    round_ = xcyl(2 * (BAR_R + CLAMP_WALL), -CLAMP_W, CLAMP_W, z=BAR_Z)
    wings = box(-CLAMP_W / 2, CLAMP_W / 2, -CLAMP_H / 2, CLAMP_H / 2, z0, BAR_Z + M4_SEAT + M4_HEAD_H)
    part = (outer ^ (round_ + wings)) - xcyl(BAR_R * 2, -CLAMP_W, CLAMP_W, z=BAR_Z)
    for sy in (-1, 1):
        y = sy * BOLT_Y
        part -= teardrop_z(M4_CLEAR, z0 - 1, z1 + 1, 0, y)
        part -= teardrop_z(M4_HEAD_D, BAR_Z + M4_SEAT, z1 + 1, 0, y)
    return part


def fit_test_front():
    """Front slice of the shell, to check the window lines up and the display fits."""
    return front_shell() ^ box(-OUTER_W, OUTER_W, -OUTER_H, OUTER_H, -1, GLASS_Z + 2.5)


def fit_test_bracket():
    """The back of the shell, to try the bracket's arms and the lid's screws."""
    z0 = BRACKET_FRONT - 1.0
    return front_shell() ^ box(-OUTER_W, OUTER_W, -OUTER_H, OUTER_H, z0, BODY_DEPTH + 1)


def fit_test_mount():
    """The mount's rail and latch pockets without the clamp, to try the quick release."""
    return mount_upper() ^ box(-OUTER_W, OUTER_W, -OUTER_H, OUTER_H, 0, MOUNT_BACK)


# ---------------------------------------------------------------- dummies for checks
def pcb_dummy():
    return box(-DISPLAY_W / 2, DISPLAY_W / 2, -DISPLAY_H / 2, DISPLAY_H / 2, GLASS_Z + GLASS_T, GLASS_Z + PCB_BACK_Z)


def glass_dummy():
    return box(-GLASS_W / 2, GLASS_W / 2, -GLASS_H / 2, GLASS_H / 2, GLASS_Z, GLASS_Z + GLASS_T)


def standoff_dummies():
    return combine([zcyl(STANDOFF_OD, GLASS_Z + PCB_BACK_Z, GLASS_Z + STANDOFF_TIP_Z, x, y) for x, y in standoffs()])


def connector_dummy():
    return box(-7.6, 7.6, -9.5, -3.0, GLASS_Z + PCB_BACK_Z, GLASS_Z + CONNECTOR_TIP_Z)


def components_dummy():
    return box(-9.0, 9.0, 0.0, 12.0, GLASS_Z + PCB_BACK_Z, GLASS_Z + PCB_BACK_Z + 1.8)


def display_dummy():
    return pcb_dummy() + glass_dummy() + standoff_dummies() + connector_dummy() + components_dummy()


def wire_room_dummy():
    z = GLASS_Z + CONNECTOR_TIP_Z
    return box(-7.6, 7.6, -9.5, -3.0, z, XIAO_Z)


def wire_path_dummies():
    """Where the display's wires come up beside the XIAO's long edges to its pins."""
    x0, x1 = XIAO_W / 2 + 0.1, XIAO_W / 2 + WIRE_SLOT - 0.1
    z0, z1 = GLASS_Z + CONNECTOR_TIP_Z, XIAO_PCB_TOP + 1.0
    return combine([box(*sorted((sx * x0, sx * x1)), WIRE_SLOT_Y0 + 0.3, CUTOUT_Y1 - 0.3, z0, z1)
                    for sx in (-1, 1)])


def pin_y(k):
    """Centre of the XIAO's k-th pin from its USB end."""
    return XIAO_Y0 + 2.88 + 2.54 * (k - 1)


def xiao_dummy():
    board = box(-XIAO_W / 2, XIAO_W / 2, XIAO_Y0, XIAO_Y1, XIAO_Z, XIAO_PCB_TOP)
    parts = box(-7.5, 7.5, XIAO_Y0, XIAO_Y1 - 1.0, XIAO_PCB_TOP, XIAO_MAX)
    usb = box(-USB_W / 2, USB_W / 2, USB_LIP_Y, XIAO_Y0 + 7.0, XIAO_PCB_TOP, XIAO_PCB_TOP + USB_H)
    return board + parts + usb


def lens_dummy():
    return box(-LENS_W / 2, LENS_W / 2, -LENS_H / 2, LENS_H / 2, BEZEL_T, BEZEL_T + LENS_T)


def plug_dummy():
    """A typical USB-C plug: the metal shell, then a moulded body outside the socket."""
    metal = box(-4.2, 4.2, USB_LIP_Y - 0.2, USB_LIP_Y + 6.5, USB_Z - 1.25, USB_Z + 1.25)
    body = box(-5.5, 5.5, -OUTER_H / 2 - 25, USB_LIP_Y - 0.3, USB_Z - 3.0, USB_Z + 3.0)
    return metal + body


def m4_bolt(sy):
    """The M4 bolt at full squeeze, with the clamp halves touching."""
    seat = BAR_Z + M4_SEAT - PARTING_GAP
    return zcyl(4.0, seat - M4_LENGTH, seat, 0, sy * BOLT_Y)


def m2_screw(x, y):
    """M2 screw, without the part inside the display's standoff."""
    return zcyl(M2_HEAD_D, BRACKET_BACK, BRACKET_BACK + M2_HEAD_H, x, y) + zcyl(2.0, BRACKET_FRONT, BRACKET_BACK, x, y)


def m2_clamp_screw(x, y, seat, length=M2_CLAMP_LENGTH):
    """An M2 screw whose head sits at z = seat, pointing towards the front."""
    head = zcyl(M2_HEAD_D, seat, seat + M2_HEAD_H, x, y)
    return head + zcyl(2.0, seat - length, seat, x, y)


def m3_lid_screw(x, y):
    head = zcyl(M3_HEAD_D, SCREW_SEAT, SCREW_SEAT + M3_HEAD_H, x, y)
    return head + zcyl(3.0, SCREW_SEAT - M3_LID_LENGTH, SCREW_SEAT, x, y)


def m3_latch_screw():
    seat = LATCH_Z0 + M3_HEAD_H
    head = zcyl(M3_HEAD_D, LATCH_Z0, seat, 0, LATCH_SCREW_Y)
    return head + zcyl(3.0, seat, seat + M3_LENGTH, 0, LATCH_SCREW_Y)


def bent_latch(latch, sx, inwards):
    """One side of the latch, with its tooth and button moved towards the centre. Only the
    far end moves by this much, so this overstates how far the arm's own length moves."""
    side = box(0, 40, -40, 40, 0, 60) if sx > 0 else box(-40, 0, -40, 40, 0, 60)
    far_end = box(-40, 40, TOOTH_Y0 - 3.0, 40, 0, 60)
    return (latch ^ side ^ far_end).translate((-sx * inwards, 0, 0))


def report_mesh(name, m):
    v = m.to_mesh()
    mesh = trimesh.Trimesh(v.vert_properties[:, :3], v.tri_verts, process=False)
    size = mesh.bounds[1] - mesh.bounds[0]
    grams = m.volume() / 1000.0 * 1.27
    print(f"  {name:17s} {size[0]:5.1f} x {size[1]:5.1f} x {size[2]:5.1f} mm  {m.volume() / 1000:6.1f} cm3  "
          f"about {grams:4.0f} g solid  watertight={mesh.is_watertight}  bodies={len(m.decompose())}")
    return mesh, len(m.decompose())


def widest_strip(region):
    """Width of the widest strip in a 2D region, found by shrinking it until it vanishes."""
    low, high = 0.0, 30.0
    for _ in range(12):
        mid = (low + high) / 2
        if region.offset(-mid, JoinType.Miter).area() > 1e-6:
            low = mid
        else:
            high = mid
    return 2 * low


def overhangs(part):
    """Slices a part in print orientation and finds what each layer can't rest on.

    Each layer can reach one layer height past the one below, which is a 45 degree slope.
    Returns (widest, z, length) for every strip above the limit."""
    b = part.bounding_box()
    found = []
    z = b[2] + LAYER * 1.5
    below = part.slice(b[2] + LAYER / 2)
    while z < b[5]:
        here = part.slice(z)
        loose = here - below.offset(LAYER + 0.02, JoinType.Round, 2, 32)
        for piece in loose.decompose():
            width = widest_strip(piece)
            if width > SELF_SUPPORTING:
                x0, y0, x1, y1 = piece.bounds()
                found.append((width, z, max(x1 - x0, y1 - y0)))
        below = here
        z += LAYER
    return found


def check_overhangs(results, name, part, allowed):
    """allowed is the widest strip this part may have, which the slicer must then bridge."""
    found = overhangs(part)
    widest = max((f[0] for f in found), default=0.0)
    ok = widest <= allowed + 1e-6
    results.append(ok)
    text = "no overhangs" if not found else f"widest {widest:.2f} mm, in {len(found)} regions, allowed {allowed:.1f}"
    print(f"  {'ok  ' if ok else 'FAIL'} {name}: {text}")
    for width, z, length in sorted(found, reverse=True)[:6]:
        print(f"         {width:.2f} mm wide, {length:.1f} mm long, {z:.1f} mm above the bed")


def check(results, label, a, b):
    overlap = (a ^ b).volume()
    ok = overlap < 0.001
    results.append(ok)
    print(f"  {'ok  ' if ok else 'FAIL'} {label}: overlap {overlap:.3f} mm3")


def min_gap(results, label, a, b, need):
    gap = a.min_gap(b, need + 5)
    ok = gap >= need - 1e-6
    results.append(ok)
    print(f"  {'ok  ' if ok else 'FAIL'} {label}: gap {gap:.2f} mm (need {need})")


def require(results, ok, text):
    results.append(ok)
    print(f"  {'ok  ' if ok else 'FAIL'} {text}")


def main():
    root = Path(__file__).parent
    stl_dir = root / "stl"
    img_dir = root / "preview"
    stl_dir.mkdir(exist_ok=True)
    img_dir.mkdir(exist_ok=True)

    shell, bracket, lid = front_shell(), display_bracket(), box_lid()
    mount, latch, lower = mount_upper(), latch_clip(), clamp_lower()
    display, xiao, lens = display_dummy(), xiao_dummy(), lens_dummy()
    bar = xcyl(BAR_D, -80, 80, z=BAR_Z)
    plug = plug_dummy()

    print("Parts, in print orientation:")
    # The lid prints with its inner face on the bed, and the mount with its clamp faces on the
    # bed. The latch prints on its back, and the lower clamp on its side, so its layers run
    # around the bar.
    on_side = (0, -90, 0)
    flipped = (180, 0, 0)
    upright = (0, 0, 0)
    r = []
    placed_parts = {}
    for name, part, angles in [("front_shell", shell, upright), ("display_bracket", bracket, upright),
                               ("box_lid", lid, upright), ("xiao_strap", xiao_strap(), upright),
                               ("mount_upper", mount, flipped),
                               ("mount_lower", lower, on_side), ("latch", latch, flipped),
                               ("fit_test_front", fit_test_front(), upright),
                               ("fit_test_bracket", fit_test_bracket(), upright),
                               ("fit_test_mount", fit_test_mount(), flipped)]:
        placed = part.rotate(angles)
        b = placed.bounding_box()
        placed = placed.translate((-(b[0] + b[3]) / 2, -(b[1] + b[4]) / 2, -b[2]))
        mesh, bodies = report_mesh(name, placed)
        mesh.export(stl_dir / f"{name}.stl")
        r.append(bodies == 1 and mesh.is_watertight)
        placed_parts[name] = placed

    print("Printing without support, sliced at 0.2 mm:")
    port_roof = (OUTER_H - CAVITY_H) / 2 + 1.0
    bar_flat = 2 * (BAR_R * math.sqrt(2) - (BAR_R + BAR_RELIEF))
    for name, allowed in [("front_shell", port_roof + 0.1),   # USB port's roof
                          ("display_bracket", SELF_SUPPORTING),
                          ("xiao_strap", SELF_SUPPORTING),
                          ("box_lid", SELF_SUPPORTING),
                          ("mount_upper", bar_flat + 0.1),    # flat above the bar
                          ("mount_lower", SELF_SUPPORTING),
                          ("latch", SELF_SUPPORTING),
                          ("fit_test_front", SELF_SUPPORTING),
                          ("fit_test_bracket", port_roof + 0.1),
                          ("fit_test_mount", SELF_SUPPORTING)]:
        check_overhangs(r, name, placed_parts[name], allowed)

    print("Fit checks:")
    check(r, "display in shell", display, shell)
    check(r, "lens in shell", lens, shell)
    check(r, "display vs bracket", display, bracket)
    check(r, "connector vs bracket", connector_dummy(), bracket)
    check(r, "wire room vs bracket", wire_room_dummy(), bracket)
    check(r, "wire room vs XIAO", wire_room_dummy(), xiao)
    check(r, "wires up to the XIAO's pins vs bracket", wire_path_dummies(), bracket)
    check(r, "wires up to the XIAO's pins vs shell", wire_path_dummies(), shell)
    check(r, "wires up to the XIAO's pins vs display", wire_path_dummies(), display)
    check(r, "XIAO vs bracket", xiao, bracket)
    check(r, "XIAO vs shell", xiao, shell)
    check(r, "XIAO vs lid", xiao, lid)
    check(r, "XIAO vs display", xiao, display)
    check(r, "bracket vs lid", bracket, lid)
    check(r, "lid vs shell", lid, shell)
    check(r, "bar vs mount", bar, mount)
    check(r, "bar vs lower clamp", bar, lower)
    check(r, "USB plug vs shell", plug, shell)
    check(r, "USB plug vs bracket", plug, bracket)
    check(r, "USB plug vs lid", plug, lid)
    check(r, "USB plug vs mount", plug, mount)
    for x, y in standoffs():
        screw = m2_screw(x, y)
        check(r, f"M2 at {x:+.2f},{y:+.1f} vs bracket", screw, bracket)
        check(r, f"M2 at {x:+.2f},{y:+.1f} vs XIAO", screw, xiao)
    for x, y in screws():
        screw = m3_lid_screw(x, y)
        check(r, f"M3 at {x:+.0f},{y:+.0f} vs lid", screw, lid)
        check(r, f"M3 at {x:+.0f},{y:+.0f} vs shell, beyond its thread",
              screw - zcyl(3.0, BODY_DEPTH - M3_HOLE_DEPTH, BODY_DEPTH + 0.01, x, y), shell)
        check(r, f"M3 at {x:+.0f},{y:+.0f} vs bracket", screw, bracket)
    for sy in (-1, 1):
        thread = zcyl(4.0, PARTING_UPPER - M4_HOLE_DEPTH, PARTING_UPPER + 0.01, 0, sy * BOLT_Y)
        check(r, f"M4 bolt {sy:+d} vs mount, fully squeezed, beyond its thread", m4_bolt(sy) - thread, mount)
    min_gap(r, "clamp halves apart", mount, lower, PARTING_GAP - 0.01)
    min_gap(r, "glass to lens", glass_dummy(), lens, GLASS_GAP - 0.01)
    min_gap(r, "XIAO to lid", xiao, lid, 0.5)
    require(r, CAVITY_W > LENS_W and CAVITY_H > LENS_H, "lens passes through the cavity, to fit from inside")
    side = (LENS_W - WINDOW_W) / 2
    top_edge = LENS_H / 2 - (ACTIVE_OFFSET_Y + WINDOW_H / 2)
    require(r, min(side, top_edge) >= 1.0, f"lens overlaps the window for sealing: {min(side, top_edge):.2f} mm at least")

    print("Enclosed box:")
    lid_holes = lid ^ box(-OUTER_W, OUTER_W, -OUTER_H, OUTER_H, BODY_DEPTH, BODY_DEPTH + 1.0)
    away_from_screws = lid_holes - combine([zcyl(M3_RECESS_D + 1, BODY_DEPTH - 1, BODY_DEPTH + 2, x, y) for x, y in screws()])
    expected = (rounded(OUTER_W, OUTER_H, CORNER_R, BODY_DEPTH, BODY_DEPTH + 1.0)
                - combine([zcyl(M3_RECESS_D + 1, BODY_DEPTH - 1, BODY_DEPTH + 2, x, y) for x, y in screws()]))
    require(r, abs(away_from_screws.volume() - expected.volume()) < 0.01, "lid is solid, apart from its four screw holes")
    thinnest = LID_T - RAMP_DEPTH
    require(r, thinnest >= 2.0, f"thinnest part of the lid: {thinnest:.1f} mm")
    lid_notch_floor = LID_BACK + (CHANNEL_HALF_W - (TOOTH_OUTER_X + 0.3))
    require(r, lid_notch_floor > LID_BACK, "latch notches stop short of the lid's plate")
    corner = CORNER_R - 3.0 / 2
    require(r, corner >= 1.6, f"wall outside each lid screw's thread: {corner:.2f} mm")
    inner = (math.hypot(SCREW_X - (CAVITY_W / 2 - CAVITY_R), SCREW_Y - (CAVITY_H / 2 - CAVITY_R))
             - CAVITY_R - 3.0 / 2)
    require(r, inner >= 1.2, f"wall between each lid screw's thread and the cavity: {inner:.2f} mm")
    tip = SCREW_SEAT - M3_LID_LENGTH
    require(r, BODY_DEPTH - tip >= M3_BITE, f"lid screw's thread in the shell: {BODY_DEPTH - tip:.1f} mm")
    require(r, tip >= BODY_DEPTH - M3_HOLE_DEPTH + 0.3, "lid screw's tip stops inside its blind hole")

    print("Screwed joints inside the box:")
    ledge_wall = (OUTER_W - CAVITY_W) / 2 - LEDGE_DEPTH
    require(r, ledge_wall >= MIN_WALL, f"wall behind the bracket's ledge slots: {ledge_wall:.2f} mm")
    ear_rest = EAR_X - CAVITY_W / 2 - EAR_TIP_CLEAR
    require(r, ear_rest >= 1.0, f"ears rest on their ledges by at least {ear_rest:.2f} mm, however the bracket shifts")
    require(r, DISPLAY_W / 2 + EAR_TIP_CLEAR < CAVITY_W / 2,
            "the ears, not the bracket's edges, locate it in the cavity")
    to_cavity = EAR_SCREW_X - 2.0 / 2 - CAVITY_W / 2
    require(r, to_cavity >= 1.0, f"plastic between each ear screw's thread and the cavity: {to_cavity:.2f} mm")
    outside = OUTER_W / 2 - (EAR_SCREW_X + 2.0 / 2)
    require(r, outside >= 2.0, f"plastic outside each ear screw's thread: {outside:.2f} mm")
    around = EAR_X - (EAR_SCREW_X + M2_CLEAR / 2)
    require(r, around >= 0.7, f"ear beyond each screw hole: {around:.2f} mm")
    require(r, EAR_SCREW_Y + M2_HEAD_D / 2 <= LEDGE_HALF_L, "ear screws' heads sit on their ears")
    for sx in (-1, 1):
        for sy in (-1, 1):
            screw = m2_clamp_screw(sx * EAR_SCREW_X, sy * EAR_SCREW_Y, BRACKET_BACK, M2_EAR_LENGTH)
            check(r, f"ear screw {sx:+d},{sy:+d} vs shell, beyond its thread",
                  screw - zcyl(2.0, BRACKET_FRONT - M2_PILOT_HOLE, BRACKET_FRONT + 0.1,
                               sx * EAR_SCREW_X, sy * EAR_SCREW_Y), shell)
            check(r, f"ear screw {sx:+d},{sy:+d} vs lid", screw, lid)
    ear_tip = BRACKET_BACK - M2_EAR_LENGTH
    require(r, BRACKET_FRONT - ear_tip >= M2_BITE and ear_tip >= BRACKET_FRONT - M2_PILOT_HOLE + 0.3,
            f"ear screw's thread in the ledge: {BRACKET_FRONT - ear_tip:.1f} mm, tip inside the hole")
    require(r, BRACKET_FRONT - M2_PILOT_HOLE >= 2.0,
            f"ledge's pilot holes stop {BRACKET_FRONT - M2_PILOT_HOLE:.1f} mm behind the front face")
    strap = xiao_strap()
    strap_seated = strap.translate((0, 0, STRAP_PRELOAD))
    check(r, "strap vs XIAO, just touching", strap_seated, xiao)
    require(r, (strap ^ xiao).volume() > 0.001, f"screws pull the strap {STRAP_PRELOAD} mm onto the XIAO's corners")
    check(r, "strap vs bracket", strap, bracket)
    check(r, "strap vs lid", strap, lid)
    for sx in (-1, 1):
        screw = m2_clamp_screw(sx * STRAP_SCREW_X, STRAP_Y0 + STRAP_W / 2, STRAP_Z + STRAP_T)
        check(r, f"strap screw {sx:+d} vs lid", screw, lid)
        check(r, f"strap screw {sx:+d} vs display", screw, display)
        check(r, f"strap screw {sx:+d} vs XIAO", screw, xiao)
    strap_thread = STRAP_Z - (STRAP_Z + STRAP_T - M2_CLAMP_LENGTH)
    require(r, strap_thread >= M2_INSERT_L - 0.1, f"strap screw's thread in its insert: {strap_thread:.1f} mm")
    require(r, STRAP_Z - BRACKET_FRONT >= M2_INSERT_L, f"strap boss holds the insert: {STRAP_Z - BRACKET_FRONT:.1f} mm")
    rim = (STRAP_BOSS_D - M2_CLEAR) / 2
    require(r, rim >= 1.8, f"strap around each screw hole: {rim:.2f} mm")
    rim = (STRAP_BOSS_D - M2_INSERT_D) / 2
    require(r, rim >= 1.5, f"boss wall around each strap insert: {rim:.2f} mm")
    require(r, STRAP_SCREW_X + STRAP_BOSS_D / 2 <= (DISPLAY_W - 0.1) / 2, "strap's bosses stay on the bracket")
    require(r, STRAP_TAB_Y0 > pin_y(6) + 1.2, f"strap's tabs clear of the wired pins: {STRAP_TAB_Y0 - pin_y(6) - 1.2:.2f} mm")

    print("Assembly paths:")
    # The XIAO, with the wires soldered on, goes in with its far end raised a little: its USB
    # end slides under the lips to the end stop, then its far end is lowered past the stops.
    rigid_bracket = bracket
    slide = 0.5 + USB_LIP_L + 0.3

    def tilted(angle, dy):
        return (xiao.translate((0, -XIAO_Y0, -XIAO_Z)).rotate((angle, 0, 0))
                .translate((0, XIAO_Y0 + dy, XIAO_Z)))
    path = [(TILT, slide * i / 8) for i in range(8, -1, -1)] + [(TILT * i / 6, 0.0) for i in range(5, -1, -1)]
    clashes = [(a, round(dy, 1)) for a, dy in path if (rigid_bracket ^ tilted(a, dy)).volume() > 0.001]
    require(r, not clashes, f"XIAO goes in at {TILT} degrees, {slide:.1f} mm under the lips, then down: "
                            f"{clashes or 'clear'}")
    lifted = tilted(TILT, slide).bounding_box()[5] - XIAO_MAX
    require(r, lifted > 0.5, f"XIAO's far end raised while it goes in: {lifted:.1f} mm")
    # Pins 2 to 6 from the USB end carry the display's wires and solder, which stick out
    # past the board's edges. Allow for 1.2 mm either side of each pin's centre.
    pin = [XIAO_Y0 + 2.88 + 2.54 * k for k in range(7)]
    require(r, pin[1] - 1.2 > XIAO_Y0 + 0.5 + USB_LIP_L,
            f"wired pins clear of the USB-end lips: {pin[1] - 1.2 - (XIAO_Y0 + 0.5 + USB_LIP_L):.2f} mm")
    require(r, pin[5] + slide + 1.2 <= XIAO_Y1 - 1.6,
            f"wired pins clear of the far-end posts while going in: {XIAO_Y1 - 1.6 - (pin[5] + slide + 1.2):.2f} mm")
    # The bracket, with the display and XIAO on it, drops straight into the shell, in any
    # position its ears allow.
    clashes = []
    for dx in (-EAR_TIP_CLEAR + 0.01, 0.0, EAR_TIP_CLEAR - 0.01):
        for dy in (-EAR_CLEAR + 0.01, 0.0, EAR_CLEAR - 0.01):
            stack = (bracket + display + xiao).translate((dx, dy, 0))
            clashes += [(dx, dy, dz) for dz in range(0, 20, 1)
                        if (stack.translate((0, 0, dz)) ^ shell).volume() > 0.001]
    require(r, not clashes, f"bracket, display and XIAO drop into the shell, shifted as far as "
                            f"the ears allow: {clashes or 'clear'}")
    # The latch drops into its pocket from the rail side, with the box off.
    clashes = [dz for dz in range(0, 15, 1) if (latch.translate((0, 0, -dz)) ^ mount).volume() > 0.001]
    require(r, not clashes, f"latch drops into the mount's pocket: {clashes or 'clear'}")

    print("Quick release:")
    check(r, "mount vs locked lid", mount, lid)
    check(r, "latch vs mount, at rest", latch, mount)
    thread = zcyl(3.0, POCKET_FLOOR - 0.1, POCKET_FLOOR + LATCH_HOLE_DEPTH, 0, LATCH_SCREW_Y)
    check(r, "latch screw vs mount, beyond its thread", m3_latch_screw() - thread, mount)
    check(r, "latch screw vs bar", m3_latch_screw(), bar)
    if SIDE_H > 0:
        # Whatever sits beside the clamp on the bar, such as a switch block, on either side.
        top = BAR_Z - BAR_D / 2 - SIDE_H
        side = combine([box(*sorted((sx * (CLAMP_W / 2 + SIDE_GAP), sx * 70)), -45, 45, top, BAR_Z)
                        for sx in (-1, 1)])
        # The neck sits beside it, so only the plate and what it carries must clear its top.
        above = mount ^ box(-80, 80, -80, 80, -1, MOUNT_BACK + PLATE_SLOPE)
        for name, part in [("plate", above), ("latch", latch), ("lid", lid), ("shell", shell)]:
            min_gap(r, f"{name} above the switch block ({SIDE_H} mm above the bar)", part, side, SIDE_CLEAR)
    # The rail must also clear the buttons, unpressed, until the teeth reach the sleeve.
    clashes = [dy for dy in range(0, 56, 2)
               if ((latch ^ box(-40, 40, -40, 40, 0, 60) - box(-TOOTH_OUTER_X - 0.1, TOOTH_OUTER_X + 0.1, -40, 40, 0, 60))
                   ^ lid.translate((0, dy, 0))).volume() > 0.001]
    require(r, not clashes, f"unpressed buttons clear the lid along the whole slide: {clashes or 'yes'}")
    for sx in (-1, 1):
        name = "right" if sx > 0 else "left"
        # At rest, the tooth overlaps the catch. That overlap is what bends the arm and pulls
        # the box home. The arm's allowed preload must clear it.
        check(r, f"{name} tooth vs lid, preloaded", bent_latch(latch, sx, PRELOAD + 0.05), lid)
        rest = (bent_latch(latch, sx, 0.0) ^ lid).volume()
        require(r, rest > 0.01, f"{name} tooth is held bent by the lid's catch: {rest:.2f} mm3 at rest")
        # Pressing the button, the tooth must clear the sleeve all the way off the mount.
        travel = TOOTH_OUTER_X - channel_half_w(SLEEVE_BACK) + 0.1
        squeezed = bent_latch(latch, sx, travel)
        check(r, f"{name} side, pressed, vs mount", squeezed, mount)
        clashes = [dy for dy in range(0, 56, 2) if (squeezed ^ lid.translate((0, dy, 0))).volume() > 0.001]
        require(r, not clashes, f"{name} tooth clears the lid when pressed, along the whole slide: {clashes or 'yes'}")
    clashes = [dy for dy in range(0, 56, 2) if (mount ^ lid.translate((0, dy, 0))).volume() > 0.001]
    require(r, not clashes, f"rail slides the whole way out of the channel: {clashes or 'yes'}")
    moved = lid.translate((0, -0.5, 0))
    require(r, (mount ^ moved).volume() > 0.001, "rail stops the box going further down")
    locked_up = [(latch ^ lid.translate((0, dy, 0))).volume() for dy in (1.0, 2.0)]
    require(r, all(v > 0.1 for v in locked_up), "teeth stop the box sliding up unless both are pressed")
    one_pressed = (bent_latch(latch, 1, travel) + bent_latch(latch, -1, 0.0)) ^ lid.translate((0, 2.0, 0))
    require(r, one_pressed.volume() > 0.1, "pressing one button alone doesn't release the box")
    room = LATCH_ARM_X0 - ISLAND_HALF_W
    require(r, room >= travel, f"latch arm's room to bend: {room:.1f} mm for {travel:.1f} mm of travel")
    release_x = BUTTON_X0 - travel - SLEEVE_HALF_W
    require(r, release_x > 0.0, f"button clears the sleeve when fully pressed: {release_x:.2f} mm")
    require(r, BUTTON_X1 <= OUTER_W / 2, f"buttons sit inside the box's edges: {OUTER_W / 2 - BUTTON_X1:.2f} mm in")
    compress = PAD_PROUD - RAIL_GAP - RAIL_CLEAR
    require(r, compress >= 0.2, f"rubber pad squeezed by {compress:.2f} mm with the box locked on")

    print("Snap fits, as bending strain (PETG and ASA tolerate roughly 3%):")
    arm_l = TOOTH_Y0 - LATCH_ROOT_Y1
    # Each only bends by how far it reaches past the wall; its clearance doesn't add to that.
    for name, value in [("latch arm, button pressed", strain(LATCH_ARM_X1 - LATCH_ARM_X0, travel, arm_l)),
                        ]:
        require(r, value <= 3.2, f"{name}: {value:.1f}%")
    stiffness = 3 * 2000 * LATCH_T * (LATCH_ARM_X1 - LATCH_ARM_X0) ** 3 / 12 / arm_l ** 3
    print(f"  estimated force to press each button, in PETG: {stiffness * travel:.0f} N")

    print("Screws and strength:")
    in_standoff = M2_LENGTH - BRACKET_T
    require(r, 1.5 <= in_standoff <= M2_MAX_THREAD,
            f"M2 x {M2_LENGTH:.0f}: {in_standoff:.1f} mm in the standoff, of {M2_MAX_THREAD} mm")
    bite = M4_LENGTH - (M4_SEAT + PARTING_GAP / 2)
    require(r, bite >= M4_BITE, f"M4 x {M4_LENGTH:.0f}: {bite:.2f} mm of thread in the mount with the clamp open, "
                                f"of {M4_BITE} mm needed")
    bolt_tip = M4_LENGTH - (M4_SEAT - PARTING_GAP) - PARTING_GAP / 2
    require(r, bolt_tip <= M4_HOLE_DEPTH, f"M4 bolt tip when fully squeezed: {bolt_tip:.2f} mm into a {M4_HOLE_DEPTH} mm hole")
    wing = M4_SEAT - PARTING_GAP / 2
    require(r, wing >= 5.0, f"plastic under each M4 head: {wing:.2f} mm")
    side = CLAMP_H / 2 - BOLT_Y - M4_PILOT_D / 2
    require(r, side >= 3.0, f"plastic beside each M4 pilot hole: {side:.2f} mm")
    side = CLAMP_W / 2 - M4_PILOT_D / 2
    require(r, side >= 3.0, f"plastic along the bar beside each M4 pilot hole: {side:.2f} mm")
    below = PARTING_UPPER - M4_HOLE_DEPTH - M4_PILOT_D / 2 - MOUNT_TOP
    require(r, below >= 2.0, f"M4 pilot hole stops {below:.2f} mm behind the plate's face")
    latch_bite = M3_LENGTH - (LATCH_T - M3_HEAD_H) - (POCKET_FLOOR - LATCH_Z1)
    require(r, latch_bite >= M3_BITE, f"M3 x {M3_LENGTH:.0f} latch screw: {latch_bite:.2f} mm of thread in the mount, "
                                      f"of {M3_BITE} mm needed")
    require(r, LATCH_HOLE_DEPTH >= latch_bite + 1.0,
            f"latch screw's pilot hole, {LATCH_HOLE_DEPTH} mm, is deeper than the screw reaches")
    check(r, "latch screw's pilot hole vs bar", latch_pilot(), bar_saddle())
    floor = MOUNT_BACK - POCKET_FLOOR
    require(r, floor >= 1.2, f"plastic between the latch's pocket and the bar: {floor:.2f} mm")
    between = (BOLT_Y - M4_PILOT_D / 2) - (abs(LATCH_SCREW_Y) + M3_PILOT_D / 2)
    require(r, between >= 1.5, f"plastic between the latch's pilot hole and the M4 pilot hole: {between:.2f} mm")

    print("Overall:")
    b = (shell + lid + mount + lower).bounding_box()
    print(f"  {b[3] - b[0]:.1f} x {b[4] - b[1]:.1f} x {b[5] - b[2]:.1f} mm, front face to back of clamp")
    print(f"  box alone, off the mount: {OUTER_W} x {OUTER_H} x {SLEEVE_BACK:.1f} mm")

    bar_short = xcyl(BAR_D, -45, 45, z=BAR_Z)
    mount_parts = [("clamp_upper", mount), ("latch_clip", latch), ("clamp_lower", lower), ("bar", bar_short)]
    box_parts = [("front_shell", shell), ("back_lid", lid)]

    def view(parts, angles):
        return [(n, m.rotate(angles)) for n, m in parts]

    def moved_up(parts, dy):
        return [(n, m.translate((0, dy, 0))) for n, m in parts]

    render(img_dir / "assembled_front.png", view(box_parts + mount_parts, (180, 0, 0)),
           "Locked on: display side", elev=-28, azim=-30)
    render(img_dir / "assembled_back.png", box_parts + mount_parts, "Locked on: clamp side", elev=-28, azim=-30)
    render(img_dir / "detached.png", moved_up(box_parts, 60) + mount_parts,
           "Buttons pressed: the box lifts off the mount", elev=-35, azim=-35)
    render(img_dir / "lid_back.png", [("back_lid", lid)], "Lid, back: closed-ended channel and screw recesses",
           elev=-40, azim=-25)
    render(img_dir / "mount.png", view([("clamp_upper", mount), ("latch_clip", latch)], (180, 0, 0)),
           "Mount: rail, pad recess and latch", elev=-40, azim=-25)
    render(img_dir / "latch.png", view([("latch_clip", latch)], (180, 0, 0)), "Latch, as printed", elev=-45, azim=-25)
    render(img_dir / "inside_shell.png", [("front_shell", shell)], "Shell from behind: USB port, bracket ledges, screw holes",
           elev=-28, azim=-20)
    render(img_dir / "bracket_back.png", [("bracket", bracket)],
           "Display bracket, back: screwed ears, XIAO lips, stops and strap bosses", elev=-30, azim=-25)
    render(img_dir / "bracket_with_parts.png", [("bracket", bracket), ("display", display), ("xiao", xiao),
                                                ("latch_clip", xiao_strap())],
           "Display, XIAO and its strap (orange) on the bracket", elev=-30, azim=-25)
    render(img_dir / "clamp.png", [("clamp_upper", mount), ("clamp_lower", lower.translate((0, 0, 22)))],
           "Clamp, opened", elev=-25, azim=-40)
    section_parts = [("front_shell", shell), ("bracket", bracket), ("back_lid", lid), ("clamp_upper", mount),
                     ("latch_clip", latch), ("clamp_lower", lower), ("bar", xcyl(BAR_D, -45, 45, z=BAR_Z)),
                     ("display", display), ("xiao", xiao), ("lens", lens)]
    sections(img_dir / "sections.png", section_parts, STANDOFF_X, BRACKET_FRONT + 1.0)
    latch_sections(img_dir / "latch_sections.png",
                   [("back_lid", lid), ("clamp_upper", mount), ("latch_clip", latch)],
                   TOOTH_TOP + 0.7, LATCH_Z0 + 1.5)

    failed = r.count(False)
    print(f"\n{len(r) - failed}/{len(r)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
