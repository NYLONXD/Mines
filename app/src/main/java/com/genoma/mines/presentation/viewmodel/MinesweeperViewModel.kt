package com.genoma.mines.presentation.viewmodel
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.genoma.mines.game.data.GameRepository
import com.genoma.mines.game.data.GameRepositoryImpl
import com.genoma.mines.game.data.GameResult
import com.genoma.mines.game.data.GameHistoryItem
import com.genoma.mines.game.data.UserStatistics
import com.genoma.mines.achievements.domain.AchievementCalculator
import com.genoma.mines.game.data.local.GuestGameDatabase
import com.genoma.mines.game.data.local.GuestGameRepository
import com.genoma.mines.game.data.remote.FirestoreGameRepository
import com.genoma.mines.wallet.data.remote.FirestoreWalletRepository
import com.genoma.mines.userfeedback.data.FirestoreFeedbackRepository
import com.genoma.mines.userfeedback.data.FeedbackSubmission
import com.genoma.mines.celebration.domain.CelebrationEvent
import com.genoma.mines.game.domain.GameFeedback
import com.genoma.mines.game.domain.Difficulty
import com.genoma.mines.game.domain.GameState
import com.genoma.mines.game.domain.GameStatus
import com.genoma.mines.game.domain.GameResultType
import com.genoma.mines.game.domain.LevelCalculator
import com.genoma.mines.game.domain.MinesweeperGame
import com.genoma.mines.game.domain.ScoreCalculator
import com.genoma.mines.session.SessionManager
import com.genoma.mines.settings.data.SettingsDataStore
import com.genoma.mines.store.data.StoreDataStore
import com.genoma.mines.store.data.StoreRepository
import com.genoma.mines.store.data.StoreRepositoryImpl
import com.genoma.mines.store.data.remote.FirestoreStoreRepository
import com.genoma.mines.store.domain.StoreItem
import com.genoma.mines.profile.domain.AvatarOption
import com.genoma.mines.userfeedback.ui.FeedbackData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import com.genoma.mines.settings.ui.ThemePreference
import com.genoma.mines.settings.ui.toDarkThemeFlag
import com.genoma.mines.wallet.data.CoinWalletDataStore
import com.genoma.mines.wallet.data.RedeemStatus
import com.genoma.mines.wallet.data.WalletRepository
import com.genoma.mines.wallet.data.WalletRepositoryImpl
import com.genoma.mines.life.data.LifeDataStore
import com.genoma.mines.life.data.LifeRepository
import com.genoma.mines.life.data.LifeRepositoryImpl
import com.genoma.mines.life.data.remote.FirestoreLifeRepository
import com.genoma.mines.life.domain.LifeRules
import com.genoma.mines.life.domain.LifeSnapshot

