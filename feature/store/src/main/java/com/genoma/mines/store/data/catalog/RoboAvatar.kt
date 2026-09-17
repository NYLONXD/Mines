package com.genoma.mines.store.data.catalog
import com.genoma.mines.store.domain.AvatarStoreItem

/**
 * Same situation as FrostCellSkin — no dedicated premium-avatar art
 * exists yet, so previewDrawableRes stays null for now.
 */
val roboAvatar = AvatarStoreItem(
    id = "avatar_robo",
    name = "Robo",
    description = "A robot avatar for your profile.",
    price = 15
)