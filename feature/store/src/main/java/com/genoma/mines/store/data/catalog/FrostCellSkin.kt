package com.genoma.mines.store.data.catalog
import com.genoma.mines.store.domain.CellSkinItem

/**
 * No dedicated cell-skin art exists yet, so this ships with
 * previewDrawableRes left null — the store UI should show a generic
 * placeholder for it until real art is added, at which point just pass
 * the drawable resource here.
 */
val frostCellSkin = CellSkinItem(
    id = "cellskin_frost",
    name = "Frost Cells",
    description = "A frosted-glass look for revealed numbers and mines.",
    price = 20
)