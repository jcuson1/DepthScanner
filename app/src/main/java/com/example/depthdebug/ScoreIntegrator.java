package com.example.depthdebug;

public final class ScoreIntegrator {
    private final int maxScore;
    private final int onTh;
    private final int offTh;
    private final int inc;
    private final int dec;

    private int score = 0;
    private boolean on = false;

    public ScoreIntegrator(int maxScore, int onTh, int offTh, int inc, int dec) {
        this.maxScore = maxScore;
        this.onTh = onTh;
        this.offTh = offTh;
        this.inc = inc;
        this.dec = dec;
    }

    public void reset() {
        score = 0;
        on = false;
    }

    public boolean update(boolean dangerNow) {
        if (dangerNow) score += inc;
        else score -= dec;

        if (score < 0) score = 0;
        if (score > maxScore) score = maxScore;

        if (!on && score >= onTh) on = true;
        if (on && score <= offTh) on = false;

        return on;
    }

    public int getScore() { return score; }
    public boolean isOn() { return on; }
}