package com.google.ar.core.examples.java.helloar.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import androidx.core.content.FileProvider;
import com.google.ar.core.ArCoreApk.Availability;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Логгер диагностики. Каждая сессия — отдельный файл в {@code files/diagnostics/}, включает:
 *
 * <ul>
 *   <li>Заголовок с информацией об устройстве, ARCore, OpenGL.</li>
 *   <li>Все runtime-события (init, состояние трекинга, переключения режимов и т.п.).</li>
 *   <li>Захват logcat в фоновом потоке — чтобы потом всё это можно было выгрузить.</li>
 *   <li>Установка crash-handler'a — необработанные исключения попадают в файл.</li>
 * </ul>
 *
 * <p>Файлы можно «поделиться» через системный share sheet (см. shareDiagnosticsLog в
 * MainActivity). Чистка — через clear-button в меню.
 */
public class DiagnosticsLogger {
  private static final String TAG = "DiagnosticsLogger";

  // Шаблон даты в имени файла (для сортировки лексикографической).
  private static final String FILE_DATE_PATTERN = "yyyyMMdd-HHmmss";
  // Шаблон даты внутри лога — каждая строка имеет свой timestamp.
  private static final String LOG_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
  private static final String LOG_SEPARATOR =
      "================================================================";
  // Сколько строк из /proc/cpuinfo выгружаем в заголовок (там обычно много дублирующего).
  private static final int CPU_INFO_MAX_LINES = 12;

  private static final Object CRASH_HANDLER_LOCK = new Object();
  private static boolean crashHandlerInstalled = false;
  private static Thread.UncaughtExceptionHandler previousCrashHandler;

  private final Context appContext;
  private final SimpleDateFormat fileNameFormat = new SimpleDateFormat(FILE_DATE_PATTERN, Locale.US);
  private final SimpleDateFormat logLineFormat = new SimpleDateFormat(LOG_DATE_PATTERN, Locale.US);

  private File currentLogFile;
  private Process logcatProcess;
  private Thread logcatReaderThread;

  public DiagnosticsLogger(Context context) {
    this.appContext = context.getApplicationContext();
  }

  public synchronized void startNewSession() {
    File logDir = resolveLogDir();
    if (!logDir.exists() && !logDir.mkdirs()) {
      Log.w(TAG, "Unable to create diagnostics log directory: " + logDir);
    }

    currentLogFile = new File(logDir, "helloar-log-" + fileNameFormat.format(new Date()) + ".txt");
    writeSessionHeader();
    installCrashHandler();
  }

  public synchronized File getCurrentLogFile() {
    return currentLogFile;
  }

  public synchronized Uri getSharableUri() {
    if (currentLogFile == null || !currentLogFile.exists()) {
      return null;
    }
    return FileProvider.getUriForFile(
            appContext, appContext.getPackageName() + ".fileprovider", currentLogFile)
        .normalizeScheme();
  }

