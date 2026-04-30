package com.google.ar.core.examples.java.helloar.speech;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.Locale;

/**
 * Обёртка над Android TextToSpeech. Кладём фразу — она говорится через системный TTS.
 *
 * <p>Лезть в SpeechModule напрямую не надо — пользуйтесь TemporaryConductorModule, он
 * умеет дросселить и проверять что фраза действительно нужна.
 *
 * <p>Голос — русский (ru_RU). Если у пользователя нет русского голосового пакета —
 * isReady() вернёт false, и приложение просто будет молчать.
 */
public class SpeechModule implements TextToSpeech.OnInitListener {
  private static final String TAG = "SpeechModule";

  private final TextToSpeech textToSpeech;
  private boolean ready = false;

  public SpeechModule(Context context) {
    // Используем applicationContext чтобы Activity мог уничтожиться без утечки.
    textToSpeech = new TextToSpeech(context.getApplicationContext(), this);
  }

  /** Колбек инициализации — вызывается Android'ом когда TTS готов. */
  @Override
  public void onInit(int status) {
    if (status != TextToSpeech.SUCCESS) {
      Log.e(TAG, "TextToSpeech init failed: " + status);
      return;
    }
    int languageResult = textToSpeech.setLanguage(new Locale("ru", "RU"));
    // LANG_MISSING_DATA / LANG_NOT_SUPPORTED → русского нет, готовы не будем.
    ready =
        languageResult != TextToSpeech.LANG_MISSING_DATA
            && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;
    textToSpeech.setSpeechRate(1.0f);
    textToSpeech.setPitch(1.0f);
    Log.i(TAG, "TextToSpeech ready=" + ready + ", languageResult=" + languageResult);
  }

  /** Готов ли TTS говорить. False = русский не поддерживается. */
  public boolean isReady() {
    return ready;
  }

  /** Сейчас что-то говорит? Используется дирижёром чтобы не перебивать. */
  public boolean isSpeaking() {
    return ready && textToSpeech.isSpeaking();
  }

  /**
   * Сказать фразу. flushQueue=true — обрывает текущую речь. Обычно ставим false —
   * пусть дирижёр сам решает что когда говорить.
   */
  public void speak(String text, boolean flushQueue) {
    if (!ready || text == null || text.trim().isEmpty()) {
      return;
    }
    int queueMode = flushQueue ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
    // utteranceId уникальный — нужен Android'у для идентификации запросов.
    textToSpeech.speak(text, queueMode, null, "depth20-" + System.currentTimeMillis());
  }

  /** Прервать текущую речь. */
  public void stop() {
    if (ready) {
      textToSpeech.stop();
    }
  }

  /** Шатдаун — освобождает TTS-ресурсы. Вызывается из onDestroy. */
  public void shutdown() {
    textToSpeech.stop();
    textToSpeech.shutdown();
    ready = false;
  }
}
