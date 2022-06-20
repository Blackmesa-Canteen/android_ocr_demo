package com.baidu.paddle.lite.demo.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Size;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLSurfaceView.Renderer;
import android.opengl.Matrix;
import android.support.v4.content.res.ResourcesCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.baidu.paddle.lite.demo.ppocr_demo.R;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

public class CameraSurfaceView extends GLSurfaceView implements Renderer,
        SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = CameraSurfaceView.class.getSimpleName();

    public static final int EXPECTED_PREVIEW_WIDTH = 1280;
    public static final int EXPECTED_PREVIEW_HEIGHT = 720;


    protected int numberOfCameras;
    protected int selectedCameraId;
    protected boolean disableCamera = false;
    protected Camera camera;

    protected Context context;
    protected SurfaceTexture surfaceTexture;
    protected int surfaceWidth = 0;
    protected int surfaceHeight = 0;
    protected int textureWidth = 0;
    protected int textureHeight = 0;

    // In order to manipulate the camera preview data and render the modified one
    // to the screen, three textures are created and the data flow is shown as following:
    // previewdata->camTextureId->fboTexureId->drawTexureId->framebuffer
    protected int[] fbo = {0};
    protected int[] camTextureId = {0};
    protected int[] fboTexureId = {0};
    protected int[] drawTexureId = {0};

    // ID card locator
    private int maskColor = Color.argb(100, 0, 0, 0);
    private Paint eraser = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Rect frame = new Rect();
    private Drawable locatorDrawable;

    // run before constructor
    {
        // 硬件加速不支持，图层混合。

        pen.setColor(Color.WHITE);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(6);

        eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    private final String vss = ""
            + "attribute vec2 vPosition;\n"
            + "attribute vec2 vTexCoord;\n" + "varying vec2 texCoord;\n"
            + "void main() {\n" + "  texCoord = vTexCoord;\n"
            + "  gl_Position = vec4 (vPosition.x, vPosition.y, 0.0, 1.0);\n"
            + "}";

    private final String fssCam2FBO = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "varying vec2 texCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTexture,texCoord);\n" + "}";

    private final String fssTex2Screen = ""
            + "precision mediump float;\n"
            + "uniform sampler2D sTexture;\n"
            + "varying vec2 texCoord;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTexture,texCoord);\n" + "}";

    private final float vertexCoords[] = {
            -1, -1,
            -1, 1,
            1, -1,
            1, 1};
    private float textureCoords[] = {
            0, 1,
            0, 0,
            1, 1,
            1, 0};

    private FloatBuffer vertexCoordsBuffer;
    private FloatBuffer textureCoordsBuffer;

    private int progCam2FBO = -1;
    private int progTex2Screen = -1;
    private int vcCam2FBO;
    private int tcCam2FBO;
    private int vcTex2Screen;
    private int tcTex2Screen;

    public CameraSurfaceView(Context context) {
        super(context);
        locatorDrawable = ResourcesCompat.getDrawable(getResources(), R.drawable.bd_ocr_id_card_locator_front, null);

    }

    public void setMask() {
        locatorDrawable = ResourcesCompat.getDrawable(getResources(), R.drawable.bd_ocr_id_card_locator_front, null);
        setWillNotDraw(false);
        invalidate();
    }


    public interface OnTextureChangedListener {
        public boolean onTextureChanged(int inTextureId, int outTextureId, int textureWidth, int textureHeight);
    }

    private OnTextureChangedListener onTextureChangedListener = null;

    public void setOnTextureChangedListener(OnTextureChangedListener listener) {
        onTextureChangedListener = listener;
    }

    public CameraSurfaceView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        context = ctx;
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);

        // Find the total number of available cameras and the ID of the default camera
        numberOfCameras = Camera.getNumberOfCameras();
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                selectedCameraId = i;
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            float ratio = h > w ? 0.9f : 0.72f;

            int width = (int) (w * ratio);
            int height = width * 400 / 620;

            int left = (w - width) / 2;
            int top = (h - height) / 2;
            int right = width + left;
            int bottom = height + top;

            frame.left = left;
            frame.top = top;
            frame.right = right;
            frame.bottom = bottom;
        }
    }

    private Path path = new Path();

    private Path fillRectRound(float left, float top, float right, float bottom, float rx, float ry, boolean
            conformToOriginalPost) {

        path.reset();
        if (rx < 0) {
            rx = 0;
        }
        if (ry < 0) {
            ry = 0;
        }
        float width = right - left;
        float height = bottom - top;
        if (rx > width / 2) {
            rx = width / 2;
        }
        if (ry > height / 2) {
            ry = height / 2;
        }
        float widthMinusCorners = (width - (2 * rx));
        float heightMinusCorners = (height - (2 * ry));

        path.moveTo(right, top + ry);
        path.rQuadTo(0, -ry, -rx, -ry);
        path.rLineTo(-widthMinusCorners, 0);
        path.rQuadTo(-rx, 0, -rx, ry);
        path.rLineTo(0, heightMinusCorners);

        if (conformToOriginalPost) {
            path.rLineTo(0, ry);
            path.rLineTo(width, 0);
            path.rLineTo(0, -ry);
        } else {
            path.rQuadTo(0, ry, rx, ry);
            path.rLineTo(widthMinusCorners, 0);
            path.rQuadTo(rx, 0, rx, -ry);
        }

        path.rLineTo(0, -heightMinusCorners);
        path.close();
        return path;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Rect frame = this.frame;
        int width = frame.width();
        int height = frame.height();

        int left = frame.left;
        int top = frame.top;
        int right = frame.right;
        int bottom = frame.bottom;

        canvas.drawColor(maskColor);
        fillRectRound(left, top, right, bottom, 30, 30, false);
        canvas.drawPath(path, pen);
        canvas.drawPath(path, eraser);

//        locatorDrawable.setBounds(
//                (int) (left + 30f / 1006 * width),
//                (int) (top + (20f / 632) * height),
//                (int) (left + (303f / 1006) * width),
//                (int) (top + (416f / 632) * height));
//
//        if (locatorDrawable != null) {
//            locatorDrawable.draw(canvas);
//        }
    }


    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {

        // Create OES texture for storing camera preview data(YUV format)
        GLES20.glGenTextures(1, camTextureId, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTextureId[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        surfaceTexture = new SurfaceTexture(camTextureId[0]);
        surfaceTexture.setOnFrameAvailableListener(this);

        // Prepare vertex and texture coordinates
        int bytes = vertexCoords.length * Float.SIZE / Byte.SIZE;
        vertexCoordsBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureCoordsBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexCoordsBuffer.put(vertexCoords).position(0);
        textureCoordsBuffer.put(textureCoords).position(0);

        // Create vertex and fragment shaders
        // camTextureId->fboTexureId
        progCam2FBO = Utils.createShaderProgram(vss, fssCam2FBO);
        vcCam2FBO = GLES20.glGetAttribLocation(progCam2FBO, "vPosition");
        tcCam2FBO = GLES20.glGetAttribLocation(progCam2FBO, "vTexCoord");
        GLES20.glEnableVertexAttribArray(vcCam2FBO);
        GLES20.glEnableVertexAttribArray(tcCam2FBO);
        // fboTexureId/drawTexureId -> screen
        progTex2Screen = Utils.createShaderProgram(vss, fssTex2Screen);
        vcTex2Screen = GLES20.glGetAttribLocation(progTex2Screen, "vPosition");
        tcTex2Screen = GLES20.glGetAttribLocation(progTex2Screen, "vTexCoord");
        GLES20.glEnableVertexAttribArray(vcTex2Screen);
        GLES20.glEnableVertexAttribArray(tcTex2Screen);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        openCamera();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (surfaceTexture == null) {
            return;
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        surfaceTexture.updateTexImage();
        float matrix[] = new float[16];
        surfaceTexture.getTransformMatrix(matrix);

        // camTextureId->fboTexureId
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, textureWidth, textureHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progCam2FBO);
        GLES20.glVertexAttribPointer(vcCam2FBO, 2, GLES20.GL_FLOAT, false, 4 * 2, vertexCoordsBuffer);
        textureCoordsBuffer.clear();
        textureCoordsBuffer.put(transformTextureCoordinates(textureCoords, matrix));
        textureCoordsBuffer.position(0);
        GLES20.glVertexAttribPointer(tcCam2FBO, 2, GLES20.GL_FLOAT, false, 4 * 2, textureCoordsBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTextureId[0]);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progCam2FBO, "sTexture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glFlush();

        // Check if the draw texture is set
        int targetTexureId = fboTexureId[0];
        if (onTextureChangedListener != null) {
            boolean modified = onTextureChangedListener.onTextureChanged(fboTexureId[0], drawTexureId[0],
                    textureWidth, textureHeight);
            if (modified) {
                targetTexureId = drawTexureId[0];
            }
        }

        // fboTexureId/drawTexureId->Screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progTex2Screen);
        GLES20.glVertexAttribPointer(vcTex2Screen, 2, GLES20.GL_FLOAT, false, 4 * 2, vertexCoordsBuffer);
        textureCoordsBuffer.clear();
        textureCoordsBuffer.put(textureCoords);
        textureCoordsBuffer.position(0);
        GLES20.glVertexAttribPointer(tcTex2Screen, 2, GLES20.GL_FLOAT, false, 4 * 2, textureCoordsBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex2Screen, "sTexture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glFlush();
    }

    private float[] transformTextureCoordinates(float[] coords, float[] matrix) {
        float[] result = new float[coords.length];
        float[] vt = new float[4];
        for (int i = 0; i < coords.length; i += 2) {
            float[] v = {coords[i], coords[i + 1], 0, 1};
            Matrix.multiplyMV(vt, 0, matrix, 0, v, 0);
            result[i] = vt[0];
            result[i + 1] = vt[1];
        }
        return result;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        requestRender();
    }

    public void disableCamera() {
        disableCamera = true;
    }

    public void switchCamera() {
        releaseCamera();
        selectedCameraId = (selectedCameraId + 1) % numberOfCameras;
        openCamera();
    }

    public void openCamera() {
        if (disableCamera) {
            return;
        }
        camera = Camera.open(selectedCameraId);
        List<Size> supportedPreviewSizes = camera.getParameters().getSupportedPreviewSizes();
        Size previewSize = Utils.getOptimalPreviewSize(supportedPreviewSizes, EXPECTED_PREVIEW_WIDTH,
                EXPECTED_PREVIEW_HEIGHT);
        Camera.Parameters parameters = camera.getParameters();
        parameters.setPreviewSize(previewSize.width, previewSize.height);
        if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        camera.setParameters(parameters);
        int degree = Utils.getCameraDisplayOrientation(context, selectedCameraId);
        camera.setDisplayOrientation(degree);
        boolean rotate = degree == 90 || degree == 270;
        textureWidth = rotate ? previewSize.height : previewSize.width;
        textureHeight = rotate ? previewSize.width : previewSize.height;
        // Destroy FBO and draw textures
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fbo, 0);
        GLES20.glDeleteTextures(1, drawTexureId, 0);
        GLES20.glDeleteTextures(1, fboTexureId, 0);
        // Normal texture for storing modified camera preview data(RGBA format)
        GLES20.glGenTextures(1, drawTexureId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, drawTexureId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, textureWidth, textureHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        // FBO texture for storing camera preview data(RGBA format)
        GLES20.glGenTextures(1, fboTexureId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexureId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, textureWidth, textureHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        // Generate FBO and bind to FBO texture
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                fboTexureId[0], 0);
        try {
            camera.setPreviewTexture(surfaceTexture);
        } catch (IOException exception) {
            Log.e(TAG, "IOException caused by setPreviewDisplay()", exception);
        }
        camera.startPreview();
    }

    public void releaseCamera() {
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }
}
