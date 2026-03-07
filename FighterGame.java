import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class FighterGame {

    // Terminal dimensions
    static final int WIDTH = 80;
    static final int HEIGHT = 24;

    // Game state
    static AtomicBoolean running = new AtomicBoolean(true);
    static AtomicBoolean shooting = new AtomicBoolean(false);

    // Player
    static double playerX = WIDTH / 2.0;
    static double playerY = HEIGHT - 3;
    static int playerHP = 5;
    static int score = 0;
    static int level = 1;

    // Lists
    static List<double[]> playerBullets = Collections.synchronizedList(new ArrayList<>());
    static List<double[]> enemyBullets  = Collections.synchronizedList(new ArrayList<>());
    static List<double[]> enemies       = Collections.synchronizedList(new ArrayList<>());
    static List<double[]> explosions    = Collections.synchronizedList(new ArrayList<>()); // x,y,timer
    static List<double[]> stars         = Collections.synchronizedList(new ArrayList<>());

    // Input state
    static boolean keyUp, keyDown, keyLeft, keyRight;

    // Frame buffer
    static char[][] buffer = new char[HEIGHT][WIDTH];
    static String[] colorBuffer = new String[HEIGHT * WIDTH];

    // ANSI codes
    static final String RESET  = "\033[0m";
    static final String BOLD   = "\033[1m";
    static final String RED    = "\033[91m";
    static final String GREEN  = "\033[92m";
    static final String YELLOW = "\033[93m";
    static final String BLUE   = "\033[94m";
    static final String MAGENTA= "\033[95m";
    static final String CYAN   = "\033[96m";
    static final String WHITE  = "\033[97m";
    static final String DIM    = "\033[2m";
    static final String CLEAR  = "\033[2J\033[H";
    static final String HIDE_CURSOR = "\033[?25l";
    static final String SHOW_CURSOR = "\033[?25h";

    // Sprites
    static final String[] PLAYER_SPRITE = {
        " /|\\ ",
        "/=|=\\",
        " | | "
    };

    static final String[] ENEMY_SPRITE = {
        " _^_ ",
        "(===)",
        " |~| "
    };

    static final String[] ENEMY2_SPRITE = {
        "->*<-",
        " [X] ",
        " | | "
    };

    static final String[] ENEMY3_SPRITE = {
        "\\###/",
        " [B] ",
        " ))) "
    };

    static Random rng = new Random();
    static long gameStartTime = System.currentTimeMillis();
    static long lastEnemySpawn = 0;
    static long spawnInterval = 2000;
    static int enemiesKilled = 0;
    static boolean gameover = false;
    static boolean paused = false;

    public static void main(String[] args) throws Exception {
        // Setup terminal raw mode
        setRawMode(true);
        System.out.print(HIDE_CURSOR);
        System.out.print(CLEAR);

        // Init stars background
        for (int i = 0; i < 30; i++) {
            stars.add(new double[]{rng.nextInt(WIDTH), rng.nextInt(HEIGHT), rng.nextDouble()});
        }

        // Input thread
        Thread inputThread = new Thread(FighterGame::readInput);
        inputThread.setDaemon(true);
        inputThread.start();

        showTitle();

        // Main game loop
        long lastTime = System.nanoTime();
        double accumulator = 0;
        double dt = 1.0 / 30.0; // 30 FPS

        while (running.get()) {
            if (paused) {
                Thread.sleep(100);
                continue;
            }

            long now = System.nanoTime();
            accumulator += (now - lastTime) / 1_000_000_000.0;
            lastTime = now;

            while (accumulator >= dt) {
                if (!gameover) {
                    update(dt);
                }
                accumulator -= dt;
            }

            render();

            if (gameover) {
                Thread.sleep(50);
            }
        }

        setRawMode(false);
        System.out.print(SHOW_CURSOR);
        System.out.print(CLEAR);
        System.out.println(YELLOW + BOLD + "感謝遊玩！最終分數：" + score + RESET);
    }

    static void showTitle() throws InterruptedException {
        System.out.print(CLEAR);
        String[] title = {
            "  ███████╗██╗ ██████╗ ██╗  ██╗████████╗███████╗██████╗  ",
            "  ██╔════╝██║██╔════╝ ██║  ██║╚══██╔══╝██╔════╝██╔══██╗ ",
            "  █████╗  ██║██║  ███╗███████║   ██║   █████╗  ██████╔╝ ",
            "  ██╔══╝  ██║██║   ██║██╔══██║   ██║   ██╔══╝  ██╔══██╗ ",
            "  ██║     ██║╚██████╔╝██║  ██║   ██║   ███████╗██║  ██║ ",
            "  ╚═╝     ╚═╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝ "
        };
        int row = 3;
        for (String line : title) {
            moveCursor(row++, (WIDTH - line.length()) / 2 + 1);
            System.out.print(CYAN + BOLD + line + RESET);
        }
        moveCursor(11, 25); System.out.print(WHITE + "★  終極戰機  ★" + RESET);
        moveCursor(13, 22); System.out.print(DIM + "↑↓←→ 移動    空格 射擊" + RESET);
        moveCursor(14, 22); System.out.print(DIM + "P 暫停       Q 離開" + RESET);
        moveCursor(16, 27); System.out.print(YELLOW + "按任意鍵開始..." + RESET);
        System.out.flush();
        paused = true;
        Thread.sleep(300);
        // Wait for keypress - handled by input thread setting paused=false on any key
        while (paused) Thread.sleep(50);
    }

    static void update(double dt) {
        long now = System.currentTimeMillis();

        // Move player
        double speed = 20.0;
        if (keyLeft  && playerX > 3)         playerX -= speed * dt;
        if (keyRight && playerX < WIDTH - 4)  playerX += speed * dt;
        if (keyUp    && playerY > 2)          playerY -= speed * dt * 0.7;
        if (keyDown  && playerY < HEIGHT - 2) playerY += speed * dt * 0.7;

        // Player shoot
        if (shooting.getAndSet(false)) {
            playerBullets.add(new double[]{playerX, playerY - 1});
            playerBullets.add(new double[]{playerX - 1, playerY - 1});
            playerBullets.add(new double[]{playerX + 1, playerY - 1});
        }

        // Move player bullets up
        synchronized (playerBullets) {
            playerBullets.removeIf(b -> {
                b[1] -= 30.0 * dt;
                return b[1] < 0;
            });
        }

        // Move enemy bullets down
        synchronized (enemyBullets) {
            enemyBullets.removeIf(b -> {
                b[1] += 15.0 * dt;
                return b[1] >= HEIGHT;
            });
        }

        // Spawn enemies
        level = 1 + enemiesKilled / 5;
        spawnInterval = Math.max(800, 2000 - (level - 1) * 150L);
        if (now - lastEnemySpawn > spawnInterval && enemies.size() < 6 + level) {
            spawnEnemy();
            lastEnemySpawn = now;
        }

        // Move enemies
        synchronized (enemies) {
            for (double[] e : enemies) {
                // e: x, y, vx, vy, type, hp, shootTimer
                e[0] += e[2] * dt;
                e[1] += e[3] * dt;

                // Bounce off walls
                if (e[0] < 3 || e[0] > WIDTH - 4) e[2] = -e[2];
                // Keep in top half
                if (e[1] < 1) e[3] = Math.abs(e[3]);
                if (e[1] > HEIGHT / 2.0) e[3] = -Math.abs(e[3]);

                // Enemy shooting
                e[6] -= dt;
                if (e[6] <= 0) {
                    double shootRate = Math.max(1.5, 3.0 - level * 0.3);
                    e[6] = shootRate + rng.nextDouble();
                    enemyBullets.add(new double[]{e[0], e[1] + 2});
                    if (level >= 3) {
                        // Aimed shot
                        double angle = Math.atan2(playerY - e[1], playerX - e[0]);
                        enemyBullets.add(new double[]{e[0], e[1] + 1, 
                            Math.cos(angle) * 8, Math.sin(angle) * 8, 1}); // aimed
                    }
                }
            }
        }

        // Move aimed bullets
        synchronized (enemyBullets) {
            for (double[] b : enemyBullets) {
                if (b.length >= 5 && b[4] == 1) {
                    b[0] += b[2] * dt;
                    b[1] += b[3] * dt;
                }
            }
            enemyBullets.removeIf(b -> b[0] < 0 || b[0] >= WIDTH || b[1] < 0 || b[1] >= HEIGHT);
        }

        // Scroll stars
        synchronized (stars) {
            for (double[] s : stars) {
                s[1] += (s[2] * 5 + 1) * dt;
                if (s[1] >= HEIGHT) {
                    s[1] = 0;
                    s[0] = rng.nextInt(WIDTH);
                }
            }
        }

        // Decay explosions
        synchronized (explosions) {
            explosions.removeIf(exp -> {
                exp[2] -= dt;
                return exp[2] <= 0;
            });
        }

        checkCollisions();
    }

    static void spawnEnemy() {
        int type = rng.nextInt(Math.min(3, level));
        double x = 3 + rng.nextInt(WIDTH - 6);
        double y = 1 + rng.nextInt(3);
        double vx = (rng.nextDouble() * 6 + 3) * (rng.nextBoolean() ? 1 : -1);
        double vy = rng.nextDouble() * 2 + 1;
        int hp = type + 1;
        enemies.add(new double[]{x, y, vx, vy, type, hp, 2.0 + rng.nextDouble() * 2});
    }

    static void checkCollisions() {
        // Player bullets hit enemies
        List<double[]> toRemoveBullets = new ArrayList<>();
        List<double[]> toRemoveEnemies = new ArrayList<>();

        synchronized (playerBullets) {
            synchronized (enemies) {
                for (double[] b : playerBullets) {
                    for (double[] e : enemies) {
                        if (Math.abs(b[0] - e[0]) < 3 && Math.abs(b[1] - e[1]) < 2) {
                            toRemoveBullets.add(b);
                            e[5]--;
                            if (e[5] <= 0) {
                                toRemoveEnemies.add(e);
                                explosions.add(new double[]{e[0], e[1], 0.5});
                                score += (int)(e[4] + 1) * 10 * level;
                                enemiesKilled++;
                            }
                        }
                    }
                }
                playerBullets.removeAll(toRemoveBullets);
                enemies.removeAll(toRemoveEnemies);
            }
        }

        // Enemy bullets hit player
        synchronized (enemyBullets) {
            enemyBullets.removeIf(b -> {
                if (Math.abs(b[0] - playerX) < 2 && Math.abs(b[1] - playerY) < 2) {
                    playerHP--;
                    explosions.add(new double[]{playerX, playerY, 0.3});
                    if (playerHP <= 0) {
                        gameover = true;
                        explosions.add(new double[]{playerX, playerY, 2.0});
                    }
                    return true;
                }
                return false;
            });
        }

        // Enemy collide with player
        synchronized (enemies) {
            enemies.removeIf(e -> {
                if (Math.abs(e[0] - playerX) < 3 && Math.abs(e[1] - playerY) < 2) {
                    playerHP -= 2;
                    explosions.add(new double[]{e[0], e[1], 0.5});
                    explosions.add(new double[]{playerX, playerY, 0.5});
                    if (playerHP <= 0) gameover = true;
                    return true;
                }
                return false;
            });
        }
    }

    static void render() {
        // Clear buffer
        for (int y = 0; y < HEIGHT; y++) Arrays.fill(buffer[y], ' ');

        StringBuilder sb = new StringBuilder();
        sb.append(CLEAR);

        // Draw stars
        synchronized (stars) {
            for (double[] s : stars) {
                int sx = (int)s[0], sy = (int)s[1];
                if (inBounds(sx, sy)) {
                    char c = s[2] > 0.7 ? '*' : (s[2] > 0.4 ? '.' : '·');
                    buffer[sy][sx] = c;
                }
            }
        }

        // Draw explosions
        synchronized (explosions) {
            String[] expChars = {"*", "#", "@", "!", "+"};
            for (double[] exp : explosions) {
                int ex = (int)exp[0], ey = (int)exp[1];
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (inBounds(ex+dx, ey+dy) && rng.nextBoolean()) {
                            buffer[ey+dy][ex+dx] = expChars[rng.nextInt(expChars.length)].charAt(0);
                        }
                    }
                }
            }
        }

        // Draw enemies
        synchronized (enemies) {
            for (double[] e : enemies) {
                int ex = (int)e[0], ey = (int)e[1];
                int type = (int)e[4];
                String[] sprite = type == 0 ? ENEMY_SPRITE : (type == 1 ? ENEMY2_SPRITE : ENEMY3_SPRITE);
                drawSprite(sprite, ex - 2, ey - 1);
            }
        }

        // Draw enemy bullets
        synchronized (enemyBullets) {
            for (double[] b : enemyBullets) {
                int bx = (int)b[0], by = (int)b[1];
                if (inBounds(bx, by)) buffer[by][bx] = '▼';
            }
        }

        // Draw player bullets
        synchronized (playerBullets) {
            for (double[] b : playerBullets) {
                int bx = (int)b[0], by = (int)b[1];
                if (inBounds(bx, by)) buffer[by][bx] = '|';
            }
        }

        // Draw player (if alive)
        if (!gameover || explosions.stream().anyMatch(e -> e[2] > 1.0)) {
            if (!gameover) {
                drawSprite(PLAYER_SPRITE, (int)playerX - 2, (int)playerY - 1);
            }
        }

        // Build output with colors
        for (int y = 0; y < HEIGHT; y++) {
            sb.append("\033[").append(y+1).append(";1H");
            for (int x = 0; x < WIDTH; x++) {
                char c = buffer[y][x];
                if (c == ' ') {
                    sb.append(' ');
                } else if (c == '*' || c == '.' || c == '·') {
                    // Stars
                    sb.append(DIM).append(WHITE).append(c).append(RESET);
                } else if (c == '|') {
                    // Player bullet
                    sb.append(CYAN).append(BOLD).append('↑').append(RESET);
                } else if (c == '▼') {
                    // Enemy bullet
                    sb.append(RED).append('▼').append(RESET);
                } else if (isExplosion(x, y)) {
                    sb.append(YELLOW).append(BOLD).append(c).append(RESET);
                } else if (isEnemy(x, y)) {
                    sb.append(RED).append(c).append(RESET);
                } else if (isPlayer(x, y)) {
                    sb.append(GREEN).append(BOLD).append(c).append(RESET);
                } else {
                    sb.append(c);
                }
            }
        }

        // HUD
        sb.append("\033[1;1H");
        sb.append(BOLD).append(CYAN);
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗");
        sb.append(RESET);

        sb.append("\033[").append(HEIGHT).append(";1H");
        sb.append(BOLD).append(CYAN);
        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝");
        sb.append(RESET);

        // Score / HP / Level
        sb.append("\033[1;2H");
        sb.append(YELLOW).append(BOLD).append(" ✦ 分數: ").append(String.format("%06d", score)).append(RESET);

        String hpBar = "♥".repeat(Math.max(0, playerHP)) + "♡".repeat(Math.max(0, 5 - playerHP));
        sb.append("\033[1;30H");
        sb.append(RED).append(BOLD).append(" 生命: ").append(hpBar).append(RESET);

        sb.append("\033[1;55H");
        sb.append(MAGENTA).append(BOLD).append(" 關卡: ").append(level).append("  擊殺: ").append(enemiesKilled).append(" ").append(RESET);

        // Gameover overlay
        if (gameover) {
            int cy = HEIGHT / 2;
            sb.append("\033[").append(cy-1).append(";20H");
            sb.append(RED).append(BOLD).append("★ ★ ★  GAME OVER  ★ ★ ★").append(RESET);
            sb.append("\033[").append(cy).append(";22H");
            sb.append(YELLOW).append("最終分數: ").append(score).append(RESET);
            sb.append("\033[").append(cy+1).append(";21H");
            sb.append(WHITE).append("按 R 重新開始  Q 離開").append(RESET);
        }

        System.out.print(sb);
        System.out.flush();
    }

    static boolean isExplosion(int x, int y) {
        return explosions.stream().anyMatch(e -> Math.abs(e[0]-x) <= 2 && Math.abs(e[1]-y) <= 1);
    }

    static boolean isEnemy(int x, int y) {
        return enemies.stream().anyMatch(e -> Math.abs(e[0]-x) <= 2 && Math.abs(e[1]-y) <= 1);
    }

    static boolean isPlayer(int x, int y) {
        return Math.abs(playerX-x) <= 2 && Math.abs(playerY-y) <= 1;
    }

    static void drawSprite(String[] sprite, int startX, int startY) {
        for (int dy = 0; dy < sprite.length; dy++) {
            String row = sprite[dy];
            for (int dx = 0; dx < row.length(); dx++) {
                int px = startX + dx, py = startY + dy;
                if (inBounds(px, py) && row.charAt(dx) != ' ') {
                    buffer[py][px] = row.charAt(dx);
                }
            }
        }
    }

    static boolean inBounds(int x, int y) {
        return x >= 0 && x < WIDTH && y >= 1 && y < HEIGHT - 1;
    }

    static void moveCursor(int row, int col) {
        System.out.printf("\033[%d;%dH", row, col);
    }

    static void readInput() {
        try {
            InputStream in = System.in;
            while (running.get()) {
                if (in.available() == 0) {
                    Thread.sleep(10);
                    continue;
                }
                int c = in.read();

                if (paused) {
                    paused = false;
                    continue;
                }

                if (c == 'q' || c == 'Q') {
                    running.set(false);
                }
                if (c == 'r' || c == 'R') {
                    if (gameover) resetGame();
                }
                if (c == 'p' || c == 'P') {
                    paused = !paused;
                }
                if (c == ' ') {
                    shooting.set(true);
                }
                if (c == 27) { // ESC sequence
                    if (in.available() >= 2) {
                        int b1 = in.read();
                        int b2 = in.read();
                        if (b1 == '[') {
                            switch (b2) {
                                case 'A': keyUp = true; keyDown = false; break;    // ↑
                                case 'B': keyDown = true; keyUp = false; break;    // ↓
                                case 'C': keyRight = true; keyLeft = false; break; // →
                                case 'D': keyLeft = true; keyRight = false; break; // ←
                            }
                        }
                    }
                } else {
                    // Key release simulation: reset direction after short delay
                    if (c != ' ') {
                        new Thread(() -> {
                            try { Thread.sleep(80); } catch (Exception e) {}
                            keyUp = keyDown = keyLeft = keyRight = false;
                        }).start();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    static void resetGame() {
        playerX = WIDTH / 2.0;
        playerY = HEIGHT - 3;
        playerHP = 5;
        score = 0;
        level = 1;
        enemiesKilled = 0;
        playerBullets.clear();
        enemyBullets.clear();
        enemies.clear();
        explosions.clear();
        gameover = false;
        lastEnemySpawn = 0;
    }

    // ---- Raw terminal mode via stty ----
    static Process sttyProcess;

    static void setRawMode(boolean raw) throws Exception {
        String[] cmd = raw
            ? new String[]{"sh", "-c", "stty -echo raw </dev/tty"}
            : new String[]{"sh", "-c", "stty echo sane </dev/tty"};
        Runtime.getRuntime().exec(cmd).waitFor();
    }
}
