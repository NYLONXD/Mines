package com.genoma.mines.store.data.catalog
import androidx.compose.ui.graphics.Color
import com.genoma.mines.store.domain.BoardThemeItem
import com.genoma.mines.store.domain.BoardThemeStyle
import com.genoma.mines.core.theme.MinesThemeVariant

val deepOceanTheme = BoardThemeItem(
    id = "board_deep_ocean",
    name = "Deep Ocean",
    description = "A cool blue palette for the board and cells.",
    price = 10,
    previewColorHex = listOf(
        "#1B4B66",
        "#7FC7E8"
    ),
    style = BoardThemeStyle(
        boardColor = Color(0xFF12384D),
        hiddenCellColor = Color(0xFF1B4B66),
        revealedCellColor = Color(0xFFE4F5FC),
        borderColor = Color(0xFF7FC7E8),
        flagColor = Color(0xFFFF8066),
        mineColor = Color(0xFF17313F),
        themeVariant = MinesThemeVariant.DEEP_OCEAN
    )
)
