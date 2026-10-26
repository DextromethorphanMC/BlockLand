"""The connected-texture tile layout (rendering spec section 25), the Python twin of client/ctm/CtmTiles.java.

Directions in a face's plane: 0 up, 1 right, 2 down, 3 left, 4 up-right, 5 down-right, 6 down-left, 7 up-left; a mask
has bit i set when the neighbour that way connects (in the texture's frame: image right = +u, image up = -v).
ctm (47 tiles): a corner bit counts only when both of its edges are set; tile = rank of the reduced mask among the 47
reduced masks in ascending order (tile 0 alone, tile 46 surrounded)."""

UP, RIGHT, DOWN, LEFT, UP_RIGHT, DOWN_RIGHT, DOWN_LEFT, UP_LEFT = range(8)
DIRS = [(0, 1), (1, 0), (0, -1), (-1, 0), (1, 1), (1, -1), (-1, -1), (-1, 1)]
# corner bit -> its two edge bits
CORNERS = {UP_RIGHT: (UP, RIGHT), DOWN_RIGHT: (DOWN, RIGHT), DOWN_LEFT: (DOWN, LEFT), UP_LEFT: (UP, LEFT)}


def has(mask, bit):
    return (mask >> bit) & 1 == 1


def reduce(mask):
    m = mask & 0xF
    for corner, (a, b) in CORNERS.items():
        if has(mask, corner) and has(mask, a) and has(mask, b):
            m |= 1 << corner
    return m


CTM_MASKS = [m for m in range(256) if reduce(m) == m]
_RANK = {m: i for i, m in enumerate(CTM_MASKS)}


def ctm(mask):
    """Raw 8-bit mask -> ctm tile index (0..46)."""
    return _RANK[reduce(mask)]


def horizontal(mask):
    return (mask >> LEFT & 1) | (mask >> RIGHT & 1) << 1


def vertical(mask):
    return (mask >> UP & 1) | (mask >> DOWN & 1) << 1
