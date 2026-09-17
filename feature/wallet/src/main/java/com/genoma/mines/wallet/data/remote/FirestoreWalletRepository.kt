package com.genoma.mines.wallet.data.remote
import com.genoma.mines.wallet.data.CoinWalletDataStore
import com.genoma.mines.wallet.data.RedeemResult
import com.genoma.mines.wallet.data.RedeemStatus
import com.genoma.mines.wallet.data.WalletSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

private const val WINDOW_MILLIS = 24L * 60 * 60 * 1000


class FirestoreWalletRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    private fun userDoc(uid: String) = firestore.collection("users").document(uid)

    /** Live coin/diamond balance, updating in real time as it changes server-side. */
    fun observeWallet(uid: String): Flow<WalletSnapshot> = callbackFlow {
        val registration = userDoc(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }

            trySend(
                WalletSnapshot(
                    coins = snapshot?.getLong("coins")?.toInt() ?: 0,
                    diamonds = snapshot?.getLong("diamonds")?.toInt() ?: 0
                )
            )
        }

        awaitClose { registration.remove() }
    }

    suspend fun addCoins(uid: String, amount: Int) {
        if (amount <= 0) return

        userDoc(uid).set(
            mapOf("coins" to FieldValue.increment(amount.toLong())),
            SetOptions.merge()
        ).await()
    }

    suspend fun addDiamonds(uid: String, amount: Int) {
        if (amount <= 0) return

        userDoc(uid).set(
            mapOf("diamonds" to FieldValue.increment(amount.toLong())),
            SetOptions.merge()
        ).await()
    }

    suspend fun getRedeemStatus(uid: String): RedeemStatus {
        val snapshot = userDoc(uid).get(Source.SERVER).await()

        val coins = snapshot.getLong("coins") ?: 0L
        val activeTimestamps = readTimestamps(snapshot.get("redeemTimestamps")).filterActive()

        return RedeemStatus(
            coins = coins.toInt(),
            diamonds = (snapshot.getLong("diamonds") ?: 0L).toInt(),
            redemptionsUsedToday = activeTimestamps.size,
            maxRedemptionsPerWindow = CoinWalletDataStore.MAX_REDEMPTIONS_PER_WINDOW,
            nextUnlockMillis = if (activeTimestamps.size >= CoinWalletDataStore.MAX_REDEMPTIONS_PER_WINDOW) {
                activeTimestamps.min() + WINDOW_MILLIS
            } else {
                null
            }
        )
    }

    /** Spends [CoinWalletDataStore.COST_PER_DIAMOND] coins for one diamond, if allowed. */
    suspend fun redeemDiamond(uid: String): RedeemResult {
        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(userDoc(uid))

            val currentCoins = snapshot.getLong("coins") ?: 0L
            val activeTimestamps = readTimestamps(snapshot.get("redeemTimestamps")).filterActive()

            when {
                activeTimestamps.size >= CoinWalletDataStore.MAX_REDEMPTIONS_PER_WINDOW -> RedeemResult(
                    success = false,
                    message = "You've used both redemptions for today. Check back later."
                )

                currentCoins < CoinWalletDataStore.COST_PER_DIAMOND -> RedeemResult(
                    success = false,
                    message = "You need ${CoinWalletDataStore.COST_PER_DIAMOND} coins to redeem a diamond."
                )

                else -> {
                    transaction.set(
                        userDoc(uid),
                        mapOf(
                            "coins" to currentCoins - CoinWalletDataStore.COST_PER_DIAMOND,
                            "diamonds" to FieldValue.increment(1),
                            "redeemTimestamps" to activeTimestamps + System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )

                    RedeemResult(success = true, message = "Redeemed 1 diamond!")
                }
            }
        }.await()
    }

    suspend fun spendDiamonds(uid: String, amount: Int): RedeemResult {
        if (amount <= 0) return RedeemResult(success = true, message = "")

        return firestore.runTransaction { transaction ->
            val snapshot = transaction.get(userDoc(uid))
            val currentDiamonds = snapshot.getLong("diamonds") ?: 0L

            if (currentDiamonds < amount) {
                RedeemResult(
                    success = false,
                    message = "You need $amount diamonds to buy this item."
                )
            } else {
                transaction.update(userDoc(uid), "diamonds", currentDiamonds - amount)
                RedeemResult(success = true, message = "Purchase successful!")
            }
        }.await()
    }

    suspend fun migrateGuestWallet(uid: String, coins: Int, diamonds: Int) {
        if (coins <= 0 && diamonds <= 0) return

        userDoc(uid).set(
            mapOf(
                "coins" to FieldValue.increment(coins.toLong()),
                "diamonds" to FieldValue.increment(diamonds.toLong())
            ),
            SetOptions.merge()
        ).await()
    }

    private fun readTimestamps(raw: Any?): List<Long> =
        (raw as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList()

    private fun List<Long>.filterActive(): List<Long> {
        val cutoff = System.currentTimeMillis() - WINDOW_MILLIS
        return filter { it > cutoff }
    }
}