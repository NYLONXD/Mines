package com.genoma.mines.life.domain

/** Tunable numbers for the hearts (lives) system, kept in one place. */
object LifeRules {
    const val MAX_HEARTS = 5

    /** Gems (diamonds) charged per heart when buying hearts back. */
    const val DIAMONDS_PER_HEART = 5

    /** One heart regenerates for free after this long, up to [MAX_HEARTS]. */
    const val REGEN_INTERVAL_MILLIS = 60L * 60 * 1000

    /**
     * Gems to top [currentHearts] back up to [MAX_HEARTS]: only the hearts that
     * are actually missing are charged, so losing a single heart costs
     * [DIAMONDS_PER_HEART] rather than a flat full-refill price.
     */
    fun refillCost(currentHearts: Int): Int =
        (MAX_HEARTS - currentHearts).coerceIn(0, MAX_HEARTS) * DIAMONDS_PER_HEART
}