  public void logAppAndDeviceInfo() {
    logSection("SESSION");
    logInfo("SESSION", "Application started");
    logInfo("SESSION", "Package: " + appContext.getPackageName());
    logInfo("SESSION", "Version: " + getAppVersionName());

    logSection("DEVICE");
    logInfo("DEVICE", "Manufacturer: " + nullSafe(Build.MANUFACTURER));
    logInfo("DEVICE", "Brand: " + nullSafe(Build.BRAND));
    logInfo("DEVICE", "Model: " + nullSafe(Build.MODEL));
    logInfo("DEVICE", "Device: " + nullSafe(Build.DEVICE));
    logInfo("DEVICE", "Product: " + nullSafe(Build.PRODUCT));
    logInfo("DEVICE", "Board: " + nullSafe(Build.BOARD));
    logInfo("DEVICE", "Hardware: " + nullSafe(Build.HARDWARE));
    logInfo("DEVICE", "Fingerprint: " + nullSafe(Build.FINGERPRINT));
    logInfo("DEVICE", "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
    logInfo("DEVICE", "Supported ABIs: " + TextUtils.join(", ", Build.SUPPORTED_ABIS));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      logInfo("DEVICE", "SoC manufacturer: " + nullSafe(Build.SOC_MANUFACTURER));
      logInfo("DEVICE", "SoC model: " + nullSafe(Build.SOC_MODEL));
    }

    ActivityManager activityManager =
        (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
    if (activityManager != null) {
      ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
      activityManager.getMemoryInfo(memoryInfo);
      logInfo("DEVICE", "Memory class: " + activityManager.getMemoryClass() + " MB");
      logInfo("DEVICE", "Large memory class: " + activityManager.getLargeMemoryClass() + " MB");
      logInfo("DEVICE", "Total memory: " + formatMegabytes(memoryInfo.totalMem));
      logInfo("DEVICE", "Available memory: " + formatMegabytes(memoryInfo.availMem));
      logInfo(
          "DEVICE",
          "OpenGL ES version: "
              + activityManager.getDeviceConfigurationInfo().getGlEsVersion());
    }

    String cpuInfo = readCpuInfo();
    if (!cpuInfo.isEmpty()) {
      logInfo("DEVICE", "CPU info:\n" + cpuInfo);
    }
  }

  public void logGlInfo(String glVendor, String glRenderer, String glVersion) {
    logSection("GPU");
    logInfo("GPU", "Vendor: " + nullSafe(glVendor));
    logInfo("GPU", "Renderer: " + nullSafe(glRenderer));
    logInfo("GPU", "Version: " + nullSafe(glVersion));
  }

  public void logArCoreAvailability(Availability availability) {
    logSection("ARCORE");
    logInfo("ARCORE", "Availability: " + String.valueOf(availability));
    if (availability == Availability.SUPPORTED_INSTALLED) {
      logInfo("ARCORE", "Support status: ARCore is installed and available");
    } else {
      logWarning("ARCORE", "Support status: ARCore is not ready yet or unsupported");
    }
  }

  public void logSection(String title) {
    appendRawLine(LOG_SEPARATOR);
    appendRawLine(now() + " | SECTION | " + title);
    appendRawLine(LOG_SEPARATOR);
  }

  public void logInfo(String section, String message) {
    log("INFO", section, message, null);
  }

  public void logWarning(String section, String message) {
    log("WARN", section, message, null);
  }

  public void logError(String section, String message, Throwable throwable) {
    log("ERROR", section, message, throwable);
  }

  public synchronized void startLogcatCapture() {
    if (logcatProcess != null) {
      return;
    }
    try {
      ProcessBuilder processBuilder =
          new ProcessBuilder(
              "logcat",
              "-v",
              "threadtime",
              "--pid",
              String.valueOf(android.os.Process.myPid()));
      processBuilder.redirectErrorStream(true);
      logcatProcess = processBuilder.start();
      logcatReaderThread =
          new Thread(
              () -> {
                try (BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    appendRawLine(now() + " | LOGCAT | " + line);
                  }
                } catch (IOException e) {
                  logWarning("LOGCAT", "Logcat capture stopped: " + e.getMessage());
                }
              },
              "HelloArLogcatCapture");
      logcatReaderThread.setDaemon(true);
      logcatReaderThread.start();
      logInfo("LOGCAT", "Started logcat capture for pid=" + android.os.Process.myPid());
    } catch (IOException e) {
      logWarning("LOGCAT", "Unable to start logcat capture: " + e.getMessage());
    }
  }

  public synchronized void stopLogcatCapture() {
    if (logcatProcess == null) {
      return;
    }
    logInfo("LOGCAT", "Stopping logcat capture");
    logcatProcess.destroy();
    logcatProcess = null;
    logcatReaderThread = null;
  }

