import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Snake Game");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.add(new SnakeGamePanel());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}

class SnakeGamePanel extends JPanel implements ActionListener, KeyListener {
    private static final String UI_FONT = "SF Pro Display";

    private static final int TILE_SIZE = 26;
    private static final int GRID_WIDTH = 24;
    private static final int GRID_HEIGHT = 24;
    private static final int PANEL_WIDTH = TILE_SIZE * GRID_WIDTH;
    private static final int PANEL_HEIGHT = TILE_SIZE * GRID_HEIGHT;

    private static final int PLAY_MARGIN = 12;

    private static final int FRAME_MS = 16;
    private static final double MENU_ENTER_MS = 320.0;
    private static final double KEY_GUIDE_DELAY_MS = 110.0;

    private static final int HEAD_RADIUS = 11;
    private static final int BODY_RADIUS = 9;
    private static final double SEGMENT_SPACING = 14.0;
    private static final int FOOD_RADIUS = 8;

    private final Random random = new Random();
    private final Timer timer = new Timer(FRAME_MS, this);
    private final List<Vec2> snake = new ArrayList<>();

    private Vec2 food;
    private int dirX = 1;
    private int dirY = 0;
    private int pendingDirX = 1;
    private int pendingDirY = 0;

    private double currentSpeed = SpeedMode.STANDARD.baseSpeed;
    private int score = 0;
    private long lastUpdateNanos = 0;
    private long stateEnterNanos = System.nanoTime();

    private GameState gameState = GameState.MENU;
    private SpeedMode speedMode = SpeedMode.STANDARD;

    private enum GameState {
        MENU,
        RUNNING,
        PAUSED,
        GAME_OVER
    }

    private enum SpeedMode {
        SMOOTH("Smooth", 115.0, 235.0, 0.65),
        STANDARD("Standard", 130.0, 300.0, 1.0),
        AGGRESSIVE("Aggressive", 145.0, 360.0, 1.4);

        final String label;
        final double baseSpeed;
        final double maxSpeed;
        final double speedPerScore;

        SpeedMode(String label, double baseSpeed, double maxSpeed, double speedPerScore) {
            this.label = label;
            this.baseSpeed = baseSpeed;
            this.maxSpeed = maxSpeed;
            this.speedPerScore = speedPerScore;
        }
    }

    private static class Vec2 {
        double x;
        double y;

        Vec2(double x, double y) {
            this.x = x;
            this.y = y;
        }

        Vec2(Vec2 other) {
            this.x = other.x;
            this.y = other.y;
        }
    }

    SnakeGamePanel() {
        setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
        setFocusable(true);
        addKeyListener(this);
        lastUpdateNanos = System.nanoTime();
        timer.start();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        requestFocusInWindow();
    }

    private void startGame() {
        snake.clear();

        double startX = PANEL_WIDTH / 2.0;
        double startY = PANEL_HEIGHT / 2.0;

        for (int i = 0; i < 4; i++) {
            snake.add(new Vec2(startX - i * SEGMENT_SPACING, startY));
        }

        dirX = 1;
        dirY = 0;
        pendingDirX = 1;
        pendingDirY = 0;
        score = 0;
        currentSpeed = speedMode.baseSpeed;

        setGameState(GameState.RUNNING);
        spawnFood();

        lastUpdateNanos = System.nanoTime();
        repaint();
    }

    private void pauseOrResume() {
        if (gameState == GameState.RUNNING) {
            setGameState(GameState.PAUSED);
        } else if (gameState == GameState.PAUSED) {
            setGameState(GameState.RUNNING);
            lastUpdateNanos = System.nanoTime();
        }
        repaint();
    }

    private void endGame() {
        setGameState(GameState.GAME_OVER);
        repaint();
    }

    private void setGameState(GameState newState) {
        if (gameState != newState) {
            gameState = newState;
            stateEnterNanos = System.nanoTime();
        }
    }

    private void updateDifficulty() {
        currentSpeed = Math.min(speedMode.maxSpeed, speedMode.baseSpeed + score * speedMode.speedPerScore);
    }

