package com.example.depthdebug;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

public class DetectionOverlayView extends View {

    private final Paint topPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private DetectionResult result;

    public DetectionOverlayView(Context context) {
        super(context);
        init();
    }

    public DetectionOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DetectionOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        topPanelPaint.setColor(Color.argb(150, 0, 0, 0));
        topPanelPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36f);

        messagePaint.setColor(Color.WHITE);
        messagePaint.setTextSize(64f);
        messagePaint.setFakeBoldText(true);
    }

    public void setDetectionResult(DetectionResult result) {
        this.result = result;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawTopPanel(canvas);
        drawCenterMessage(canvas);
    }

    private void drawTopPanel(Canvas canvas) {
        float panelHeight = 170f;
        canvas.drawRect(0f, 0f, getWidth(), panelHeight, topPanelPaint);

        if (result == null) {
            canvas.drawText("Нет данных", 24f, 60f, textPaint);
            return;
        }

        canvas.drawText(
                "Уверенность: " + String.format(Locale.US, "%.2f", result.getOverallConfidence()),
                24f,
                60f,
                textPaint
        );
        canvas.drawText(result.getDebugText(), 24f, 115f, textPaint);
    }

    private void drawCenterMessage(Canvas canvas) {
        String message = "Свободно";

        if (result != null) {
            Hazard h = result.getPrimaryHazard();
            if (h != null) {
                message = h.getMessage();
            } else if (result.getOverallConfidence() < 0.20f) {
                message = "Мало надежных данных";
            }
        }

        float textWidth = messagePaint.measureText(message);
        float x = (getWidth() - textWidth) / 2f;
        float y = getHeight() * 0.82f;

        canvas.drawText(message, x, y, messagePaint);
    }
}