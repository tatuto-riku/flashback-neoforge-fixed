package dev.flashbackfix.compat;

/** Original and collision-adjusted coordinates for a transient screen widget. */
public record ButtonPlacement(int originX, int originY, int movedX, int movedY) {}
