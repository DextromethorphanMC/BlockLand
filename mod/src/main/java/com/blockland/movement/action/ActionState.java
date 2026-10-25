package com.blockland.movement.action;

/**
 * A player's current special movement as one side knows it.
 *
 * @param yaw the direction the body lies or moves along (Minecraft yaw): the slide's, the dive's, the prone body's
 */
public record ActionState(MovementAction action, float yaw) {
}
