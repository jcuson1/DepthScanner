package com.example.depthdebug;

import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class DenseDepthProvider {

    public DenseDepthData tryAcquire(Frame frame) {
        Image depthImage = null;

        try {
            depthImage = frame.acquireDepthImage16Bits();

            int width = depthImage.getWidth();
            int height = depthImage.getHeight();

            short[] depth = readDepth(depthImage);
            if (depth == null || depth.length != width * height) {
                return null;
            }

            return new DenseDepthData(depth, width, height);

        } catch (NotYetAvailableException e) {
            return null;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        } finally {
            if (depthImage != null) {
                depthImage.close();
            }
        }
    }

    private short[] readDepth(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            return null;
        }

        ByteBuffer buffer = planes[0].getBuffer().order(ByteOrder.nativeOrder());
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();

        int width = image.getWidth();
        int height = image.getHeight();

        short[] out = new short[width * height];

        if (pixelStride == 2 && rowStride == width * 2) {
            ShortBuffer shortBuffer = buffer.asShortBuffer();
            shortBuffer.get(out);
            return out;
        }

        for (int y = 0; y < height; y++) {
            int rowStart = y * rowStride;
            for (int x = 0; x < width; x++) {
                int index = rowStart + x * pixelStride;
                short value = buffer.getShort(index);
                out[y * width + x] = value;
            }
        }

        return out;
    }
}