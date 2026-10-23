package com.blockland.client.scope;

import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Where the held weapon's sight is this frame, in view space as the viewmodel draws it (its own narrower projection,
 * {@code ViewmodelRenderer.FOV}). The weapon module implements it from its posed model; the optics only read it.
 */
public interface SightPose {
    /** A node of the fitted optic's model, or null when the optic has no model or is not on screen. */
    @Nullable
    Matrix4f opticNode(String name);

    /** The weapon's own sight line (its {@code aim_iron} eye point, −Z along the line), or null when it has none. */
    @Nullable
    Matrix4f ironSight();
}
