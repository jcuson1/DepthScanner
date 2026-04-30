package com.google.ar.core.examples.java.helloar.depth20;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Кастомный View, рисует 20 точек с подписями (дистанция + confidence) поверх картинки
 * камеры. Используется и Depth20PointModule'ем, и SmartDepthPointModule'ем — у них одна
 * визуализация.
 *
 * <p>Кружок зелёный → точка свободна, красный → blocked. Рядом две строки: метры и %.
 */
public class Depth20OverlayView extends View {
  // Размеры в пикселях — подобраны на глаз. На разных экранах смотрится плюс-минус ок.
  private static final float POINT_RADIUS_PX = 10.0f;
  private static final float LABEL_OFFSET_Y_PX = 22.0f;
  private static final float LABEL_LINE_GAP_PX = 6.0f;
  private static final float LABEL_PADDING_X_PX = 10.0f;
  private static final float LABEL_PADDING_Y_PX = 6.0f;
  private static final float LABEL_CORNER_RADIUS_PX = 10.0f;

  private final Paint clearPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint blockedPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint primaryTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint secondaryTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private float[] pointPositions = new float[0];
  private String[] distanceLabels = new String[0];
  private String[] confidenceLabels = new String[0];
  private boolean[] blockedPoints = new boolean[0];

  public Depth20OverlayView(Context context) {
    this(context, null);
  }

  public Depth20OverlayView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public Depth20OverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    clearPointPaint.setColor(Color.parseColor("#2ECC71"));
    blockedPointPaint.setColor(Color.parseColor("#FF3B30"));

    primaryTextPaint.setColor(Color.WHITE);
    primaryTextPaint.setTextSize(28.0f);
    primaryTextPaint.setFakeBoldText(true);

    secondaryTextPaint.setColor(Color.parseColor("#E8F0FE"));
    secondaryTextPaint.setTextSize(22.0f);

    labelBackgroundPaint.setColor(Color.parseColor("#99000000"));
  }

  /**
   * Заливает в View новые данные и просит redraw. Вызывается из MainActivity на UI-потоке.
   * Все массивы клонируем чтобы не было гонок если caller их потом изменит.
   */
  public void updateOverlay(
      float[] pointPositions,
      String[] distanceLabels,
      String[] confidenceLabels,
      boolean[] blockedPoints) {
    this.pointPositions = pointPositions != null ? pointPositions.clone() : new float[0];
    this.distanceLabels = distanceLabels != null ? distanceLabels.clone() : new String[0];
    this.confidenceLabels = confidenceLabels != null ? confidenceLabels.clone() : new String[0];
    this.blockedPoints = blockedPoints != null ? blockedPoints.clone() : new boolean[0];
    postInvalidateOnAnimation();
  }

  /** Очистить экран — выходим из режима. */
  public void clear() {
    pointPositions = new float[0];
    distanceLabels = new String[0];
    confidenceLabels = new String[0];
    blockedPoints = new boolean[0];
    postInvalidateOnAnimation();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int pointCount = Math.min(pointPositions.length / 2, distanceLabels.length);
    pointCount = Math.min(pointCount, confidenceLabels.length);
    pointCount = Math.min(pointCount, blockedPoints.length);
    for (int i = 0; i < pointCount; i++) {
      float x = pointPositions[i * 2];
      float y = pointPositions[i * 2 + 1];
      Paint pointPaint = blockedPoints[i] ? blockedPointPaint : clearPointPaint;
      canvas.drawCircle(x, y, POINT_RADIUS_PX, pointPaint);
      drawLabel(canvas, x, y - LABEL_OFFSET_Y_PX, distanceLabels[i], confidenceLabels[i], blockedPoints[i]);
    }
  }

  private void drawLabel(
      Canvas canvas, float centerX, float anchorY, String primaryText, String secondaryText, boolean blocked) {
    if (primaryText == null) {
      return;
    }
    Paint.FontMetrics primaryMetrics = primaryTextPaint.getFontMetrics();
    Paint.FontMetrics secondaryMetrics = secondaryTextPaint.getFontMetrics();
    boolean hasSecondary = secondaryText != null && !secondaryText.trim().isEmpty();

    float primaryWidth = primaryTextPaint.measureText(primaryText);
    float secondaryWidth = hasSecondary ? secondaryTextPaint.measureText(secondaryText) : 0.0f;
    float contentWidth = Math.max(primaryWidth, secondaryWidth);
    float primaryHeight = primaryMetrics.bottom - primaryMetrics.top;
    float secondaryHeight = hasSecondary ? (secondaryMetrics.bottom - secondaryMetrics.top) : 0.0f;
    float contentHeight =
        primaryHeight + (hasSecondary ? LABEL_LINE_GAP_PX + secondaryHeight : 0.0f);

    float left = centerX - (contentWidth / 2.0f) - LABEL_PADDING_X_PX;
    float top = anchorY - contentHeight - LABEL_PADDING_Y_PX;
    float right = centerX + (contentWidth / 2.0f) + LABEL_PADDING_X_PX;
    float bottom = anchorY + LABEL_PADDING_Y_PX;

    if (blocked) {
      labelBackgroundPaint.setColor(Color.parseColor("#AA8B0000"));
    } else {
      labelBackgroundPaint.setColor(Color.parseColor("#99000000"));
    }
    canvas.drawRoundRect(
        new RectF(left, top, right, bottom),
        LABEL_CORNER_RADIUS_PX,
        LABEL_CORNER_RADIUS_PX,
        labelBackgroundPaint);

    float contentTop = top + LABEL_PADDING_Y_PX;
    float primaryBaseline = contentTop - primaryMetrics.top;
    canvas.drawText(primaryText, centerX - primaryWidth / 2.0f, primaryBaseline, primaryTextPaint);

    if (hasSecondary) {
      float secondaryTop = contentTop + primaryHeight + LABEL_LINE_GAP_PX;
      float secondaryBaseline = secondaryTop - secondaryMetrics.top;
      canvas.drawText(
          secondaryText,
          centerX - secondaryWidth / 2.0f,
          secondaryBaseline,
          secondaryTextPaint);
    }
  }
}
