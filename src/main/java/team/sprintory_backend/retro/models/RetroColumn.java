package team.sprintory_backend.retro.models;

/**
 * The three columns of a retro board (R31). Stored in {@code retro_items.column_name}; a new
 * value needs a migration changing {@code ck_retro_items_column}.
 */
public enum RetroColumn {
    WENT_WELL,
    TO_IMPROVE,
    IDEAS
}
