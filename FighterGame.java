import java.io.*;
import java.util.*;
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
    static List<double[]> explosions    = Collections.synchronizedList(new ArrayList<>());
    static List<double[]> stars         = Collections.synchronizedList(new ArrayList<>());

    // Input state
    static boolean keyUp, keyDown, keyLeft, keyRight;

    // Frame buffer
    static char[][] buffer = new char[HEIGHT][WIDTH];

    // ANSI codes
    static final String RESET   = "\033[0m";
    static final String BOLD    = "\033[1m";
    static final String RED     = "\033[91m";
    static final String GREEN   = "\033[92m";
    static final String YELLOW  = "\033[93m";
    static final String MAGENTA = "\033[95m";
    static final String BLUE    = "\033[94m";
    static final String CYAN    = "\033[96m";
    static final String WHITE   = "\033[97m";
    static final String DIM     = "\033[2m";
    static final String CLEAR   = "\033[2J\033[H";
    static final String HIDE_CURSOR = "\033[?25l";
    static final String SHOW_CURSOR = "\033[?25h";
    static final String BG_DARK = "\033[48;5;235m";
    static final String BG_RST  = "\033[49m";

    // =========================================================
    //  Language System
    // =========================================================
    enum Lang { ZH, JA, KO }
    static Lang lang = Lang.ZH;

    // All UI strings: STR[stringId][langOrdinal]
    static final String[][] STR = {
        /* 0  game subtitle  */ { "★  終極戰機  ★",     "★  究極戦闘機  ★",      "★  최강 전투기  ★"    },
        /* 1  controls 1     */ { "↑↓←→ 移動  空格 射擊","↑↓←→ 移動  スペース 射撃","↑↓←→ 이동  스페이스 발사"},
        /* 2  controls 2     */ { "P 暫停  Q 離開",       "P 一時停止  Q 終了",      "P 일시정지  Q 종료"    },
        /* 3  press any key  */ { "按任意鍵開始...",       "何かキーを押して開始...", "아무 키를 눌러 시작..."},
        /* 4  hud score      */ { " ✦ 分數: ",            " ✦ スコア: ",            " ✦ 점수: "            },
        /* 5  hud hp         */ { " 生命: ",               " HP: ",                  " 생명: "              },
        /* 6  hud level      */ { " 關卡: ",               " レベル: ",              " 스테이지: "          },
        /* 7  hud kills      */ { "  擊殺: ",              "  撃墜: ",               "  격추: "             },
        /* 8  go score label */ { "最終分數: ",            "最終スコア: ",           "최종 점수: "          },
        /* 9  go restart hint*/ { "R 重新開始  Q 離開",    "R 再スタート  Q 終了",   "R 재시작  Q 종료"     },
        /* 10 goodbye        */ { "感謝遊玩！最終分數：",  "ありがとう！最終スコア：","감사합니다! 최종 점수："},
        /* 11 paused         */ { "── 暫停 ──",            "── 一時停止 ──",         "── 일시정지 ──"       },
    };

    static String t(int key) { return STR[key][lang.ordinal()]; }

    // =========================================================
    //  Language selection state
    // =========================================================
    static volatile boolean inLangSelect  = true;
    static volatile int     langCursor    = 0;
    static volatile boolean langConfirmed = false;

    // =========================================================
    //  Sprites
    // =========================================================
    static final String[] PLAYER_SPRITE = { " /|\\ ", "/=|=\\", " | | " };
    static final String[] ENEMY_SPRITE  = { " _^_ ", "(===)", " |~| " };
    static final String[] ENEMY2_SPRITE = { "->*<-", " [X] ", " | | " };
    static final String[] ENEMY3_SPRITE = { "\\###/", " [B] ", " ))) " };

    static Random rng = new Random();
    static long lastEnemySpawn = 0;
    static long spawnInterval  = 2000;
    static int  enemiesKilled  = 0;
    static boolean gameover = false;
    static boolean paused   = false;

    // =========================================================
    //  Main
    // =========================================================
    public static void main(String[] args) throws Exception {
        setRawMode(true);
        System.out.print(HIDE_CURSOR + CLEAR);

        Thread inputThread = new Thread(FighterGame::readInput);
        inputThread.setDaemon(true);
        inputThread.start();

        // --- Language selection ---
        showLangSelect();

        // --- Stars ---
        for (int i = 0; i < 30; i++)
            stars.add(new double[]{rng.nextInt(WIDTH), rng.nextInt(HEIGHT), rng.nextDouble()});

        // --- Title ---
        showTitle();

        // --- Game loop ---
        long lastTime = System.nanoTime();
        double accumulator = 0, dt = 1.0 / 30.0;

        while (running.get()) {
            if (paused) { Thread.sleep(100); continue; }
            long now = System.nanoTime();
            accumulator += (now - lastTime) / 1_000_000_000.0;
            lastTime = now;
            while (accumulator >= dt) { if (!gameover) update(dt); accumulator -= dt; }
            render();
            if (gameover) Thread.sleep(50);
        }

        setRawMode(false);
        System.out.print(SHOW_CURSOR + CLEAR);
        System.out.println(YELLOW + BOLD + t(10) + score + RESET);
    }

    // =========================================================
    //  Language Selection Screen
    // =========================================================
    static void showLangSelect() throws InterruptedException {
        // [label, native name, flag chars]
        String[][] opts = {
            { "繁體中文", "Traditional Chinese", "TW" },
            { "日本語",   "Japanese",            "JP" },
            { "한국어",   "Korean",              "KR" },
        };
        // Flag art (3 lines each, 5 chars wide)
        String[][] flags = {
            { " ╔═╗ ", " ║▓║ ", " ╚═╝ " },  // TW placeholder box
            { " ╔═╗ ", " ║○║ ", " ╚═╝ " },  // JP
            { " ╔═╗ ", " ║═║ ", " ╚═╝ " },  // KR
        };

        inLangSelect = true;
        langConfirmed = false;

        while (!langConfirmed && running.get()) {
            StringBuilder sb = new StringBuilder(CLEAR);

            // Outer border
            sb.append("\033[2;2H").append(CYAN).append(BOLD);
            sb.append("╔══════════════════════════════════════════════════════════════════════════════╗");
            for (int r = 3; r <= 21; r++) {
                sb.append("\033[").append(r).append(";2H║");
                sb.append("\033[").append(r).append(";79H║");
            }
            sb.append("\033[22;2H");
            sb.append("╚══════════════════════════════════════════════════════════════════════════════╝");
            sb.append(RESET);

            // Header
            sb.append("\033[4;1H");
            sb.append(centerStr(YELLOW + BOLD + "✈   SELECT LANGUAGE   ✈" + RESET, 80));
            sb.append("\033[5;1H");
            sb.append(centerStr(DIM + WHITE + "選擇語言  /  言語選択  /  언어 선택" + RESET, 80));
            sb.append("\033[6;1H");
            sb.append(centerStr(DIM + "─────────────────────────────────────" + RESET, 80));

            // Option boxes (centered, 3 options side by side)
            int[] boxCols = { 9, 31, 53 };
            int boxRow = 9;
            for (int i = 0; i < opts.length; i++) {
                boolean sel = (i == langCursor);
                String bc = sel ? (GREEN + BOLD) : (DIM + WHITE);
                String fill = sel ? BG_DARK : "";
                String fillEnd = sel ? BG_RST : "";
                int col = boxCols[i];

                // Top
                sb.append("\033[").append(boxRow).append(";").append(col).append("H");
                sb.append(bc).append("┌──────────────────┐").append(RESET);

                // Flag line
                sb.append("\033[").append(boxRow+1).append(";").append(col).append("H");
                sb.append(bc).append("│").append(fill);
                String flagColor = i==0?CYAN:(i==1?RED:BLUE);
                sb.append(centerPad(flagColor + BOLD + flags[i][1] + RESET + fill, 18, fill));
                sb.append(fillEnd).append(bc).append("│").append(RESET);

                // Native name
                sb.append("\033[").append(boxRow+2).append(";").append(col).append("H");
                sb.append(bc).append("│").append(fill);
                String nameColor = sel ? (YELLOW + BOLD) : WHITE;
                sb.append(centerPad(nameColor + opts[i][0] + RESET + fill, 18, fill));
                sb.append(fillEnd).append(bc).append("│").append(RESET);

                // English name
                sb.append("\033[").append(boxRow+3).append(";").append(col).append("H");
                sb.append(bc).append("│").append(fill);
                sb.append(centerPad(DIM + opts[i][1] + RESET + fill, 18, fill));
                sb.append(fillEnd).append(bc).append("│").append(RESET);

                // Selector arrow
                sb.append("\033[").append(boxRow+4).append(";").append(col).append("H");
                sb.append(bc).append("│").append(fill);
                String arrow = sel ? (GREEN + BOLD + "     ▶  選擇  ◀     " + RESET + fill) : "                  ";
                sb.append(centerPad(arrow, 18, fill));
                sb.append(fillEnd).append(bc).append("│").append(RESET);

                // Bottom
                sb.append("\033[").append(boxRow+5).append(";").append(col).append("H");
                sb.append(bc).append("└──────────────────┘").append(RESET);
            }

            // Navigation hint
            sb.append("\033[").append(boxRow+7).append(";1H");
            sb.append(centerStr(CYAN + "[ ← → ]  選擇語言" + RESET, 80));
            sb.append("\033[").append(boxRow+8).append(";1H");
            sb.append(centerStr(CYAN + "[ Enter / Space ]  確認" + RESET, 80));
            sb.append("\033[").append(boxRow+9).append(";1H");
            sb.append(centerStr(DIM + "[ Q ]  Quit / 離開 / 終了 / 종료" + RESET, 80));

            System.out.print(sb);
            System.out.flush();
            Thread.sleep(60);
        }

        lang = Lang.values()[langCursor];
        inLangSelect = false;
    }

    // Pad a string to exactly `width` visible chars (no ANSI), centering content
    static String centerPad(String s, int width, String bg) {
        String plain = s.replaceAll("\033\\[[^m]*m", "");
        int len = plain.length();
        int left = Math.max(0, (width - len) / 2);
        int right = Math.max(0, width - len - left);
        return " ".repeat(left) + s + " ".repeat(right);
    }

    static String centerStr(String s, int totalWidth) {
        String plain = s.replaceAll("\033\\[[^m]*m", "");
        int pad = Math.max(0, (totalWidth - plain.length()) / 2);
        return " ".repeat(pad) + s;
    }

    // =========================================================
    //  Title Screen
    // =========================================================
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
        moveCursor(11, 0); System.out.print(centerStr(WHITE + BOLD + t(0) + RESET, 80));
        moveCursor(13, 0); System.out.print(centerStr(DIM + t(1) + RESET, 80));
        moveCursor(14, 0); System.out.print(centerStr(DIM + t(2) + RESET, 80));
        moveCursor(16, 0); System.out.print(centerStr(YELLOW + t(3) + RESET, 80));
        System.out.flush();
        paused = true;
        Thread.sleep(300);
        while (paused) Thread.sleep(50);
    }

    // =========================================================
    //  Update
    // =========================================================
    static void update(double dt) {
        long now = System.currentTimeMillis();
        double speed = 20.0;
        if (keyLeft  && playerX > 3)         playerX -= speed * dt;
        if (keyRight && playerX < WIDTH - 4)  playerX += speed * dt;
        if (keyUp    && playerY > 2)          playerY -= speed * dt * 0.7;
        if (keyDown  && playerY < HEIGHT - 2) playerY += speed * dt * 0.7;

        if (shooting.getAndSet(false)) {
            playerBullets.add(new double[]{playerX,     playerY - 1});
            playerBullets.add(new double[]{playerX - 1, playerY - 1});
            playerBullets.add(new double[]{playerX + 1, playerY - 1});
        }

        synchronized (playerBullets) { playerBullets.removeIf(b -> { b[1] -= 30.0*dt; return b[1]<0; }); }
        synchronized (enemyBullets)  { enemyBullets.removeIf(b ->  { b[1] += 15.0*dt; return b[1]>=HEIGHT; }); }

        level = 1 + enemiesKilled / 5;
        spawnInterval = Math.max(800, 2000 - (level - 1) * 150L);
        if (now - lastEnemySpawn > spawnInterval && enemies.size() < 6 + level) {
            spawnEnemy(); lastEnemySpawn = now;
        }

        synchronized (enemies) {
            for (double[] e : enemies) {
                e[0] += e[2]*dt; e[1] += e[3]*dt;
                if (e[0]<3||e[0]>WIDTH-4)   e[2]=-e[2];
                if (e[1]<1)                  e[3]=Math.abs(e[3]);
                if (e[1]>HEIGHT/2.0)         e[3]=-Math.abs(e[3]);
                e[6] -= dt;
                if (e[6] <= 0) {
                    double sr = Math.max(1.5, 3.0 - level*0.3);
                    e[6] = sr + rng.nextDouble();
                    enemyBullets.add(new double[]{e[0], e[1]+2});
                    if (level >= 3) {
                        double ang = Math.atan2(playerY-e[1], playerX-e[0]);
                        enemyBullets.add(new double[]{e[0], e[1]+1, Math.cos(ang)*8, Math.sin(ang)*8, 1});
                    }
                }
            }
        }

        synchronized (enemyBullets) {
            for (double[] b : enemyBullets)
                if (b.length >= 5 && b[4]==1) { b[0]+=b[2]*dt; b[1]+=b[3]*dt; }
            enemyBullets.removeIf(b -> b[0]<0||b[0]>=WIDTH||b[1]<0||b[1]>=HEIGHT);
        }

        synchronized (stars) {
            for (double[] s : stars) {
                s[1] += (s[2]*5+1)*dt;
                if (s[1]>=HEIGHT) { s[1]=0; s[0]=rng.nextInt(WIDTH); }
            }
        }

        synchronized (explosions) { explosions.removeIf(exp -> { exp[2]-=dt; return exp[2]<=0; }); }
        checkCollisions();
    }

    static void spawnEnemy() {
        int type = rng.nextInt(Math.min(3, level));
        double x = 3+rng.nextInt(WIDTH-6), y = 1+rng.nextInt(3);
        double vx = (rng.nextDouble()*6+3)*(rng.nextBoolean()?1:-1), vy = rng.nextDouble()*2+1;
        enemies.add(new double[]{x, y, vx, vy, type, type+1, 2.0+rng.nextDouble()*2});
    }

    static void checkCollisions() {
        List<double[]> rmB=new ArrayList<>(), rmE=new ArrayList<>();
        synchronized (playerBullets) {
            synchronized (enemies) {
                for (double[] b : playerBullets)
                    for (double[] e : enemies)
                        if (Math.abs(b[0]-e[0])<3 && Math.abs(b[1]-e[1])<2) {
                            rmB.add(b); e[5]--;
                            if (e[5]<=0) { rmE.add(e); explosions.add(new double[]{e[0],e[1],0.5});
                                           score+=(int)(e[4]+1)*10*level; enemiesKilled++; }
                        }
                playerBullets.removeAll(rmB); enemies.removeAll(rmE);
            }
        }
        synchronized (enemyBullets) {
            enemyBullets.removeIf(b -> {
                if (Math.abs(b[0]-playerX)<2 && Math.abs(b[1]-playerY)<2) {
                    playerHP--; explosions.add(new double[]{playerX,playerY,0.3});
                    if (playerHP<=0) { gameover=true; explosions.add(new double[]{playerX,playerY,2.0}); }
                    return true;
                } return false;
            });
        }
        synchronized (enemies) {
            enemies.removeIf(e -> {
                if (Math.abs(e[0]-playerX)<3 && Math.abs(e[1]-playerY)<2) {
                    playerHP-=2; explosions.add(new double[]{e[0],e[1],0.5}); explosions.add(new double[]{playerX,playerY,0.5});
                    if (playerHP<=0) gameover=true; return true;
                } return false;
            });
        }
    }

    // =========================================================
    //  Render
    // =========================================================
    static void render() {
        for (int y=0;y<HEIGHT;y++) Arrays.fill(buffer[y],' ');
        StringBuilder sb = new StringBuilder(CLEAR);

        synchronized (stars) {
            for (double[] s : stars) { int sx=(int)s[0],sy=(int)s[1]; if(inBounds(sx,sy)) buffer[sy][sx]=s[2]>0.7?'*':(s[2]>0.4?'.':'·'); }
        }
        synchronized (explosions) {
            String[] ec={"*","#","@","!","+"};
            for (double[] exp : explosions) { int ex=(int)exp[0],ey=(int)exp[1];
                for(int dy=-1;dy<=1;dy++) for(int dx=-2;dx<=2;dx++)
                    if(inBounds(ex+dx,ey+dy)&&rng.nextBoolean()) buffer[ey+dy][ex+dx]=ec[rng.nextInt(ec.length)].charAt(0); }
        }
        synchronized (enemies) {
            for (double[] e : enemies) { int t=(int)e[4]; drawSprite(t==0?ENEMY_SPRITE:(t==1?ENEMY2_SPRITE:ENEMY3_SPRITE),(int)e[0]-2,(int)e[1]-1); }
        }
        synchronized (enemyBullets)  { for(double[] b:enemyBullets)  { int bx=(int)b[0],by=(int)b[1]; if(inBounds(bx,by)) buffer[by][bx]='▼'; } }
        synchronized (playerBullets) { for(double[] b:playerBullets) { int bx=(int)b[0],by=(int)b[1]; if(inBounds(bx,by)) buffer[by][bx]='|'; } }
        if (!gameover) drawSprite(PLAYER_SPRITE,(int)playerX-2,(int)playerY-1);

        for (int y=0;y<HEIGHT;y++) {
            sb.append("\033[").append(y+1).append(";1H");
            for (int x=0;x<WIDTH;x++) {
                char c=buffer[y][x];
                if      (c==' ')                  sb.append(' ');
                else if (c=='*'||c=='.'||c=='·')  sb.append(DIM).append(WHITE).append(c).append(RESET);
                else if (c=='|')                  sb.append(CYAN).append(BOLD).append('↑').append(RESET);
                else if (c=='▼')                  sb.append(RED).append('▼').append(RESET);
                else if (isExplosion(x,y))        sb.append(YELLOW).append(BOLD).append(c).append(RESET);
                else if (isEnemy(x,y))            sb.append(RED).append(c).append(RESET);
                else if (isPlayer(x,y))           sb.append(GREEN).append(BOLD).append(c).append(RESET);
                else                              sb.append(c);
            }
        }

        // HUD border
        sb.append("\033[1;1H").append(BOLD).append(CYAN)
          .append("╔══════════════════════════════════════════════════════════════════════════════╗").append(RESET);
        sb.append("\033[").append(HEIGHT).append(";1H").append(BOLD).append(CYAN)
          .append("╚══════════════════════════════════════════════════════════════════════════════╝").append(RESET);

        sb.append("\033[1;2H").append(YELLOW).append(BOLD).append(t(4)).append(String.format("%06d",score)).append(RESET);
        String hpBar="♥".repeat(Math.max(0,playerHP))+"♡".repeat(Math.max(0,5-playerHP));
        sb.append("\033[1;30H").append(RED).append(BOLD).append(t(5)).append(hpBar).append(RESET);
        sb.append("\033[1;55H").append(MAGENTA).append(BOLD).append(t(6)).append(level).append(t(7)).append(enemiesKilled).append(" ").append(RESET);

        if (paused && !gameover) {
            sb.append("\033[").append(HEIGHT/2).append(";1H");
            sb.append(centerStr(YELLOW+BOLD+t(11)+RESET, 80));
        }
        if (gameover) {
            int cy=HEIGHT/2;
            sb.append("\033[").append(cy-1).append(";20H").append(RED).append(BOLD).append("★ ★ ★  GAME OVER  ★ ★ ★").append(RESET);
            sb.append("\033[").append(cy).append(";22H").append(YELLOW).append(t(8)).append(score).append(RESET);
            sb.append("\033[").append(cy+1).append(";1H").append(centerStr(WHITE+t(9)+RESET,80));
        }

        System.out.print(sb); System.out.flush();
    }

    static boolean isExplosion(int x,int y){ return explosions.stream().anyMatch(e->Math.abs(e[0]-x)<=2&&Math.abs(e[1]-y)<=1); }
    static boolean isEnemy(int x,int y)    { return enemies.stream().anyMatch(e->Math.abs(e[0]-x)<=2&&Math.abs(e[1]-y)<=1); }
    static boolean isPlayer(int x,int y)   { return Math.abs(playerX-x)<=2&&Math.abs(playerY-y)<=1; }

    static void drawSprite(String[] sp, int sx, int sy) {
        for (int dy=0;dy<sp.length;dy++) { String row=sp[dy];
            for (int dx=0;dx<row.length();dx++) { int px=sx+dx,py=sy+dy;
                if (inBounds(px,py)&&row.charAt(dx)!=' ') buffer[py][px]=row.charAt(dx); } }
    }
    static boolean inBounds(int x,int y){ return x>=0&&x<WIDTH&&y>=1&&y<HEIGHT-1; }
    static void moveCursor(int r,int c){ System.out.printf("\033[%d;%dH",r,c); }

    // =========================================================
    //  Input
    // =========================================================
    static void readInput() {
        try {
            InputStream in = System.in;
            while (running.get()) {
                if (in.available()==0) { Thread.sleep(10); continue; }
                int c = in.read();

                // --- Language select ---
                if (inLangSelect) {
                    if (c==27 && in.available()>=2) {
                        int b1=in.read(), b2=in.read();
                        if (b1=='[') {
                            if (b2=='D') langCursor=(langCursor+2)%3; // ←
                            if (b2=='C') langCursor=(langCursor+1)%3; // →
                            if (b2=='A') langCursor=(langCursor+2)%3; // ↑ same as ←
                            if (b2=='B') langCursor=(langCursor+1)%3; // ↓ same as →
                        }
                    } else if (c=='\n'||c=='\r'||c==' ') {
                        langConfirmed=true;
                    } else if (c=='q'||c=='Q') {
                        running.set(false); langConfirmed=true;
                    }
                    continue;
                }

                // --- Title screen (paused=true, not gameover) ---
                if (paused && !gameover) {
                    if (c!='p'&&c!='P') paused=false;
                }

                // --- Game inputs ---
                if (c=='q'||c=='Q') running.set(false);
                if ((c=='r'||c=='R') && gameover) resetGame();
                if (c=='p'||c=='P') paused=!paused;
                if (c==' ') shooting.set(true);
                if (c==27 && in.available()>=2) {
                    int b1=in.read(), b2=in.read();
                    if (b1=='[') switch(b2) {
                        case 'A': keyUp=true;   keyDown=false;  break;
                        case 'B': keyDown=true;  keyUp=false;   break;
                        case 'C': keyRight=true; keyLeft=false; break;
                        case 'D': keyLeft=true;  keyRight=false;break;
                    }
                } else if (c!=' ') {
                    new Thread(()->{try{Thread.sleep(80);}catch(Exception e){} keyUp=keyDown=keyLeft=keyRight=false;}).start();
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    // =========================================================
    //  Reset
    // =========================================================
    static void resetGame() {
        playerX=WIDTH/2.0; playerY=HEIGHT-3; playerHP=5; score=0; level=1; enemiesKilled=0;
        playerBullets.clear(); enemyBullets.clear(); enemies.clear(); explosions.clear();
        gameover=false; lastEnemySpawn=0;
    }

    // =========================================================
    //  Raw mode
    // =========================================================
    static void setRawMode(boolean raw) throws Exception {
        String[] cmd = raw ? new String[]{"sh","-c","stty -echo raw </dev/tty"}
                           : new String[]{"sh","-c","stty echo sane </dev/tty"};
        Runtime.getRuntime().exec(cmd).waitFor();
    }
}