    private void spawnFood() {
        double minX = minPlayableX() + FOOD_RADIUS;
        double maxX = maxPlayableX() - FOOD_RADIUS;
        double minY = minPlayableY() + FOOD_RADIUS;
        double maxY = maxPlayableY() - FOOD_RADIUS;

        for (int tries = 0; tries < 2000; tries++) {
            double x = minX + random.nextDouble() * (maxX - minX);
            double y = minY + random.nextDouble() * (maxY - minY);
            Vec2 candidate = new Vec2(x, y);

            if (isFarFromSnake(candidate, BODY_RADIUS + FOOD_RADIUS + 5)) {
                food = candidate;
                return;
            }
        }

        food = new Vec2(PANEL_WIDTH / 2.0, PANEL_HEIGHT / 2.0);
    }

    private boolean isFarFromSnake(Vec2 point, double minDistance) {
        for (Vec2 part : snake) {
            if (distance(point, part) < minDistance) {
                return false;
            }
        }
        return true;
    }

    private void applyPendingDirection() {
        if (!isOpposite(pendingDirX, pendingDirY, dirX, dirY)) {
            dirX = pendingDirX;
            dirY = pendingDirY;
        }
    }

    private boolean isOpposite(int x1, int y1, int x2, int y2) {
        return x1 == -x2 && y1 == -y2;
    }

    private void moveSnake(double deltaSeconds) {
        Vec2 head = snake.get(0);
        head.x += dirX * currentSpeed * deltaSeconds;
        head.y += dirY * currentSpeed * deltaSeconds;

        for (int i = 1; i < snake.size(); i++) {
            Vec2 prev = snake.get(i - 1);
            Vec2 cur = snake.get(i);

            double dx = prev.x - cur.x;
            double dy = prev.y - cur.y;
            double dist = Math.hypot(dx, dy);

            if (dist > 0.0001) {
                cur.x = prev.x - dx / dist * SEGMENT_SPACING;
                cur.y = prev.y - dy / dist * SEGMENT_SPACING;
            }
        }
    }

    private void growByOne() {
        if (snake.isEmpty()) {
            return;
        }

        if (snake.size() == 1) {
            snake.add(new Vec2(snake.get(0)));
            return;
        }

        Vec2 tail = snake.get(snake.size() - 1);
        Vec2 beforeTail = snake.get(snake.size() - 2);

        double dx = tail.x - beforeTail.x;
        double dy = tail.y - beforeTail.y;
        double dist = Math.hypot(dx, dy);

        if (dist < 0.0001) {
            snake.add(new Vec2(tail));
            return;
        }

        double newX = tail.x + dx / dist * SEGMENT_SPACING;
        double newY = tail.y + dy / dist * SEGMENT_SPACING;
        snake.add(new Vec2(newX, newY));
    }

    private boolean hitsWall(Vec2 head) {
        return head.x < minPlayableX() || head.x > maxPlayableX() || head.y < minPlayableY() || head.y > maxPlayableY();
    }

    private boolean hitsSelf(Vec2 head) {
        for (int i = 8; i < snake.size(); i++) {
            Vec2 part = snake.get(i);
            if (distance(head, part) < HEAD_RADIUS + BODY_RADIUS - 2) {
                return true;
            }
        }
        return false;
    }

    private double minPlayableX() {
        return PLAY_MARGIN + HEAD_RADIUS;
    }

    private double maxPlayableX() {
        return PANEL_WIDTH - PLAY_MARGIN - HEAD_RADIUS;
    }

    private double minPlayableY() {
        return PLAY_MARGIN + HEAD_RADIUS;
    }

    private double maxPlayableY() {
        return PANEL_HEIGHT - PLAY_MARGIN - HEAD_RADIUS;
    }

