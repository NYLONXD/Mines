package com.genoma.mines.wallet.data
import com.genoma.mines.wallet.data.remote.FirestoreWalletRepository
import com.genoma.mines.session.SessionManager
import com.genoma.mines.session.UserSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class WalletRepositoryImpl(
    private val sessionManager: SessionManager,
    private val guestWallet: CoinWalletDataStore,
    private val firestoreWallet: FirestoreWalletRepository
) : WalletRepository {

    private val walletSnapshot: Flow<WalletSnapshot> =
        sessionManager.sessionFlow.flatMapLatest { session ->
            when (session) {
                is UserSession.Authenticated ->
                    firestoreWallet.observeWallet(session.firebaseUid)

                UserSession.Guest ->
                    combine(
                        guestWallet.coins,
                        guestWallet.diamonds
                    ) { coins, diamonds ->
                        WalletSnapshot(
                            coins = coins,
                            diamonds = diamonds
                        )
                    }
            }
        }

    override val coins: Flow<Int> =
        walletSnapshot.map { it.coins }

    override val diamonds: Flow<Int> =
        walletSnapshot.map { it.diamonds }

    override suspend fun addCoins(amount: Int) {
        when (val session = sessionManager.currentSession) {
            is UserSession.Authenticated ->
                firestoreWallet.addCoins(
                    session.firebaseUid,
                    amount
                )

            UserSession.Guest ->
                guestWallet.addCoins(amount)
        }
    }

    override suspend fun addDiamonds(amount: Int) {
        when (val session = sessionManager.currentSession) {
            is UserSession.Authenticated ->
                firestoreWallet.addDiamonds(
                    session.firebaseUid,
                    amount
                )

            UserSession.Guest ->
                guestWallet.addDiamonds(amount)
        }
    }

    override suspend fun getRedeemStatus(): RedeemStatus {
        return when (val session = sessionManager.currentSession) {
            is UserSession.Authenticated ->
                firestoreWallet.getRedeemStatus(
                    session.firebaseUid
                )

            UserSession.Guest ->
                guestWallet.getRedeemStatus()
        }
    }

    /**
     * Spends diamonds when purchasing a Store item.
     *
     * Logged-in users use the Firestore wallet.
     * Guest users use the local DataStore wallet.
     */
    override suspend fun spendDiamonds(
        amount: Int
    ): RedeemResult {
        return when (val session = sessionManager.currentSession) {
            is UserSession.Authenticated ->
                firestoreWallet.spendDiamonds(
                    session.firebaseUid,
                    amount
                )

            UserSession.Guest ->
                guestWallet.spendDiamonds(amount)
        }
    }

    override suspend fun redeemDiamond(): RedeemResult {
        return when (val session = sessionManager.currentSession) {
            is UserSession.Authenticated ->
                firestoreWallet.redeemDiamond(
                    session.firebaseUid
                )

            UserSession.Guest ->
                guestWallet.redeemDiamond()
        }
    }
}