package com.genoma.mines.wallet.data
import kotlinx.coroutines.flow.Flow
interface WalletRepository {
    val coins: Flow<Int>
    val diamonds: Flow<Int>

    suspend fun addCoins(amount: Int)
    suspend fun getRedeemStatus(): RedeemStatus
    suspend fun redeemDiamond(): RedeemResult

    suspend fun spendDiamonds(amount: Int): RedeemResult

    /** Returns diamonds to the balance, e.g. refunding a purchase that failed. */
    suspend fun addDiamonds(amount: Int)
}