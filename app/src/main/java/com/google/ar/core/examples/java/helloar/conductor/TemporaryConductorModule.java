package com.google.ar.core.examples.java.helloar.conductor;

import android.os.SystemClock;
import android.util.Log;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.examples.java.helloar.depth20.Depth20ScanResult;
import com.google.ar.core.examples.java.helloar.speech.SpeechModule;

/**
 * «Дирижёр» — решает что и когда говорить.
 *
 * <p>Все модули анализа (Depth20, SmartDepth, FloorHazard через адаптер, Octomap) шлют сюда
 * результат — Depth20ScanResult с фразой и severity. Этот класс проверяет:
 * <ul>
 *   <li>прошло ли время с прошлого высказывания (cooldown по severity);</li>
 *   <li>не дублируется ли фраза;</li>
 *   <li>не говорит ли TTS прямо сейчас (тогда просто пропускаем).</li>
 * </ul>
 * И передаёт фразу в SpeechModule.
 *
 * <p>Также озвучивает события трекинга: «Не могу определить дистанцию» если ARCore потерял
 * tracking, и осмысленные сообщения для разных причин сбоя (темно, мало деталей, резкое
 * движение и т.п.).
 *
 * <p>Класс не потокобезопасный — вызывается с GL-потока.
 */
public class TemporaryConductorModule {
  private static final String TAG = "TemporaryConductor";

  // Кулдауны по уровню важности фразы. Чем серьёзнее — тем чаще можем повторять.
  // STOP — быстрая реакция на угрозу, повтор раз в секунду. CLEAR — спокойная обстановка,
  // не «болтаем», раз в 8 секунд хватит.
  private static final long CLEAR_COOLDOWN_MS = 8000L;
  private static final long INFO_COOLDOWN_MS = 4000L;
  private static final long WARNING_COOLDOWN_MS = 2500L;
  private static final long STOP_COOLDOWN_MS = 1000L;

  // Кулдауны для отдельных «сервисных» сообщений (потеря трекинга, сбой ARCore).
  private static final long TRACKING_LOST_COOLDOWN_MS = 6000L;
  private static final long TRACKING_FAILURE_COOLDOWN_MS = 6000L;

  // Общая фраза при потере трекинга без явной причины.
  private static final String TRACKING_LOST_PHRASE =
      "Не могу определить дистанцию. Поводите телефоном вокруг.";

  private final SpeechModule speechModule;

  // Состояние для основного потока команд.
  private long lastSpeechTimestampMs = 0L;
  private String lastSpokenPhrase = "";

  // Состояние для tracking-lost фразы.
  private long lastTrackingLostSpeechMs = 0L;
  private boolean trackingLostActive = false;

  // Состояние для tracking-failure фраз (с конкретной причиной).
  private TrackingFailureReason lastSpokenFailureReason = null;
  private long lastFailureSpeechMs = 0L;

  public TemporaryConductorModule(SpeechModule speechModule) {
    this.speechModule = speechModule;
  }

  /** Совместимая перегрузка — sourceName по умолчанию. */
  public void onDepth20Result(Depth20ScanResult result) {
    onDepth20Result(result, "Depth20PointModule");
  }

  /**
   * Главный метод — модуль анализа отдаёт нам результат.
   *
   * <p>Логика:
   * 1) если речь не готова, фраза пустая или TTS уже что-то говорит — выходим.
   * 2) для модулей с собственным gating'ом речи (SmartDepth) — пропускаем cooldown
   *    (модуль уже сам решил что фразу пора).
   * 3) для остальных — сравниваем с предыдущей фразой и временем, и решаем говорить.
   */
  public void onDepth20Result(Depth20ScanResult result, String sourceName) {
    if (result == null || !speechModule.isReady()) {
      return;
    }

    String phrase = result.getSpokenPhrase();
    if (phrase == null || phrase.trim().isEmpty()) {
      return; // пустая фраза = «ничего говорить не надо»
    }

    long now = SystemClock.elapsedRealtime();
    boolean hasInternalSpeechGating = "SmartDepthPointModule".equals(sourceName);
    if (!hasInternalSpeechGating) {
      // Если фраза не изменилась И ещё не прошёл кулдаун — молчим.
      long cooldownMs = getCooldownMs(result.getSeverity());
      boolean phraseChanged = !phrase.equals(lastSpokenPhrase);
      if (!phraseChanged && (now - lastSpeechTimestampMs) < cooldownMs) {
        return;
      }
    }

    // TTS ещё не закончил предыдущую фразу — не перебиваем.
    if (speechModule.isSpeaking()) {
      Log.d(TAG, "Skipped phrase while previous speech is still playing: " + phrase);
      return;
    }

    speechModule.speak(phrase, false);
    lastSpokenPhrase = phrase;
    lastSpeechTimestampMs = now;
    Log.i(
        TAG,
        "Spoken phrase from "
            + sourceName
            + ": "
            + phrase
            + ", severity="
            + result.getSeverity());
  }

