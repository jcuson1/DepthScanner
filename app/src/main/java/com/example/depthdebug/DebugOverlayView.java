package com.example.depthdebug;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.HashSet;

public class DebugOverlayView extends View {

    private final Paint gridPaint = new Paint();
    private final Paint hotPaint = new Paint();
    private final Paint textPaint = new Paint();

    private final HashSet<Integer> hot = new HashSet<>();
    private HazardDetector.Severity severity = HazardDetector.Severity.FAR;

    public DebugOverlayView(Context context) {
        super(context);
        init();
    }

    public DebugOverlayView(Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(4f);
        gridPaint.setARGB(180, 255, 255, 255);

        hotPaint.setStyle(Paint.Style.FILL);

        textPaint.setARGB(220, 255, 255, 255);
        textPaint.setTextSize(42f);
        textPaint.setAntiAlias(true);
    }

    public void setHotCells(int[] cells, HazardDetector.Severity sev) {
        hot.clear();
        if (cells != null) {
            for (int c : cells) hot.add(c);
        }
        severity = (sev == null) ? HazardDetector.Severity.FAR : sev;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        // Grid 3x5 (cols x rows)
        float cw = w / 3f;
        float ch = h / 5f;

        for (int cell = 0; cell < 15; cell++) {
            if (!hot.contains(cell)) continue;

            int row = cell / 3;
            int col = cell % 3;

            float left = col * cw;
            float top = row * ch;
            float right = left + cw;
            float bottom = top + ch;

            // FAR = yellow, NEAR = red, CRITICAL = purple
            if (severity == HazardDetector.Severity.NEAR) hotPaint.setARGB(120, 255, 0, 0);
            else if (severity == HazardDetector.Severity.CRITICAL) hotPaint.setARGB(140, 180, 0, 255);
            else hotPaint.setARGB(120, 255, 220, 0);

            canvas.drawRect(left, top, right, bottom, hotPaint);
        }

        // grid
        for (int i = 1; i < 3; i++) {
            canvas.drawLine(i * cw, 0, i * cw, h, gridPaint);
        }
        for (int j = 1; j < 5; j++) {
            canvas.drawLine(0, j * ch, w, j * ch, gridPaint);
        }

        canvas.drawText("Hazard grid (3x5)", 20, 50, textPaint);
    }
}