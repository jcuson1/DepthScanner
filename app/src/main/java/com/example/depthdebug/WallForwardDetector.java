package com.example.depthdebug;

public final class WallForwardDetector {

    public static final class State {
        public final boolean forwardOn;
        public final boolean leftWallOn;
        public final boolean rightWallOn;

        public final float forwardDistM;
        public final float leftDistM;
        public final float rightDistM;

        State(boolean f, boolean l, boolean r, float fd, float ld, float rd) {
            forwardOn = f; leftWallOn = l; rightWallOn = r;
            forwardDistM = fd; leftDistM = ld; rightDistM = rd;
        }
    }

    // Tunables
    private static final float MIN_TILE_VALID_RATIO = 0.25f;

    private static final float FORWARD_DIST_M = 2.2f;
    private static final float WALL_DIST_M = 1.4f;

    private static final float FRACTION_CLOSE = 0.30f;
    private static final int MIN_VALID_TILES = 10;

    private final ScoreIntegrator forwardScore = new ScoreIntegrator(20, 8, 3, 2, 1);
    private final ScoreIntegrator leftScore    = new ScoreIntegrator(20, 8, 3, 2, 1);
    private final ScoreIntegrator rightScore   = new ScoreIntegrator(20, 8, 3, 2, 1);

    public void reset() {
        forwardScore.reset();
        leftScore.reset();
        rightScore.reset();
    }

    public State update(RawDepthTileAnalyzer.Grid g) {
        ZoneResult f = evalForward(g);
        ZoneResult l = evalLeftWall(g);
        ZoneResult r = evalRightWall(g);

        boolean fOn = forwardScore.update(f.dangerNow);
        boolean lOn = leftScore.update(l.dangerNow);
        boolean rOn = rightScore.update(r.dangerNow);

        return new State(fOn, lOn, rOn, f.distM, l.distM, r.distM);
    }

    private static final class ZoneResult {
        final boolean dangerNow;
        final float distM;
        ZoneResult(boolean d, float m) { dangerNow = d; distM = m; }
    }

    // Forward = центральные 40% по ширине и средние 60% по высоте
    private ZoneResult evalForward(RawDepthTileAnalyzer.Grid g) {
        int c0 = (int)Math.floor(g.cols * 0.35);
        int c1 = (int)Math.ceil (g.cols * 0.65);
        int r0 = (int)Math.floor(g.rows * 0.25);
        int r1 = (int)Math.ceil (g.rows * 0.80);

        return evalZone(g, c0, c1, r0, r1, FORWARD_DIST_M);
    }

    private ZoneResult evalLeftWall(RawDepthTileAnalyzer.Grid g) {
        int c0 = 0;
        int c1 = Math.max(1, (int)Math.ceil(g.cols * 0.25));
        int r0 = (int)Math.floor(g.rows * 0.25);
        int r1 = (int)Math.ceil (g.rows * 0.80);

        return evalZone(g, c0, c1, r0, r1, WALL_DIST_M);
    }

    private ZoneResult evalRightWall(RawDepthTileAnalyzer.Grid g) {
        int c0 = Math.max(0, (int)Math.floor(g.cols * 0.75));
        int c1 = g.cols;
        int r0 = (int)Math.floor(g.rows * 0.25);
        int r1 = (int)Math.ceil (g.rows * 0.80);

        return evalZone(g, c0, c1, r0, r1, WALL_DIST_M);
    }

    private ZoneResult evalZone(RawDepthTileAnalyzer.Grid g, int c0, int c1, int r0, int r1, float distThM) {
        int valid = 0;
        int close = 0;
        float best = Float.POSITIVE_INFINITY;

        for (int r = r0; r < r1; r++) {
            for (int c = c0; c < c1; c++) {
                int i = g.idx(c, r);
                if (g.validRatio[i] < MIN_TILE_VALID_RATIO) continue;

                valid++;
                float d = g.p20m[i];
                if (d < best) best = d;
                if (d < distThM) close++;
            }
        }

        boolean dangerNow = (valid >= MIN_VALID_TILES) && ((close / (float)valid) >= FRACTION_CLOSE);
        return new ZoneResult(dangerNow, best);
    }
}