  /**
   * Трекинг потерян, причину ARCore не сообщил. Говорим общую подсказку «поводите телефоном
   * вокруг». Один раз за эпизод (пока не восстановится).
   */
  public void onTrackingLost() {
    if (!speechModule.isReady()) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (trackingLostActive && (now - lastTrackingLostSpeechMs) < TRACKING_LOST_COOLDOWN_MS) {
      return;
    }
    if (speechModule.isSpeaking()) {
      return;
    }
    speechModule.speak(TRACKING_LOST_PHRASE, false);
    lastTrackingLostSpeechMs = now;
    trackingLostActive = true;
    Log.i(TAG, "Tracking lost phrase spoken: " + TRACKING_LOST_PHRASE);
  }

  /** Трекинг восстановился — сбрасываем флаг чтобы при следующей потере опять озвучить. */
  public void onTrackingRestored() {
    if (trackingLostActive) {
      Log.i(TAG, "Tracking restored, clearing tracking-lost state");
    }
    trackingLostActive = false;
    lastSpokenFailureReason = null;
  }

  /**
   * ARCore сообщил конкретную причину сбоя (темно, мало деталей, движение и т.п.) —
   * озвучиваем понятным языком. Каждая причина → одна фраза с кулдауном 6 секунд.
   */
  public void onTrackingFailure(TrackingFailureReason reason) {
    if (reason == null || reason == TrackingFailureReason.NONE) {
      lastSpokenFailureReason = null;
      return;
    }
    if (!speechModule.isReady()) {
      return;
    }
    String phrase = phraseForFailureReason(reason);
    if (phrase == null) {
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (reason == lastSpokenFailureReason
        && (now - lastFailureSpeechMs) < TRACKING_FAILURE_COOLDOWN_MS) {
      return; // та же причина и кулдаун ещё не прошёл
    }
    if (speechModule.isSpeaking()) {
      return;
    }
    speechModule.speak(phrase, false);
    lastSpokenFailureReason = reason;
    lastFailureSpeechMs = now;
    Log.i(TAG, "Tracking failure phrase spoken (" + reason + "): " + phrase);
  }

  /** Сброс — при смене режима. */
  public void reset() {
    lastSpeechTimestampMs = 0L;
    lastSpokenPhrase = "";
    lastTrackingLostSpeechMs = 0L;
    trackingLostActive = false;
    lastSpokenFailureReason = null;
    lastFailureSpeechMs = 0L;
    speechModule.stop();
  }

  /** Шатдаун TTS — вызывается из MainActivity.onDestroy. */
  public void shutdown() {
    reset();
    speechModule.shutdown();
  }

  /** Кулдаун в зависимости от severity. */
  private long getCooldownMs(Depth20ScanResult.Severity severity) {
    switch (severity) {
      case STOP:
        return STOP_COOLDOWN_MS;
      case WARNING:
        return WARNING_COOLDOWN_MS;
      case INFO:
        return INFO_COOLDOWN_MS;
      case CLEAR:
      default:
        return CLEAR_COOLDOWN_MS;
    }
  }

  /** Перевод TrackingFailureReason → русская подсказка. NONE → null (молчим). */
  private static String phraseForFailureReason(TrackingFailureReason reason) {
    switch (reason) {
      case BAD_STATE:
        return "Сбой отслеживания. Подождите немного.";
      case INSUFFICIENT_LIGHT:
        return "Слишком темно. Перейдите в более освещённое место.";
      case EXCESSIVE_MOTION:
        return "Слишком быстро. Двигайте телефон медленнее.";
      case INSUFFICIENT_FEATURES:
        return "Слишком мало деталей. Наведите камеру на поверхность с текстурой или цветом.";
      case CAMERA_UNAVAILABLE:
        return "Камера недоступна.";
      case NONE:
      default:
        return null;
    }
  }
}