    private double distance(Vec2 a, Vec2 b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        if (gameState != GameState.RUNNING) {
            lastUpdateNanos = now;
            repaint();
            return;
        }

        double deltaSeconds = (now - lastUpdateNanos) / 1_000_000_000.0;
        lastUpdateNanos = now;

        if (deltaSeconds > 0.05) {
            deltaSeconds = 0.05;
        }

        applyPendingDirection();
        moveSnake(deltaSeconds);

        Vec2 head = snake.get(0);
        if (hitsWall(head) || hitsSelf(head)) {
            endGame();
            return;
        }

        if (food != null && distance(head, food) <= HEAD_RADIUS + FOOD_RADIUS) {
            score += 10;
            growByOne();
            updateDifficulty();
            spawnFood();
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        long now = System.nanoTime();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBackground(g2);
        drawPlayField(g2);

        if (gameState != GameState.MENU) {
            drawFood(g2);
            drawSnake(g2);
            drawHud(g2);
        }

        if (gameState == GameState.MENU) {
            drawStartMenu(g2, now);
        } else if (gameState == GameState.PAUSED) {
            drawGlassOverlay(
                    g2,
                    "Paused",
                    "Take a short break, then continue.",
                    "Press P to Resume",
                    "Press ENTER to restart  |  Mode: 1 / 2 / 3",
                    new Color(54, 124, 255),
                    now
            );
        } else if (gameState == GameState.GAME_OVER) {
            drawGlassOverlay(
                    g2,
                    "Game Over",
                    "Score: " + score,
                    "Press ENTER to Restart",
                    "Wall hit ended this run  |  Mode: 1 / 2 / 3",
                    new Color(255, 92, 92),
                    now
            );
        }

        g2.dispose();
    }

    private void drawBackground(Graphics2D g2) {
        GradientPaint gradient = new GradientPaint(
                0, 0, new Color(214, 232, 255),
                0, PANEL_HEIGHT, new Color(186, 236, 225)
        );
        g2.setPaint(gradient);
        g2.fillRect(0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        g2.setColor(new Color(255, 255, 255, 85));
        g2.fillOval(-70, -120, 320, 320);
        g2.setColor(new Color(255, 255, 255, 62));
        g2.fillOval(PANEL_WIDTH - 260, 70, 280, 280);
        g2.setColor(new Color(180, 220, 255, 55));
        g2.fillOval(PANEL_WIDTH / 2 - 120, PANEL_HEIGHT - 150, 260, 260);
    }

    private void drawPlayField(Graphics2D g2) {
        int x = PLAY_MARGIN;
        int y = PLAY_MARGIN;
        int w = PANEL_WIDTH - PLAY_MARGIN * 2;
        int h = PANEL_HEIGHT - PLAY_MARGIN * 2;

        g2.setColor(new Color(7, 20, 18, 120));
        g2.fillRoundRect(x, y, w, h, 28, 28);

        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(190, 255, 235, 90));
        g2.drawRoundRect(x, y, w, h, 28, 28);
    }

    private void drawFood(Graphics2D g2) {
        if (food == null) {
            return;
        }

        int cx = (int) Math.round(food.x);
        int cy = (int) Math.round(food.y);

        g2.setColor(new Color(255, 110, 110, 70));
        g2.fillOval(cx - 15, cy - 15, 30, 30);

        g2.setColor(new Color(255, 70, 70));
        g2.fillOval(cx - FOOD_RADIUS, cy - FOOD_RADIUS, FOOD_RADIUS * 2, FOOD_RADIUS * 2);

        g2.setColor(new Color(255, 200, 200));
        g2.fillOval(cx - 3, cy - 5, 4, 4);
    }

    private void drawSnake(Graphics2D g2) {
        for (int i = snake.size() - 1; i > 0; i--) {
            Vec2 cur = snake.get(i);
            Vec2 prev = snake.get(i - 1);

            float ratio = snake.size() <= 1 ? 0f : (float) i / (snake.size() - 1);
            int red = (int) (42 + (1f - ratio) * 38);
            int green = (int) (135 + (1f - ratio) * 75);
            int blue = (int) (68 + (1f - ratio) * 18);

            g2.setColor(new Color(red, green, blue));
            float width = (float) (BODY_RADIUS * 2 - ratio * 2.5);
            g2.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) Math.round(cur.x), (int) Math.round(cur.y), (int) Math.round(prev.x), (int) Math.round(prev.y));
        }

