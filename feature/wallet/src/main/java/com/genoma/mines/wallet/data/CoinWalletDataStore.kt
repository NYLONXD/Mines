package com.genoma.mines.wallet.data
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.walletDataStore by preferencesDataStore(name = "wallet")
data class RedeemResult(
    val success: Boolean,
    val message: String
)

data class WalletSnapshot(
    val coins: Int,
    val diamonds: Int
)

/** Current balances + redeem availability, refreshed when the Store screen opens. */
data class RedeemStatus(
    val coins: Int,
    val diamonds: Int,
    val redemptionsUsedToday: Int,
    val maxRedemptionsPerWindow: Int,
    val nextUnlockMillis: Long?
) {
    val canRedeem: Boolean
        get() = redemptionsUsedToday < maxRedemptionsPerWindow &&
                coins >= CoinWalletDataStore.COST_PER_DIAMOND
}

class CoinWalletDataStore(private val context: Context) : WalletRepository {

    companion object {
        const val COST_PER_DIAMOND = 500
        const val MAX_REDEMPTIONS_PER_WINDOW = 2
        private const val WINDOW_MILLIS = 24L * 60 * 60 * 1000

        private val COINS = intPreferencesKey("coins")
        private val DIAMONDS = intPreferencesKey("diamonds")
        private val REDEEM_TIMESTAMPS = stringPreferencesKey("redeem_timestamps")
    }

    override val coins: Flow<Int> = context.walletDataStore.data.map { it[COINS] ?: 0 }
    override val diamonds: Flow<Int> = context.walletDataStore.data.map { it[DIAMONDS] ?: 0 }

    override suspend fun addCoins(amount: Int) {
        if (amount <= 0) return

        context.walletDataStore.edit { prefs ->
            prefs[COINS] = (prefs[COINS] ?: 0) + amount
        }
    }

    override suspend fun addDiamonds(amount: Int) {
        if (amount <= 0) return

        context.walletDataStore.edit { prefs ->
            prefs[DIAMONDS] = (prefs[DIAMONDS] ?: 0) + amount
        }
    }

    override suspend fun getRedeemStatus(): RedeemStatus {
        val prefs = context.walletDataStore.data.first()
        val activeTimestamps = parseTimestamps(prefs[REDEEM_TIMESTAMPS]).filterActive()

        return RedeemStatus(
            coins = prefs[COINS] ?: 0,
            diamonds = prefs[DIAMONDS] ?: 0,
            redemptionsUsedToday = activeTimestamps.size,
            maxRedemptionsPerWindow = MAX_REDEMPTIONS_PER_WINDOW,
            nextUnlockMillis = if (activeTimestamps.size >= MAX_REDEMPTIONS_PER_WINDOW) {
                activeTimestamps.min() + WINDOW_MILLIS
            } else {
                null
            }
        )
    }

    /** Spends [COST_PER_DIAMOND] coins for one diamond, if allowed. */
    override suspend fun redeemDiamond(): RedeemResult {
        var result = RedeemResult(success = false, message = "")

        context.walletDataStore.edit { prefs ->
            val currentCoins = prefs[COINS] ?: 0
            val activeTimestamps = parseTimestamps(prefs[REDEEM_TIMESTAMPS]).filterActive()

            // Expired stamps are pruned whatever the outcome, so a failed
            // redeem can't leave a stale one holding the daily slot.
            prefs[REDEEM_TIMESTAMPS] = activeTimestamps.joinToString(",")

            result = when {
                activeTimestamps.size >= MAX_REDEMPTIONS_PER_WINDOW -> RedeemResult(
                    success = false,
                    message = "You've used both redemptions for today. Check back later."
                )

                currentCoins < COST_PER_DIAMOND -> RedeemResult(
                    success = false,
                    message = "You need $COST_PER_DIAMOND coins to redeem a diamond."
                )

                else -> {
                    prefs[COINS] = currentCoins - COST_PER_DIAMOND
                    prefs[DIAMONDS] = (prefs[DIAMONDS] ?: 0) + 1
                    prefs[REDEEM_TIMESTAMPS] =
                        (activeTimestamps + System.currentTimeMillis()).joinToString(",")

                    RedeemResult(success = true, message = "Redeemed 1 diamond!")
                }
            }
        }

        return result
    }

    override suspend fun spendDiamonds(amount: Int): RedeemResult {
        if (amount <= 0) return RedeemResult(true, "")

        var result = RedeemResult(false, "")

        context.walletDataStore.edit { prefs ->
            val current = prefs[DIAMONDS] ?: 0

            result = if (current < amount) {
                RedeemResult(
                    false,
                    "You need $amount diamonds to buy this item."
                )
            } else {
                prefs[DIAMONDS] = current - amount

                RedeemResult(
                    true,
                    "Purchase successful!"
                )
            }
        }

        return result
    }

    /**
     * Clears the local balance entirely. Called after a guest's
     * coins/diamonds have been folded into a Firestore account on first
     * sign-in, so this device's DataStore doesn't double-count them if
     * anything ever falls back to reading it.
     */
    suspend fun clear() {
        context.walletDataStore.edit { prefs ->
            prefs.remove(COINS)
            prefs.remove(DIAMONDS)
            prefs.remove(REDEEM_TIMESTAMPS)
        }
    }

    private fun parseTimestamps(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun List<Long>.filterActive(): List<Long> {
        val cutoff = System.currentTimeMillis() - WINDOW_MILLIS
        return filter { it > cutoff }
    }
}