  public synchronized boolean clearAllLogs() {
    stopLogcatCapture();

    File logDir = resolveLogDir();
    boolean success = true;
    File[] files = logDir.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isFile() && !file.delete()) {
          success = false;
        }
      }
    }
    currentLogFile = null;
    return success;
  }

  private void writeSessionHeader() {
    appendRawLine(LOG_SEPARATOR);
    appendRawLine("HelloAR Diagnostics Log");
    appendRawLine("Started: " + now());
    appendRawLine(LOG_SEPARATOR);
    appendRawLine("Log Reference:");
    appendRawLine("INFO  = normal runtime information and state transitions");
    appendRawLine("WARN  = degraded mode, retry, unsupported feature, or recoverable problem");
    appendRawLine("ERROR = failure, exception, or unexpected state");
    appendRawLine("LOGCAT = best-effort capture of runtime log output for this app process");
    appendRawLine("Sections:");
    appendRawLine(
        "SESSION, DEVICE, GPU, ARCORE, LIFECYCLE, PERMISSION, RENDER, DEPTH, DEPTH20, SMART_DEPTH, FLOORMAP, CONDUCTOR, TRACKING, INPUT, SHARE, LOGCAT, CRASH");
    appendRawLine(LOG_SEPARATOR);
  }

  private void installCrashHandler() {
    synchronized (CRASH_HANDLER_LOCK) {
      if (crashHandlerInstalled) {
        return;
      }
      previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, throwable) -> {
            logError(
                "CRASH",
                "Uncaught exception on thread " + thread.getName(),
                throwable);
            if (previousCrashHandler != null) {
              previousCrashHandler.uncaughtException(thread, throwable);
            }
          });
      crashHandlerInstalled = true;
    }
  }

  private void log(String level, String section, String message, Throwable throwable) {
    StringBuilder builder = new StringBuilder();
    builder
        .append(now())
        .append(" | ")
        .append(level)
        .append(" | ")
        .append(section)
        .append(" | ")
        .append(message);
    if (throwable != null) {
      builder.append('\n').append(stackTraceToString(throwable));
    }
    appendRawLine(builder.toString());
  }

  private synchronized void appendRawLine(String line) {
    if (currentLogFile == null) {
      Log.w(TAG, "Tried to write to diagnostics log before session start: " + line);
      return;
    }
    try (FileWriter writer = new FileWriter(currentLogFile, true)) {
      writer.write(line);
      writer.write(System.lineSeparator());
    } catch (IOException e) {
      Log.e(TAG, "Failed to append diagnostics log", e);
    }
  }

  private File resolveLogDir() {
    File externalDir = appContext.getExternalFilesDir("logs");
    if (externalDir != null) {
      return externalDir;
    }
    return new File(appContext.getFilesDir(), "logs");
  }

  private String getAppVersionName() {
    try {
      PackageManager packageManager = appContext.getPackageManager();
      PackageInfo packageInfo = packageManager.getPackageInfo(appContext.getPackageName(), 0);
      return String.valueOf(packageInfo.versionName);
    } catch (PackageManager.NameNotFoundException e) {
      return "unknown";
    }
  }

  private String readCpuInfo() {
    File cpuInfoFile = new File("/proc/cpuinfo");
    if (!cpuInfoFile.exists()) {
      return "";
    }

    StringBuilder builder = new StringBuilder();
    int linesRead = 0;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(cpuInfoFile), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null && linesRead < CPU_INFO_MAX_LINES) {
        if (line.trim().isEmpty()) {
          continue;
        }
        builder.append(line).append('\n');
        linesRead++;
      }
    } catch (IOException e) {
      return "Unable to read /proc/cpuinfo: " + e.getMessage();
    }
    return builder.toString().trim();
  }

  private String stackTraceToString(Throwable throwable) {
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    throwable.printStackTrace(printWriter);
    printWriter.flush();
    return stringWriter.toString();
  }

  private String formatMegabytes(long bytes) {
    return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
  }

  private String now() {
    return logLineFormat.format(new Date());
  }

  private String nullSafe(String value) {
    return value == null ? "unknown" : value;
  }
}