        drawHead(g2);
    }

    private void drawHead(Graphics2D g2) {
        if (snake.isEmpty()) {
            return;
        }

        Vec2 head = snake.get(0);
        int cx = (int) Math.round(head.x);
        int cy = (int) Math.round(head.y);

        g2.setColor(new Color(95, 235, 105));
        g2.fillOval(cx - HEAD_RADIUS, cy - HEAD_RADIUS, HEAD_RADIUS * 2, HEAD_RADIUS * 2);

        g2.setColor(new Color(215, 255, 220));
        g2.fillOval(cx - HEAD_RADIUS / 2, cy - HEAD_RADIUS / 2, HEAD_RADIUS, HEAD_RADIUS);

        drawEyes(g2, cx, cy);
    }

    private void drawEyes(Graphics2D g2, int cx, int cy) {
        g2.setColor(Color.WHITE);

        if (dirX != 0) {
            int eyeX = dirX > 0 ? cx + 4 : cx - 9;
            g2.fillOval(eyeX, cy - 7, 5, 5);
            g2.fillOval(eyeX, cy + 2, 5, 5);
        } else {
            int eyeY = dirY > 0 ? cy + 4 : cy - 9;
            g2.fillOval(cx - 7, eyeY, 5, 5);
            g2.fillOval(cx + 2, eyeY, 5, 5);
        }
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRoundRect(14, 14, 360, 58, 16, 16);

        g2.setColor(new Color(220, 255, 245));
        g2.setFont(new Font(UI_FONT, Font.BOLD, 18));
        g2.drawString("Score: " + score, 24, 38);
        g2.drawString("Mode: " + speedMode.label, 150, 38);

        g2.setFont(new Font(UI_FONT, Font.PLAIN, 15));
        String speedText = String.format("Speed: %.0f / %.0f px/s", currentSpeed, speedMode.maxSpeed);
        g2.drawString(speedText, 24, 59);
    }

    private void drawStartMenu(Graphics2D g2, long nowNanos) {
        double elapsedMs = (nowNanos - stateEnterNanos) / 1_000_000.0;
        float mainEnter = (float) easeOutCubic(clamp(elapsedMs / MENU_ENTER_MS, 0.0, 1.0));
        float keysEnter = (float) easeOutCubic(clamp((elapsedMs - KEY_GUIDE_DELAY_MS) / 280.0, 0.0, 1.0));

        int cardW = 470;
        int cardH = 250;
        int cardX = (PANEL_WIDTH - cardW) / 2;
        int cardY = PANEL_HEIGHT / 2 - cardH / 2 - 30;

        int centerX = cardX + cardW / 2;
        int centerY = cardY + cardH / 2;
        double scale = 0.97 + 0.03 * mainEnter;
        AffineTransform oldTransform = g2.getTransform();
        Composite oldComposite = g2.getComposite();
        g2.translate(centerX, centerY);
        g2.scale(scale, scale);
        g2.translate(-centerX, -centerY);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, mainEnter));

        g2.setColor(new Color(20, 30, 48, 38));
        g2.fillRoundRect(cardX + 6, cardY + 10, cardW, cardH, 34, 34);

        GradientPaint glass = new GradientPaint(
                cardX, cardY, new Color(255, 255, 255, 210),
                cardX, cardY + cardH, new Color(243, 249, 255, 185)
        );
        g2.setPaint(glass);
        g2.fillRoundRect(cardX, cardY, cardW, cardH, 34, 34);

        g2.setStroke(new BasicStroke(1.5f));
        g2.setColor(new Color(255, 255, 255, 210));
        g2.drawRoundRect(cardX, cardY, cardW, cardH, 34, 34);

        g2.setColor(new Color(255, 255, 255, 120));
        g2.fillRoundRect(cardX + 24, cardY + 18, cardW - 48, 28, 18, 18);

        g2.setColor(new Color(33, 48, 74));
        g2.setFont(new Font(UI_FONT, Font.BOLD, 64));
        g2.drawString("Snake", cardX + 34, cardY + 98);

        g2.setColor(new Color(70, 88, 113));
        g2.setFont(new Font(UI_FONT, Font.PLAIN, 21));
        g2.drawString("Clean. Smooth. Fast.", cardX + 38, cardY + 136);

        g2.setColor(new Color(40, 112, 255));
        g2.fillRoundRect(cardX + 34, cardY + 162, 176, 46, 23, 23);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(UI_FONT, Font.BOLD, 19));
        g2.drawString("Press Enter", cardX + 66, cardY + 192);

        g2.setColor(new Color(88, 103, 126));
        g2.setFont(new Font(UI_FONT, Font.PLAIN, 17));
        g2.drawString("to start game", cardX + 226, cardY + 191);

        g2.setTransform(oldTransform);
        g2.setComposite(oldComposite);

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, keysEnter));
        drawMenuKeyGuide(g2);
        g2.setComposite(oldComposite);
    }

    private void drawMenuKeyGuide(Graphics2D g2) {
        int panelW = 305;
        int panelH = 184;
        int panelX = PANEL_WIDTH - panelW - 20;
        int panelY = PANEL_HEIGHT - panelH - 20;

        g2.setColor(new Color(16, 26, 44, 36));
        g2.fillRoundRect(panelX + 4, panelY + 7, panelW, panelH, 24, 24);

        g2.setColor(new Color(247, 252, 255, 220));
        g2.fillRoundRect(panelX, panelY, panelW, panelH, 24, 24);
        g2.setColor(new Color(255, 255, 255, 210));
        g2.drawRoundRect(panelX, panelY, panelW, panelH, 24, 24);

        g2.setColor(new Color(44, 64, 95));
        g2.setFont(new Font(UI_FONT, Font.BOLD, 20));
        g2.drawString("Quick Keys", panelX + 18, panelY + 32);

        drawKeyRow(g2, panelX + 18, panelY + 56, "Enter", "Start / Restart");
        drawKeyRow(g2, panelX + 18, panelY + 86, "Arrows/WASD", "Move");
        drawKeyRow(g2, panelX + 18, panelY + 116, "P", "Pause / Resume");
        drawKeyRow(g2, panelX + 18, panelY + 146, "1 2 3", "Speed Mode");
    }

    private void drawKeyRow(Graphics2D g2, int x, int y, String key, String desc) {
        int chipW = 125;
        int chipH = 24;

        g2.setColor(new Color(233, 241, 252));
        g2.fillRoundRect(x, y - 16, chipW, chipH, 12, 12);
        g2.setColor(new Color(214, 226, 241));
        g2.drawRoundRect(x, y - 16, chipW, chipH, 12, 12);

        g2.setColor(new Color(52, 74, 108));
        g2.setFont(new Font(UI_FONT, Font.BOLD, 13));
        g2.drawString(key, x + 10, y + 1);

        g2.setColor(new Color(79, 95, 116));
        g2.setFont(new Font(UI_FONT, Font.PLAIN, 14));
        g2.drawString(desc, x + chipW + 12, y + 1);
    }

    private void drawGlassOverlay(
            Graphics2D g2,
            String title,
            String subtitle,
            String primaryAction,
            String secondaryHint,
            Color accentColor,
            long nowNanos
    ) {
        double elapsedMs = (nowNanos - stateEnterNanos) / 1_000_000.0;
        float enter = (float) easeOutCubic(clamp(elapsedMs / 260.0, 0.0, 1.0));
        double breathing = 1.0 + Math.sin(nowNanos / 220_000_000.0) * 0.004;

        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRect(0, 0, PANEL_WIDTH, PANEL_HEIGHT);

        int w = 470;
        int h = 230;
        int x = (PANEL_WIDTH - w) / 2;
        int y = (PANEL_HEIGHT - h) / 2;

        int cx = x + w / 2;
        int cy = y + h / 2;
        double scale = (0.955 + 0.045 * enter) * breathing;
        AffineTransform oldTransform = g2.getTransform();
        Composite oldComposite = g2.getComposite();
        g2.translate(cx, cy);
        g2.scale(scale, scale);
        g2.translate(-cx, -cy);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, enter));

        g2.setColor(new Color(20, 30, 48, 38));
        g2.fillRoundRect(x + 6, y + 10, w, h, 34, 34);

        GradientPaint glass = new GradientPaint(
                x, y, new Color(255, 255, 255, 220),
                x, y + h, new Color(243, 249, 255, 190)
        );
        g2.setPaint(glass);
        g2.fillRoundRect(x, y, w, h, 28, 28);

        g2.setStroke(new BasicStroke(1.5f));
        g2.setColor(new Color(255, 255, 255, 210));
        g2.drawRoundRect(x, y, w, h, 28, 28);

        g2.setColor(new Color(255, 255, 255, 120));
        g2.fillRoundRect(x + 24, y + 16, w - 48, 26, 16, 16);

        drawCenteredText(g2, title, 46, y + 88, new Color(33, 48, 74));
        drawCenteredText(g2, subtitle, 23, y + 124, new Color(76, 92, 115));

        int pillW = 265;
        int pillH = 44;
        int pillX = x + (w - pillW) / 2;
        int pillY = y + 146;
        g2.setColor(accentColor);
        g2.fillRoundRect(pillX, pillY, pillW, pillH, 22, 22);
        drawCenteredText(g2, primaryAction, 18, pillY + 30, Color.WHITE);

        drawCenteredText(g2, secondaryHint, 15, y + 210, new Color(90, 108, 130));

        g2.setTransform(oldTransform);
        g2.setComposite(oldComposite);
    }

    private void drawCenteredText(Graphics2D g2, String text, int size, int y, Color color) {
        g2.setColor(color);
        g2.setFont(new Font(UI_FONT, Font.BOLD, size));
        FontMetrics fm = g2.getFontMetrics();
        int x = (PANEL_WIDTH - fm.stringWidth(text)) / 2;
        g2.drawString(text, x, y);
    }

    private void trySetDirection(int nextX, int nextY) {
        if (isOpposite(nextX, nextY, dirX, dirY)) {
            return;
        }
        pendingDirX = nextX;
        pendingDirY = nextY;
    }

    private void setSpeedMode(SpeedMode newMode) {
        speedMode = newMode;
        updateDifficulty();
        repaint();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double easeOutCubic(double t) {
        double inv = 1.0 - t;
        return 1.0 - inv * inv * inv;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int key = e.getKeyCode();

        if (key == KeyEvent.VK_ENTER && (gameState == GameState.MENU || gameState == GameState.GAME_OVER)) {
            startGame();
            return;
        }

        if (key == KeyEvent.VK_P && (gameState == GameState.RUNNING || gameState == GameState.PAUSED)) {
            pauseOrResume();
            return;
        }

        if (key == KeyEvent.VK_1) {
            setSpeedMode(SpeedMode.SMOOTH);
            return;
        } else if (key == KeyEvent.VK_2) {
            setSpeedMode(SpeedMode.STANDARD);
            return;
        } else if (key == KeyEvent.VK_3) {
            setSpeedMode(SpeedMode.AGGRESSIVE);
            return;
        }

        if (gameState != GameState.RUNNING) {
            return;
        }

        if (key == KeyEvent.VK_UP || key == KeyEvent.VK_W) {
            trySetDirection(0, -1);
        } else if (key == KeyEvent.VK_DOWN || key == KeyEvent.VK_S) {
            trySetDirection(0, 1);
        } else if (key == KeyEvent.VK_LEFT || key == KeyEvent.VK_A) {
            trySetDirection(-1, 0);
        } else if (key == KeyEvent.VK_RIGHT || key == KeyEvent.VK_D) {
            trySetDirection(1, 0);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }
}
