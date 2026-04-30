package com.google.ar.core.examples.java.helloar.depth20;

import java.util.Arrays;

/**
 * Готовый результат одного «прохода» по depth-карте — иммутабельный snapshot.
 * Его создают разные модули (Depth20PointModule, SmartDepthPointModule, FloorHazardModule
 * через адаптер, OctomapModule) и шлют в TemporaryConductorModule, который решает —
 * озвучивать или нет.
 */
public class Depth20ScanResult {
  /** Уровень опасности — определяет кулдаун дирижёра. STOP озвучивается чаще всех. */
  public enum Severity {
    CLEAR,    // путь свободен
    INFO,     // лёгкая корректировка
    WARNING,  // важная подсказка
    STOP      // стоп / опасность
  }

  // Дистанции по точкам (метры). -1 = валидной не было.
  private final float[] distancesMeters;
  // Уверенность ARCore по точкам (0..1, -1 = недоступно).
  private final float[] confidenceValues;
  // Какие точки помечены «заблокированы» (для красной подсветки в overlay'е).
  private final boolean[] blockedPoints;
  // Готовая фраза для TTS. Пустая = ничего говорить не надо.
  private final String spokenPhrase;
  private final Severity severity;
  // Самая близкая дистанция среди всех точек. Для дополнительного логирования.
  private final float closestDistanceMeters;

  public Depth20ScanResult(
      float[] distancesMeters,
      float[] confidenceValues,
      boolean[] blockedPoints,
      String spokenPhrase,
      Severity severity,
      float closestDistanceMeters) {
    this.distancesMeters = distancesMeters.clone();
    this.confidenceValues = confidenceValues.clone();
    this.blockedPoints = blockedPoints.clone();
    this.spokenPhrase = spokenPhrase;
    this.severity = severity;
    this.closestDistanceMeters = closestDistanceMeters;
  }

  public float[] getDistancesMeters() {
    return distancesMeters.clone();
  }

  public float[] getConfidenceValues() {
    return confidenceValues.clone();
  }

  public boolean[] getBlockedPoints() {
    return blockedPoints.clone();
  }

  public String getSpokenPhrase() {
    return spokenPhrase;
  }

  public Severity getSeverity() {
    return severity;
  }

  public float getClosestDistanceMeters() {
    return closestDistanceMeters;
  }

  public boolean hasObstacle() {
    return severity != Severity.CLEAR;
  }

  @Override
  public String toString() {
    return "Depth20ScanResult{"
        + "severity="
        + severity
        + ", closestDistanceMeters="
        + closestDistanceMeters
        + ", spokenPhrase='"
        + spokenPhrase
        + '\''
        + ", blockedPoints="
        + Arrays.toString(blockedPoints)
        + '}';
  }
}
