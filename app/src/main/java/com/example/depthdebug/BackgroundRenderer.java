package com.example.depthdebug;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class BackgroundRenderer {

    private static final String VERT =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main(){\n" +
                    "  gl_Position = a_Position;\n" +
                    "  v_TexCoord = a_TexCoord;\n" +
                    "}\n";

    private static final String FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main(){\n" +
                    "  gl_FragColor = texture2D(sTexture, v_TexCoord);\n" +
                    "}\n";

    private int program = 0;
    private int texId = -1;

    private int aPos, aUv, uTex;

    private FloatBuffer quadPos;
    private FloatBuffer quadUv;

    private final float[] quadPosArr = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    // UV будут обновляться через transformCoordinates2d
    private final float[] quadUvArr = {
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };
    // 4 вершины quad в NDC (x,y) — именно их нужно подавать в transformCoordinates2d
    private final float[] quadNdc = {
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
    };

    private final float[] transformedUv = new float[8];

    public void init() {
        program = ShaderUtil.createProgram(VERT, FRAG);
        aPos = GLES20.glGetAttribLocation(program, "a_Position");
        aUv = GLES20.glGetAttribLocation(program, "a_TexCoord");
        uTex = GLES20.glGetUniformLocation(program, "sTexture");

        quadPos = ByteBuffer.allocateDirect(quadPosArr.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadPos.put(quadPosArr).position(0);

        quadUv = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadUv.put(quadUvArr).position(0);

        texId = createOesTexture();
    }

    public int getTextureId() {
        return texId;
    }

    public void draw(Frame frame) {
        if (program == 0) return;

        // Получаем правильные UV для камеры с учётом rotation/crop
        frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadNdc,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedUv
        );

        quadUv.position(0);
        quadUv.put(transformedUv);
        quadUv.position(0);

        // Рисуем fullscreen quad с OES текстурой камеры
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTex, 0);

        quadPos.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos);

        GLES20.glEnableVertexAttribArray(aUv);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, quadUv);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUv);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    private static int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int id = tex[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        return id;
    }
}