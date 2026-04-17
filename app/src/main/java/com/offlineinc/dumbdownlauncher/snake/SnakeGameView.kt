package com.offlineinc.dumbdownlauncher.snake

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

// ─── Game states ────────────────────────────────────────────────────────────
enum class GameState { START, PLAYING, PAUSED, GAME_OVER }

data class Point(val x: Int, val y: Int)

// ─── SnakeGameView ───────────────────────────────────────────────────────────
class SnakeGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Preferences ──────────────────────────────────────────────────────────
    private val prefs: SharedPreferences =
        context.getSharedPreferences("snake_prefs", Context.MODE_PRIVATE)

    // ── Grid configuration ───────────────────────────────────────────────────
    private val cellSize = 16          // px per cell — good for 240px-wide screens
    private val gridCols get() = (width / cellSize).coerceAtLeast(1)
    private val gridRows get() = (height / cellSize).coerceAtLeast(1)

    // Pixel offset to centre the grid
    private val offsetX get() = (width - gridCols * cellSize) / 2
    private val offsetY get() = (height - gridRows * cellSize) / 2

    // ── Game data ─────────────────────────────────────────────────────────────
    private val snake = ArrayDeque<Point>()
    private var food = Point(0, 0)
    private var direction = Direction.RIGHT
    private var nextDirection = Direction.RIGHT
    private var score = 0
    private var highScore = prefs.getInt("high_score", 0)
    private var gameState = GameState.START

    /** Exposed read-only so the Activity can check state for back-button handling. */
    val currentState: GameState get() = gameState

    // ── Speed ─────────────────────────────────────────────────────────────────
    private val baseDelayMs = 250L      // starting speed
    private val minDelayMs = 80L        // fastest speed
    private val speedStepScore = 3     // every N food eaten → speed up
    private val speedStepMs = 20L      // ms reduction per step
    private val currentDelayMs get(): Long {
        val steps = score / speedStepScore
        return (baseDelayMs - steps * speedStepMs).coerceAtLeast(minDelayMs)
    }

    // ── Game loop ─────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val gameRunnable = object : Runnable {
        override fun run() {
            if (gameState == GameState.PLAYING) {
                tick()
                handler.postDelayed(this, currentDelayMs)
            }
        }
    }

    // ── Paints ────────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply { color = Color.BLACK }

    private val gridPaint = Paint().apply {
        color = Color.argb(30, 0, 180, 0)
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }

    private val snakeHeadPaint = Paint().apply {
        color = Color.rgb(0, 255, 0)
        style = Paint.Style.FILL
    }

    private val snakeBodyPaint = Paint().apply {
        color = Color.rgb(0, 200, 0)
        style = Paint.Style.FILL
    }

    private val snakeBorderPaint = Paint().apply {
        color = Color.rgb(0, 130, 0)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val foodPaint = Paint().apply {
        color = Color.rgb(255, 80, 80)
        style = Paint.Style.FILL
    }

    private val foodGlowPaint = Paint().apply {
        color = Color.rgb(255, 200, 200)
        style = Paint.Style.FILL
        alpha = 60
    }

    private val textPaint = Paint().apply {
        color = Color.rgb(0, 255, 0)
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val smallTextPaint = Paint().apply {
        color = Color.rgb(0, 200, 0)
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val dimTextPaint = Paint().apply {
        color = Color.rgb(0, 140, 0)
        textSize = 16f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val scorePaint = Paint().apply {
        color = Color.rgb(0, 220, 0)
        textSize = 18f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val gameOverPaint = Paint().apply {
        color = Color.rgb(255, 80, 80)
        textSize = 32f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val newBestPaint = Paint().apply {
        color = Color.rgb(255, 220, 0)
        textSize = 18f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val borderPaint = Paint().apply {
        color = Color.rgb(0, 180, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // ── Rect helpers ──────────────────────────────────────────────────────────
    private val cellRect = RectF()

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun handleKey(dir: Direction) {
        when (gameState) {
            GameState.PLAYING -> {
                // Prevent 180° reversals
                if (!direction.isOpposite(dir)) {
                    nextDirection = dir
                }
            }
            else -> {}
        }
    }

    fun handleKeyCenter() {
        when (gameState) {
            GameState.START, GameState.GAME_OVER -> startGame()
            GameState.PLAYING -> pauseGame()
            GameState.PAUSED -> resumeGame()
        }
    }

    fun pauseGame() {
        if (gameState == GameState.PLAYING) {
            gameState = GameState.PAUSED
            handler.removeCallbacks(gameRunnable)
            invalidate()
        }
    }

    fun resumeIfNeeded() {
        // Called from onResume — only auto-resume if we were paused due to backgrounding
        // (we leave pause toggling to the user via center key)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Prevent Handler callbacks from firing on a detached View
        handler.removeCallbacks(gameRunnable)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private game logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun startGame() {
        snake.clear()
        score = 0

        // Start snake in the middle, length 3, facing right
        val midX = gridCols / 2
        val midY = gridRows / 2
        snake.addFirst(Point(midX, midY))
        snake.addFirst(Point(midX + 1, midY))
        snake.addFirst(Point(midX + 2, midY))

        direction = Direction.RIGHT
        nextDirection = Direction.RIGHT

        spawnFood()

        gameState = GameState.PLAYING
        handler.removeCallbacks(gameRunnable)
        handler.postDelayed(gameRunnable, currentDelayMs)
        invalidate()
    }

    private fun resumeGame() {
        if (gameState == GameState.PAUSED) {
            gameState = GameState.PLAYING
            handler.postDelayed(gameRunnable, currentDelayMs)
            invalidate()
        }
    }

    private fun tick() {
        direction = nextDirection

        val head = snake.first()
        val newHead = when (direction) {
            Direction.UP    -> Point(head.x, head.y - 1)
            Direction.DOWN  -> Point(head.x, head.y + 1)
            Direction.LEFT  -> Point(head.x - 1, head.y)
            Direction.RIGHT -> Point(head.x + 1, head.y)
        }

        // Wall collision
        if (newHead.x < 0 || newHead.x >= gridCols ||
            newHead.y < 0 || newHead.y >= gridRows) {
            endGame()
            return
        }

        // Self collision (ignore tail since it will move).
        // Check all segments except the last (tail) without allocating a new list.
        val selfCollision = (0 until snake.size - 1).any { snake.elementAt(it) == newHead }
        if (selfCollision) {
            endGame()
            return
        }

        snake.addFirst(newHead)

        if (newHead == food) {
            score++
            if (score > highScore) {
                highScore = score
                prefs.edit().putInt("high_score", highScore).apply()
            }
            spawnFood()
            // Don't remove tail — snake grows
        } else {
            snake.removeLast()
        }

        invalidate()
    }

    private fun endGame() {
        gameState = GameState.GAME_OVER
        handler.removeCallbacks(gameRunnable)
        invalidate()
    }

    private fun spawnFood() {
        val emptyCells = mutableListOf<Point>()
        for (x in 0 until gridCols) {
            for (y in 0 until gridRows) {
                val p = Point(x, y)
                if (!snake.contains(p)) emptyCells.add(p)
            }
        }
        food = if (emptyCells.isNotEmpty()) emptyCells[Random.nextInt(emptyCells.size)]
               else Point(0, 0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        when (gameState) {
            GameState.START    -> drawStartScreen(canvas)
            GameState.PLAYING,
            GameState.PAUSED   -> drawGame(canvas)
            GameState.GAME_OVER -> drawGameOver(canvas)
        }
    }

    private fun drawGame(canvas: Canvas) {
        drawGrid(canvas)
        drawBorder(canvas)
        drawFood(canvas)
        drawSnake(canvas)
        drawHUD(canvas)

        if (gameState == GameState.PAUSED) drawPauseOverlay(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        // Subtle grid lines
        for (x in 0..gridCols) {
            val px = (offsetX + x * cellSize).toFloat()
            canvas.drawLine(px, offsetY.toFloat(), px, (offsetY + gridRows * cellSize).toFloat(), gridPaint)
        }
        for (y in 0..gridRows) {
            val py = (offsetY + y * cellSize).toFloat()
            canvas.drawLine(offsetX.toFloat(), py, (offsetX + gridCols * cellSize).toFloat(), py, gridPaint)
        }
    }

    private fun drawBorder(canvas: Canvas) {
        val left = offsetX.toFloat()
        val top = offsetY.toFloat()
        val right = (offsetX + gridCols * cellSize).toFloat()
        val bottom = (offsetY + gridRows * cellSize).toFloat()
        canvas.drawRect(left, top, right, bottom, borderPaint)
    }

    private fun drawSnake(canvas: Canvas) {
        val margin = 2f
        snake.forEachIndexed { index, point ->
            val left   = (offsetX + point.x * cellSize + margin)
            val top    = (offsetY + point.y * cellSize + margin)
            val right  = (offsetX + point.x * cellSize + cellSize - margin)
            val bottom = (offsetY + point.y * cellSize + cellSize - margin)

            cellRect.set(left, top, right, bottom)

            val paint = if (index == 0) snakeHeadPaint else snakeBodyPaint
            canvas.drawRoundRect(cellRect, 3f, 3f, paint)
            canvas.drawRoundRect(cellRect, 3f, 3f, snakeBorderPaint)
        }
    }

    private fun drawFood(canvas: Canvas) {
        val cx = (offsetX + food.x * cellSize + cellSize / 2).toFloat()
        val cy = (offsetY + food.y * cellSize + cellSize / 2).toFloat()
        val r  = cellSize / 2f - 2f

        // Glow
        canvas.drawCircle(cx, cy, r + 2f, foodGlowPaint)
        // Food dot
        canvas.drawCircle(cx, cy, r, foodPaint)
    }

    private fun drawHUD(canvas: Canvas) {
        // Score top-left, High Score top-right
        canvas.drawText("Score: $score", 6f, scorePaint.textSize + 2f, scorePaint)
        scorePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Best: $highScore", width - 6f, scorePaint.textSize + 2f, scorePaint)
        scorePaint.textAlign = Paint.Align.LEFT
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        val overlay = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)

        val cy = height / 2f
        canvas.drawText("PAUSED", width / 2f, cy, textPaint)
        canvas.drawText("Press OK to resume", width / 2f, cy + 28f, dimTextPaint)
        canvas.drawText("Press Back to exit", width / 2f, cy + 48f, dimTextPaint)
    }

    private fun drawStartScreen(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawText("SNAKE", cx, cy - 60f, textPaint.apply { textSize = 40f })
        textPaint.textSize = 28f

        canvas.drawText("High Score: $highScore", cx, cy - 10f, smallTextPaint)
        canvas.drawText("Press OK to start", cx, cy + 30f, dimTextPaint)
        canvas.drawText("DPAD = move", cx, cy + 55f, dimTextPaint)
        canvas.drawText("OK = pause", cx, cy + 75f, dimTextPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawText("GAME OVER", cx, cy - 55f, gameOverPaint)

        canvas.drawText("Score: $score", cx, cy - 10f, textPaint)

        val isNewBest = score >= highScore && score > 0
        val hiPaint = if (isNewBest) newBestPaint else smallTextPaint
        val hiLabel = if (isNewBest) "NEW BEST: $highScore" else "Best: $highScore"
        canvas.drawText(hiLabel, cx, cy + 20f, hiPaint)

        canvas.drawText("Press OK to play again", cx, cy + 55f, dimTextPaint)
    }
}
