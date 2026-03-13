package com.example.depthdebug;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

public class OrientationHelper implements SensorEventListener {

    public static class Orientation {
        public final float pitchDeg; // наклон вперёд/назад
        public final float rollDeg;  // наклон влево/вправо
        public Orientation(float pitchDeg, float rollDeg) {
            this.pitchDeg = pitchDeg;
            this.rollDeg = rollDeg;
        }
    }

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;

    private volatile float pitchDeg = 0f;
    private volatile float rollDeg = 0f;

    private final float[] rotMat = new float[9];
    private final float[] orient = new float[3]; // azimuth, pitch, roll

    private final float alpha = 0.85f;

    public OrientationHelper(Context ctx) {
        sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    public boolean isAvailable() {
        return rotationVectorSensor != null;
    }

    public void start() {
        if (rotationVectorSensor == null) return;
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    public Orientation getOrientation() {
        return new Orientation(pitchDeg, rollDeg);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;

        SensorManager.getRotationMatrixFromVector(rotMat, event.values);

        // Получаем ориентирование. Важно: pitch/roll тут зависят от системы координат Android.
        SensorManager.getOrientation(rotMat, orient);

        float pitch = (float) Math.toDegrees(orient[1]);
        float roll  = (float) Math.toDegrees(orient[2]);

        // сгладим
        pitchDeg = alpha * pitchDeg + (1f - alpha) * pitch;
        rollDeg  = alpha * rollDeg  + (1f - alpha) * roll;
        //Log.println(1, "DEGREES", String.format("pitch = %.3f, roll = %.3f", pitch, roll));

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}