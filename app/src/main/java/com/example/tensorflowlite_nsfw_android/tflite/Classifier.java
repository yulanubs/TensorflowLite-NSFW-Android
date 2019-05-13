
package com.example.tensorflowlite_nsfw_android.tflite;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.RequiresApi;
import com.zwy.xlog.XLog;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Date;

/**
 * A classifier specialized to label images using TensorFlow Lite.
 */
public abstract class Classifier {

    /**
     * The runtime device type used for executing classification.
     */
    public enum Device {
        CPU,
        NNAPI,
        GPU
    }

    /**
     * Dimensions of inputs.
     */
    private static final int DIM_BATCH_SIZE = 1;

    private static final int DIM_PIXEL_SIZE = 3;

    /**
     * Preallocated buffers for storing image data in.
     */
    private int[] intValues = new int[getImageSizeX() * getImageSizeY()];

    /**
     * Options for configuring the Interpreter.
     */
    private final Interpreter.Options tfliteOptions = new Interpreter.Options();

    /**
     * The loaded TensorFlow Lite model.
     */
    private MappedByteBuffer tfliteModel;

    /** Labels corresponding to the output of the vision model. */
//  private List<String> labels;

    /**
     * Optional GPU delegate for accleration.
     */
    private GpuDelegate gpuDelegate = null;

    /**
     * An instance of the driver class to run model inference with Tensorflow Lite.
     */
    protected Interpreter tflite;

    /**
     * A ByteBuffer to hold image data, to be feed into Tensorflow Lite as inputs.
     */
    protected ByteBuffer imgData = null;

    /**
     * Creates a classifier with the provided configuration.
     *
     * @param activity   The current Activity.
     * @param numThreads The number of threads to use for classification.
     * @return A classifier with the desired configuration.
     */
    public static Classifier create(Activity activity,  Boolean isAddGpuDelegate, int numThreads)
            throws IOException {
        return new ClassifierFloatMobileNet(activity, isAddGpuDelegate, numThreads);
    }

    /**
     * An immutable result returned by a Classifier describing what was recognized.
     */

    /**
     * Initializes a {@code Classifier}.
     */
    protected Classifier(Activity activity, Boolean isAddGpuDelegate, int numThreads) throws IOException {
        tfliteModel = loadModelFile(activity);
        if (isAddGpuDelegate) {
            gpuDelegate = new GpuDelegate();
            tfliteOptions.addDelegate(gpuDelegate);
        }
        tfliteOptions.setNumThreads(numThreads);
        tflite = new Interpreter(tfliteModel, tfliteOptions);
        imgData =
                ByteBuffer.allocateDirect(
                        DIM_BATCH_SIZE
                                * getImageSizeX()
                                * getImageSizeY()
                                * DIM_PIXEL_SIZE
                                * getNumBytesPerChannel());
        imgData.order(ByteOrder.LITTLE_ENDIAN);
        XLog.d("TensorflowLite", "Created a Tensorflow Lite Image Classifier.");
    }

    /**
     * Memory-map the model file in Assets.
     */
    private MappedByteBuffer loadModelFile(Activity activity) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(getModelPath());
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Writes Image data into a {@code ByteBuffer}.
     */
    private void convertBitmapToByteBuffer(Bitmap bitmap) {
        if (imgData == null) {
            return;
        }

        imgData.rewind();
//        intValues= ImageUtil.bitmap2BGR(bitmap);
        //把每个像素的颜色值转为int 存入intValues
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
//        bitmap.copyPixelsToBuffer(imgData);
        // Convert the image to floating point.
        int pixel = 0;
        long startTime = SystemClock.uptimeMillis();
        for (int i = 0; i < getImageSizeX(); ++i) {
            for (int j = 0; j < getImageSizeY(); ++j) {

                //操作每一个像素
                //拿出每一个像素点对应的R、G、B的int值
                //对每一个int值减去阈值 R-123  B-104  G-117
                //将R、G、B 利用 B、G、R顺序重新写入数组
                //将数组传入tflite获取回传结果
                final int color = intValues[pixel++];
                int r1 = Color.red(color) - 123;
                int g1 = Color.green(color) - 117;
                int b1 = Color.blue(color) - 104;

                imgData.putFloat(b1);
                imgData.putFloat(g1);
                imgData.putFloat(r1);
            }
        }
        long endTime = SystemClock.uptimeMillis();
        XLog.d("TensorflowLite", "Timecost to put values into ByteBuffer: " + (endTime - startTime));
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    public NsfwBean run(Bitmap bitmap) {
        XLog.d("TensorflowLite", "开始转换图片");
        Long startTime_ = new Date().getTime();
        convertBitmapToByteBuffer(bitmap);
        XLog.d("TensorflowLite", "转换成功，耗时:" + (new Date().getTime() - startTime_) + "ms");
        long startTime = SystemClock.uptimeMillis();
        float[][] labelProbArray = new float[1][2];
        tflite.run(imgData, labelProbArray);
        long endTime = SystemClock.uptimeMillis();
        XLog.d("TensorflowLite", "Timecost to run model inference: " + (endTime - startTime) + "ms");
        return new NsfwBean(labelProbArray[0][0], labelProbArray[0][1]);
    }


    /**
     * Closes the interpreter and model to release resources.
     */
    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        tfliteModel = null;
    }

    /**
     * Get the image size along the x axis.
     *
     * @return
     */
    public abstract int getImageSizeX();

    /**
     * Get the image size along the y axis.
     *
     * @return
     */
    public abstract int getImageSizeY();

    /**
     * Get the name of the model file stored in Assets.
     *
     * @return
     */
    protected abstract String getModelPath();


    /**
     * Get the number of bytes that is used to store a single color channel value.
     *
     * @return
     */
    protected abstract int getNumBytesPerChannel();


    public static final class NsfwBean {
        private float sfw;
        private float nsfw;

        public NsfwBean(float sfw, float nsfw) {
            this.sfw = sfw;
            this.nsfw = nsfw;
        }

        public float getSfw() {
            return sfw;
        }


        public float getNsfw() {
            return nsfw;
        }


        @Override
        public String toString() {
            return "NsfwBean{" +
                    "sfw='" + sfw + '\'' +
                    ", nsfw='" + nsfw + '\'' +
                    '}';
        }
    }
}
