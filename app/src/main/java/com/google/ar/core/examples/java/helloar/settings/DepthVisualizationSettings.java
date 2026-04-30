/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ar.core.examples.java.helloar.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки режима визуализации. Хранятся в SharedPreferences — переживают перезапуск.
 *
 * <p>Плюс отдельный флаг {@code octomapUseRawDepth} — переключатель источника depth для
 * octomap-режима (raw / full).
 */
public class DepthVisualizationSettings {
  private static final String SHARED_PREFERENCES_ID = "DEPTH_VISUALIZATION_OPTIONS";
  private static final String SHARED_PREFERENCES_DEPTH_VISUALIZATION_MODE =
      "depth_visualization_mode";
  private static final String SHARED_PREFERENCES_OCTOMAP_USE_RAW_DEPTH =
      "octomap_use_raw_depth";

  /**
   * Все режимы визуализации. Порядок важен — индекс используется в array строк меню
   * и сохраняется в SharedPreferences. Добавляем новые ТОЛЬКО в конец, иначе у юзеров
   * собьются настройки после обновления.
   */
  public enum DepthVisualizationMode {
    CAMERA,             // обычная камера, без обработки
    FULL_DEPTH,         // depth-карта раскрашена
    RAW_DEPTH,          // raw depth-карта
    CONFIDENCE,         // карта уверенности
    DEPTH_DOT_GRID,     // depth точками-сеткой
    DISTANCE_PROBES,    // 4×4 крупных пробника
    DEPTH20_PROBES,     // 20 точек, простой режим
    SMART_DEPTH_20PT,   // 23 точки с умной логикой
    FLOOR_HEIGHTMAP,    // 3D карта пола
    FLOOR_POINTS_3D,    // 3D точки накапливаемые
    OCTOMAP_3D          // octomap + 3DVFH+
  }

  private DepthVisualizationMode depthVisualizationMode = DepthVisualizationMode.CAMERA;
  private boolean octomapUseRawDepth = true; // по умолчанию raw
  private SharedPreferences sharedPreferences;

  /** Загрузка настроек. Вызывается из MainActivity.onCreate. */
  public void onCreate(Context context) {
    sharedPreferences = context.getSharedPreferences(SHARED_PREFERENCES_ID, Context.MODE_PRIVATE);

    // Восстанавливаем сохранённый режим. Если индекс битый (например после удаления
    // пункта в enum'е) — fallback на CAMERA.
    int savedVisualizationMode =
        sharedPreferences.getInt(
            SHARED_PREFERENCES_DEPTH_VISUALIZATION_MODE, DepthVisualizationMode.CAMERA.ordinal());
    if (savedVisualizationMode < 0
        || savedVisualizationMode >= DepthVisualizationMode.values().length) {
      savedVisualizationMode = DepthVisualizationMode.CAMERA.ordinal();
    }
    depthVisualizationMode = DepthVisualizationMode.values()[savedVisualizationMode];

    octomapUseRawDepth =
        sharedPreferences.getBoolean(SHARED_PREFERENCES_OCTOMAP_USE_RAW_DEPTH, true);
  }

  public DepthVisualizationMode depthVisualizationMode() {
    return depthVisualizationMode;
  }

  /** Меняем режим и сразу сохраняем. */
  public void setDepthVisualizationMode(DepthVisualizationMode depthVisualizationMode) {
    if (depthVisualizationMode == null || depthVisualizationMode == this.depthVisualizationMode) {
      return; // ничего не изменилось
    }

    this.depthVisualizationMode = depthVisualizationMode;
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putInt(
        SHARED_PREFERENCES_DEPTH_VISUALIZATION_MODE, this.depthVisualizationMode.ordinal());
    editor.apply();
  }

  /** Использовать raw depth для octomap (true) или full depth (false). */
  public boolean octomapUseRawDepth() {
    return octomapUseRawDepth;
  }

  public void setOctomapUseRawDepth(boolean useRawDepth) {
    if (this.octomapUseRawDepth == useRawDepth) {
      return;
    }
    this.octomapUseRawDepth = useRawDepth;
    if (sharedPreferences != null) {
      SharedPreferences.Editor editor = sharedPreferences.edit();
      editor.putBoolean(SHARED_PREFERENCES_OCTOMAP_USE_RAW_DEPTH, useRawDepth);
      editor.apply();
    }
  }
}
