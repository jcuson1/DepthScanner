package com.example.depthdebug;

import android.media.Image;
import android.opengl.GLES20;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class DepthOverlayRenderer {

    public enum Mode { DEPTH, UNKNOWN }

    private static final String VERT =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main(){\n" +
                    "  gl_Position = a_Position;\n" +
                    "  v_TexCoord = a_TexCoord;\n" +
                    "}\n";

    private static final String FRAG =
            "precision mediump float;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main(){\n" +
                    "  gl_FragColor = texture2D(sTexture, v_TexCoord);\n" +
                    "}\n";
    private int program;
    private int aPos, aUv, uTex, uAlpha;
    private int texId = -1;

    private ByteBuffer overlayRGBA;

    private FloatBuffer quadPos;
    private FloatBuffer quadUv;

    private final float[] quadPosArr = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    private final float[] quadUvArr = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };
    // Тот же quad в NDC, что и у BackgroundRenderer
    private final float[] quadNdc = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    private final float[] transformedUv = new float[8];
    private ByteBuffer overlay8;
    private int imgW = 0, imgH = 0;
    private int uMode;
    private Mode currentMode = Mode.DEPTH;

    // Диапазон для depth визуализации
    private final int minMm = 200;
    private final int maxMm = 4000;

    public void init() {
        program = ShaderUtil.createProgram(VERT, FRAG);
        aPos = GLES20.glGetAttribLocation(program, "a_Position");
        aUv = GLES20.glGetAttribLocation(program, "a_TexCoord");
        uTex = GLES20.glGetUniformLocation(program, "sTexture");
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");

        uMode = GLES20.glGetUniformLocation(program, "uMode");

        quadPos = ByteBuffer.allocateDirect(quadPosArr.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadPos.put(quadPosArr).position(0);

        quadUv = ByteBuffer.allocateDirect(quadUvArr.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadUv.put(quadUvArr).position(0);

        texId = createLumaTexture();
    }

    public void onViewportChanged(int w, int h) {
        // пока не нужно
    }

    public void updateFromFrame(Frame frame, Mode mode) throws NotYetAvailableException {
        frame.transformCoordinates2d(
                com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadNdc,
                com.google.ar.core.Coordinates2d.IMAGE_NORMALIZED,
                transformedUv
        );
        currentMode = mode;
        quadUv.position(0);
        quadUv.put(transformedUv).position(0);
        Image depth = null;
        try {
            depth = frame.acquireDepthImage16Bits();
            ensureBuffer(depth.getWidth(), depth.getHeight());
            convert(depth);
            uploadTexture();
        } finally {
            if (depth != null) depth.close();
        }
    }

    private void ensureBuffer(int w, int h) {
        if (w == imgW && h == imgH && overlayRGBA != null) return;
        imgW = w; imgH = h;
        overlayRGBA = ByteBuffer.allocateDirect(w * h * 4);
    }

    private void convert(Image depth) {
        Image.Plane dp = depth.getPlanes()[0];
        ByteBuffer db = dp.getBuffer();
        int dRow = dp.getRowStride();
        int dPix = dp.getPixelStride();

        overlayRGBA.rewind();

        for (int y = 0; y < imgH; y++) {
            int dRowStart = y * dRow;
            for (int x = 0; x < imgW; x++) {
                int dOff = dRowStart + x * dPix;
                int lo = db.get(dOff) & 0xFF;
                int hi = db.get(dOff + 1) & 0xFF;
                int dmm = (hi << 8) | lo;

                boolean known = (dmm != 0 && dmm >= minMm && dmm <= maxMm);

                int r, g, b, a;
                if (!known) {
                    // unknown → полностью прозрачный
                    r = g = b = 0;
                    a = 0;
                } else {
                    float t = (dmm - minMm) / (float)(maxMm - minMm);
                    t = clamp01(t); // 0 near, 1 far

                    // near=red, far=green
                    r = (int)(255f * (1f - t));
                    g = (int)(255f * t);
                    b = 0;
                    a = 90; // ~0.35 прозрачность (90/255)
                }

                overlayRGBA.put((byte) (r & 0xFF));
                overlayRGBA.put((byte) (g & 0xFF));
                overlayRGBA.put((byte) (b & 0xFF));
                overlayRGBA.put((byte) (a & 0xFF));
            }
        }
        overlayRGBA.rewind();
    }


    private void uploadTexture() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                imgW,
                imgH,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                overlayRGBA
        );
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public void draw() {
        if (program == 0 || texId == -1) return;

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(uTex, 0);

        GLES20.glUniform1f(uAlpha, 0.35f);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);

        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);

        GLES20.glUniform1i(uMode, (currentMode == Mode.UNKNOWN) ? 1 : 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static int createLumaTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int id = tex[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        return id;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}