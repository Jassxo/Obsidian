package dev.obsidian.core.tracker;

/**
 * Typed events on the per-player action timeline. Raw packets are translated
 * into exactly one of these by the ingestion layer; checks only ever see these.
 */
public enum ActionType {
    CRYSTAL_PLACE,
    CRYSTAL_ATTACK,
    ANCHOR_INTERACT,
    GLOWSTONE_PLACE,
    HOTBAR_SWITCH,
    SWING,
    USE_ITEM,
    BLOCK_PLACE
}