class MinesweeperViewModel(
    application: Application
) : AndroidViewModel(application) {

    private companion object {
        /** A game that runs this long auto-quits back to the home screen. */
        const val MAX_GAME_DURATION_SECONDS = 30 * 60

        /**
         * Coins paid for a win, scaled by board difficulty so that clearing a
         * denser board is worth more than grinding the easiest one.
         */
        fun coinRewardFor(difficulty: Difficulty): Int = when (difficulty) {
            Difficulty.EASY -> 50
            Difficulty.MEDIUM -> 100
            Difficulty.HARD -> 200
        }
    }

    private var game: MinesweeperGame? = null
    private var timerJob: Job? = null

    private val feedback = GameFeedback(application)
    private val settings = SettingsDataStore(application)

    private val sessionManager = SessionManager()
    private val scoreCalculator = ScoreCalculator()

    private val storeDataStore: StoreRepository = StoreRepositoryImpl(
        sessionManager = sessionManager,
        guestStore = StoreDataStore(application),
        firestoreStore = FirestoreStoreRepository()
    )

    private val feedbackRepository = FirestoreFeedbackRepository()

    private val gameRepository: GameRepository = GameRepositoryImpl(
        sessionManager = sessionManager,
        guestRepository = GuestGameRepository(
            GuestGameDatabase.getInstance(application).guestGameDao()
        ),
        firestoreRepository = FirestoreGameRepository()
    )

    private var gameResultSaved = false

    private val wallet: WalletRepository = WalletRepositoryImpl(
        sessionManager = sessionManager,
        guestWallet = CoinWalletDataStore(application),
        firestoreWallet = FirestoreWalletRepository()
    )

    private val lives: LifeRepository = LifeRepositoryImpl(
        sessionManager = sessionManager,
        guestLife = LifeDataStore(application),
        firestoreLife = FirestoreLifeRepository()
    )

    private var startGameJob: Job? = null

    private val _lifeSnapshot = MutableStateFlow(LifeSnapshot.FULL)
    val lifeSnapshot: StateFlow<LifeSnapshot> = _lifeSnapshot.asStateFlow()

    private val _showHeartsDialog = MutableStateFlow(false)
    val showHeartsDialog: StateFlow<Boolean> = _showHeartsDialog.asStateFlow()

    private val _isRefillingHearts = MutableStateFlow(false)
    val isRefillingHearts: StateFlow<Boolean> = _isRefillingHearts.asStateFlow()

    private val _coins = MutableStateFlow(0)
    val coins: StateFlow<Int> = _coins.asStateFlow()

    private val _diamonds = MutableStateFlow(0)
    val diamonds: StateFlow<Int> = _diamonds.asStateFlow()

    private val _redeemStatus = MutableStateFlow<RedeemStatus?>(null)
    val redeemStatus: StateFlow<RedeemStatus?> = _redeemStatus.asStateFlow()

    private val _redeemResultMessage = MutableStateFlow<String?>(null)
    val redeemResultMessage: StateFlow<String?> = _redeemResultMessage.asStateFlow()

    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _lastScore = MutableStateFlow<Int?>(null)
    val lastScore: StateFlow<Int?> = _lastScore.asStateFlow()

    private val _isNewBestTime = MutableStateFlow(false)
    val isNewBestTime: StateFlow<Boolean> = _isNewBestTime.asStateFlow()

    // The player's best prior time (seconds) at this difficulty, captured
    // right before the just-finished game is saved — lets the UI show how
    // the current run compares, whether it's a new record or not.
    private val _previousBestSeconds = MutableStateFlow<Long?>(null)
    val previousBestSeconds: StateFlow<Long?> = _previousBestSeconds.asStateFlow()

    // Milestones (level-ups, newly unlocked achievement tiers) earned by the
    // most recently completed game, queued so the UI can acknowledge them
    // one at a time instead of the change only showing up silently the next
    // time the player opens Profile or Achievements.
    private val _celebrationEvents = MutableStateFlow<List<CelebrationEvent>>(emptyList())
    val celebrationEvents: StateFlow<List<CelebrationEvent>> = _celebrationEvents.asStateFlow()

    // Null = no saved preference yet; the UI falls back to the system
    // setting until the user explicitly picks light or dark.
    private val _darkTheme = MutableStateFlow<Boolean?>(null)
    val darkTheme: StateFlow<Boolean?> = _darkTheme.asStateFlow()

    private val _selectedAvatar = MutableStateFlow(AvatarOption.Default)
    val selectedAvatar: StateFlow<AvatarOption> = _selectedAvatar.asStateFlow()

    private val _ownedStoreItemIds = MutableStateFlow<Set<String>>(emptySet())
    val ownedStoreItemIds: StateFlow<Set<String>> = _ownedStoreItemIds.asStateFlow()

    private val _equippedBoardThemeId = MutableStateFlow("board_classic_teal")
    val equippedBoardThemeId: StateFlow<String> = _equippedBoardThemeId.asStateFlow()

    private val _equippedCellSkinId = MutableStateFlow<String?>(null)
    val equippedCellSkinId: StateFlow<String?> = _equippedCellSkinId.asStateFlow()



    private val _isSubmittingFeedback = MutableStateFlow(false)
    val isSubmittingFeedback: StateFlow<Boolean> = _isSubmittingFeedback.asStateFlow()

    private val _feedbackError = MutableStateFlow<String?>(null)
    val feedbackError: StateFlow<String?> = _feedbackError.asStateFlow()

    private val _feedbackSubmitted = MutableStateFlow(false)
    val feedbackSubmitted: StateFlow<Boolean> = _feedbackSubmitted.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {

            launch {
                settings.soundEnabled.collect { enabled ->
                    _soundEnabled.value = enabled
                }
            }

            launch {
                settings.hapticsEnabled.collect { enabled ->
                    _hapticsEnabled.value = enabled
                }
            }

            launch {
                settings.darkThemeEnabled.collect { enabled ->
                    _darkTheme.value = enabled
                }
            }

            launch {
                settings.selectedAvatarId.collect { avatarId ->
                    _selectedAvatar.value = AvatarOption.fromId(avatarId)
                }
            }

            launch {
                storeDataStore.ownedItemIds.collect { _ownedStoreItemIds.value = it }
            }

            launch {
                storeDataStore.equippedBoardThemeId.collect { _equippedBoardThemeId.value = it }
            }

            launch {
                storeDataStore.equippedCellSkinId.collect { _equippedCellSkinId.value = it }
            }

            launch {
                wallet.coins.collect { _coins.value = it }
            }

            launch {
                wallet.diamonds.collect { _diamonds.value = it }
            }

            launch {
                lives.lifeSnapshot.collect { _lifeSnapshot.value = it }
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        _soundEnabled.value = enabled

        viewModelScope.launch {
            settings.setSoundEnabled(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _hapticsEnabled.value = enabled

        viewModelScope.launch {
            settings.setHapticsEnabled(enabled)
        }
    }

    fun setThemePreference(preference: ThemePreference) {
        val flag = preference.toDarkThemeFlag()
        _darkTheme.value = flag

        viewModelScope.launch {
            if (flag == null) {
                settings.clearDarkThemePreference()
            } else {
                settings.setDarkThemeEnabled(flag)
            }
        }
    }
    fun setAvatar(avatar: AvatarOption) {
        _selectedAvatar.value = avatar

        viewModelScope.launch {
            settings.setSelectedAvatarId(avatar.id)
        }
    }

    fun purchaseOrEquipStoreItem(item: StoreItem) {
        viewModelScope.launch {
            val owned = try {
                item.price == 0 || storeDataStore.isOwned(item.id)
            } catch (_: Exception) {
                _redeemResultMessage.value = "Couldn't reach the store. Check your connection and try again."
                return@launch
            }

            if (!owned) {
                val result = try {
                    wallet.spendDiamonds(item.price)
                } catch (_: Exception) {
                    _redeemResultMessage.value = "Couldn't complete the purchase. Check your connection and try again."
                    return@launch
                }

                if (!result.success) {
                    _redeemResultMessage.value =
                        "You need ${item.price} gems to buy ${item.name}."
                    return@launch
                }

                try {
                    storeDataStore.addOwnedItem(item.id)
                } catch (_: Exception) {
                    // The gems are already spent, so hand them back rather than
                    // charging for an item the player never received.
                    runCatching { wallet.addDiamonds(item.price) }
                    _redeemResultMessage.value = "Couldn't complete the purchase. Your gems were not charged."
                    return@launch
                }
            }

            // The item is owned at this point either way, so a failure to equip
            // is only worth a message — it isn't worth undoing the purchase.
            try {
                when (item) {
                    is com.genoma.mines.store.domain.BoardThemeItem -> storeDataStore.equipBoardTheme(item.id)
                    is com.genoma.mines.store.domain.CellSkinItem -> storeDataStore.equipCellSkin(item.id)
                    is com.genoma.mines.store.domain.AvatarStoreItem -> {
                        // Avatar artwork is not yet part of AvatarOption. Ownership is
                        // still saved, but no fake visual mapping is introduced.
                    }
                }
            } catch (_: Exception) {
                _redeemResultMessage.value = "${item.name} is yours, but couldn't be applied. Tap it again."
            }
        }
    }

    /**
     * Spends a heart, then starts the game. With no hearts left the hearts
     * dialog opens instead and [gameState] is left untouched.
     *
     * The heart is taken up front and handed back on a win, so losing,
     * quitting, restarting mid-game, or the app being killed all cost exactly
     * one heart without needing to detect each case.
     */
    fun startGame(difficulty: Difficulty) {
        if (startGameJob?.isActive == true) return

        startGameJob = viewModelScope.launch {
            val consumed = try {
                lives.consumeHeart()
            } catch (_: Exception) {
                _redeemResultMessage.value = "Couldn't start the game. Check your connection and try again."
                return@launch
            }

            if (consumed) {
                beginGame(difficulty)
            } else {
                _showHeartsDialog.value = true
            }
        }
    }

    private fun beginGame(difficulty: Difficulty) {
        timerJob?.cancel()

        game = MinesweeperGame(difficulty)
        gameResultSaved = false
        _lastScore.value = null
        _isNewBestTime.value = false
        _previousBestSeconds.value = null

        val newGame = game ?: return

        _gameState.value = GameState(
            difficulty = difficulty,
            cells = newGame.getBoard(),
            flagsPlaced = 0,
            elapsedSeconds = 0,
            status = GameStatus.PLAYING
        )

        startTimer()
    }

    fun togglePause() {
        val currentState = _gameState.value ?: return

        when (currentState.status) {

            GameStatus.PLAYING -> {
                timerJob?.cancel()

                _gameState.value = currentState.copy(
                    status = GameStatus.PAUSED
                )
            }

            GameStatus.PAUSED -> {
                _gameState.value = currentState.copy(
                    status = GameStatus.PLAYING
                )

                startTimer()
            }

            else -> {
                // Cannot pause a game that is ready, won, or lost.
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1.seconds)

                val currentState = _gameState.value

                if (currentState?.status != GameStatus.PLAYING) {
                    break
                }

                val updatedSeconds = currentState.elapsedSeconds + 1

                if (updatedSeconds >= MAX_GAME_DURATION_SECONDS) {
                    goBackToHome()
                    break
                }

                _gameState.value = currentState.copy(
                    elapsedSeconds = updatedSeconds
                )
            }
        }
    }

    fun revealCell(index: Int) {

        val currentGame = game ?: return
        val currentState = _gameState.value ?: return

        if (currentState.status != GameStatus.PLAYING) {
            return
        }

        val tappedCell = currentState.cells.getOrNull(index) ?: return

        val detonatedIndex = if (tappedCell.isRevealed) {
            currentGame.chord(index)
        } else {
            currentGame.reveal(index)
        }

        if (detonatedIndex != null) {
            timerJob?.cancel()

            feedback.explosion(
                soundEnabled = _soundEnabled.value,
                hapticsEnabled = _hapticsEnabled.value
            )

            currentGame.revealAllMines()

            val finalState = currentState.copy(
                cells = currentGame.getBoard(),
                flagsPlaced = currentGame.getFlagsPlaced(),
                status = GameStatus.LOST,
                detonatedCellIndex = detonatedIndex
            )

            _gameState.value = finalState
            completeLevel(finalState, GameResultType.LOSS)

            return
        }

        val status = if (currentGame.isWon()) {
            GameStatus.WON
        } else {
            GameStatus.PLAYING
        }

        if (status == GameStatus.WON) {
            timerJob?.cancel()

            feedback.win(
                soundEnabled = _soundEnabled.value,
                hapticsEnabled = _hapticsEnabled.value
            )
        } else {
            feedback.tap(
                soundEnabled = _soundEnabled.value,
                hapticsEnabled = _hapticsEnabled.value
            )
        }

        val updatedState = currentState.copy(
            cells = currentGame.getBoard(),
            flagsPlaced = currentGame.getFlagsPlaced(),
            status = status
        )

        _gameState.value = updatedState

        if (status == GameStatus.WON) {
            completeLevel(updatedState, GameResultType.WIN)
        }
    }

    fun toggleFlag(index: Int) {

        val currentGame = game ?: return
        val currentState = _gameState.value ?: return

        if (currentState.status != GameStatus.PLAYING) {
            return
        }

        val changed = currentGame.toggleFlag(index)

        if (!changed) {
            return
        }

        feedback.flag(
            soundEnabled = _soundEnabled.value,
            hapticsEnabled = _hapticsEnabled.value
        )

        _gameState.value = currentState.copy(
            cells = currentGame.getBoard(),
            flagsPlaced = currentGame.getFlagsPlaced()
        )
    }

    private fun completeLevel(state: GameState, result: GameResultType) {
        if (gameResultSaved) return
        gameResultSaved = true

        if (result == GameResultType.WIN) {
            viewModelScope.launch {
                wallet.addCoins(coinRewardFor(state.difficulty))
            }

            // Winning keeps the heart that was spent to start this game.
            viewModelScope.launch {
                try {
                    lives.refundHeart()
                } catch (_: Exception) {
                    _redeemResultMessage.value = "Couldn't return your heart. Check your connection."
                }
            }
        }

        val correctlyRevealedCells = state.cells.count { it.isRevealed && !it.isMine }

        val totalSafeCells = state.difficulty.rows * state.difficulty.columns - state.difficulty.mines

        val score = scoreCalculator.calculate(
            difficulty = state.difficulty,
            result = result,
            elapsedSeconds = state.elapsedSeconds.toLong(),
            correctlyRevealedCells = correctlyRevealedCells,
            totalSafeCells = totalSafeCells
        )

        _lastScore.value = score

        val gameResult = GameResult(
            difficulty = state.difficulty,
            score = score,
            result = result,
            durationSeconds = state.elapsedSeconds.toLong()
        )

        viewModelScope.launch {
            // Fetched once up front: doubles as the "before this game"
            // history used both for the best-time comparison below and for
            // the level/achievement diff after saving.
            val historyBeforeThisGame = gameRepository.getGameHistory()

            if (result == GameResultType.WIN) {
                // Compare against past wins at this difficulty *before*
                // saving the current one, so it's judged against previous
                // attempts rather than against itself.
                val previousBestSeconds = historyBeforeThisGame
                    .filter {
                        it.difficulty == state.difficulty &&
                                it.result == GameResultType.WIN
                    }
                    .minOfOrNull { it.durationSeconds }

                val isNewBest = previousBestSeconds == null ||
                        state.elapsedSeconds.toLong() < previousBestSeconds

                _isNewBestTime.value = isNewBest
                _previousBestSeconds.value = previousBestSeconds

                if (isNewBest) {
                    feedback.newBestTime(
                        soundEnabled = _soundEnabled.value,
                        hapticsEnabled = _hapticsEnabled.value
                    )
                }
            } else {
                _isNewBestTime.value = false
                _previousBestSeconds.value = null
            }

            // Snapshot level + achievement progress from *before* this game
            // counts, so afterwards we can tell exactly what just changed.
            val levelBefore = levelFor(historyBeforeThisGame)
            val achievedTiersBefore = AchievementCalculator.calculate(historyBeforeThisGame)
                .associate { it.id to it.achievedTier }

            gameRepository.saveGameResult(gameResult)

            // Rather than re-querying the repository (which, for an
            // authenticated user, could race with server-side write
            // propagation), the just-saved game is appended locally to the
            // same history snapshot used above — cheap, and guaranteed to
            // reflect exactly what was just written.
            val historyAfterThisGame = historyBeforeThisGame + GameHistoryItem(
                difficulty = state.difficulty,
                score = score,
                result = result,
                durationSeconds = state.elapsedSeconds.toLong(),
                createdAtMillis = gameResult.createdAt
            )

            val levelAfter = levelFor(historyAfterThisGame)
            val tracksAfter = AchievementCalculator.calculate(historyAfterThisGame)

            val newCelebrations = mutableListOf<CelebrationEvent>()

            if (levelAfter > levelBefore) {
                newCelebrations += CelebrationEvent.LevelUp(newLevel = levelAfter)
            }

            tracksAfter.forEach { track ->
                val tierAfter = track.achievedTier ?: return@forEach
                val tierBefore = achievedTiersBefore[track.id]

                if (tierBefore == null || tierAfter.ordinal > tierBefore.ordinal) {
                    newCelebrations += CelebrationEvent.AchievementUnlocked(
                        trackId = track.id,
                        trackTitle = track.title,
                        tier = tierAfter
                    )
                }
            }

            if (newCelebrations.isNotEmpty()) {
                if (newCelebrations.any { it is CelebrationEvent.LevelUp }) {
                    feedback.levelUp(
                        soundEnabled = _soundEnabled.value,
                        hapticsEnabled = _hapticsEnabled.value
                    )
                }

                if (newCelebrations.any { it is CelebrationEvent.AchievementUnlocked }) {
                    feedback.achievementUnlocked(
                        soundEnabled = _soundEnabled.value,
                        hapticsEnabled = _hapticsEnabled.value
                    )
                }

                _celebrationEvents.value = _celebrationEvents.value + newCelebrations
            }
        }
    }

    /** Player level implied by a completed history list, via total XP. */
    private fun levelFor(history: List<GameHistoryItem>): Int {
        val wins = history.count { it.result == GameResultType.WIN }
        val losses = history.count { it.result == GameResultType.LOSS }

        return LevelCalculator.calculateProgress(
            LevelCalculator.calculateTotalXp(wins, losses)
        ).level
    }

    /** Pops the front-most queued celebration once the UI has shown it. */
    fun consumeCelebrationEvent() {
        _celebrationEvents.value = _celebrationEvents.value.drop(1)
    }

    suspend fun loadGameHistory(): List<GameHistoryItem> {
        return gameRepository.getGameHistory()
    }

    suspend fun loadStatistics(): UserStatistics {
        return gameRepository.getStatistics()
    }

    fun resetGame() {
        val currentState = _gameState.value ?: return

        startGame(currentState.difficulty)
    }

    fun goBackToHome() {
        timerJob?.cancel()
        game = null
        _gameState.value = null
    }

    fun submitFeedback(data: FeedbackData, userId: String?) {
        if (_isSubmittingFeedback.value) return

        _isSubmittingFeedback.value = true
        _feedbackError.value = null

        viewModelScope.launch {
            try {
                feedbackRepository.submitFeedback(
                    FeedbackSubmission(
                        userId = userId,
                        userName = data.userName,
                        userEmail = data.userEmail,
                        description = data.description,
                        screenshotCount = data.screenshots.size
                    )
                )
                _feedbackSubmitted.value = true
            } catch (e: Exception) {
                _feedbackError.value = e.message ?: "Couldn't send feedback. Please try again."
            } finally {
                _isSubmittingFeedback.value = false
            }
        }
    }

    fun resetFeedbackSubmitted() {
        _feedbackSubmitted.value = false
    }

    fun refreshRedeemStatus() {
        viewModelScope.launch {
            _redeemStatus.value = wallet.getRedeemStatus()
        }
    }

    fun redeemDiamond() {
        viewModelScope.launch {
            val result = wallet.redeemDiamond()
            _redeemResultMessage.value = result.message
            _redeemStatus.value = wallet.getRedeemStatus()
        }
    }

    fun openHeartsDialog() {
        _showHeartsDialog.value = true
    }

    fun dismissHeartsDialog() {
        if (_isRefillingHearts.value) return
        _showHeartsDialog.value = false
    }

    /**
     * Buys the hearts the player is actually missing, at
     * [LifeRules.DIAMONDS_PER_HEART] gems each.
     *
     * The price is read off the same status the refill is applied to, so a
     * heart that regenerates mid-purchase can never be charged for.
     */
    fun refillHearts() {
        if (_isRefillingHearts.value) return
        _isRefillingHearts.value = true

        viewModelScope.launch {
            var cost = 0

            try {
                val status = lives.getStatus()
                if (status.isFull) {
                    _redeemResultMessage.value = "Your hearts are already full."
                    return@launch
                }

                val missing = status.maxHearts - status.hearts
                cost = LifeRules.refillCost(status.hearts)

                val payment = wallet.spendDiamonds(cost)
                if (!payment.success) {
                    _redeemResultMessage.value =
                        "You need $cost gems to refill $missing " +
                                if (missing == 1) "heart." else "hearts."
                    return@launch
                }

                try {
                    lives.refillHearts()
                } catch (e: Exception) {
                    // The gems are already gone, so hand them back rather than
                    // charging for hearts the player never received.
                    wallet.addDiamonds(cost)
                    throw e
                }

                _redeemResultMessage.value =
                    if (missing == 1) "1 heart refilled!" else "$missing hearts refilled!"
                _showHeartsDialog.value = false
            } catch (_: Exception) {
                _redeemResultMessage.value = "Couldn't refill hearts. Check your connection and try again."
            } finally {
                _isRefillingHearts.value = false
            }
        }
    }

    fun consumeRedeemResultMessage() {
        _redeemResultMessage.value = null
    }

    override fun onCleared() {
        timerJob?.cancel()
        feedback.release()
    }
}