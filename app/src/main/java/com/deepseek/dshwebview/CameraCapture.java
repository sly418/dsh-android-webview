package com.deepseek.dshwebview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static photo capture via the framework Camera2 API (no AndroidX dependency).
 * Captures one JPEG frame, saves it to the app-private directory, and returns
 * base64 for the MCP tool to hand to the model as image content.
 */
public final class CameraCapture {

    private static final String TAG = "DshCameraCapture";
    private static final long TIMEOUT_MS = 8000;

    private final Context context;

    public CameraCapture(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Result of a capture attempt. */
    public static final class Result {
        public final boolean ok;
        public final String base64;
        public final String path;
        public final String error;

        private Result(boolean ok, String base64, String path, String error) {
            this.ok = ok;
            this.base64 = base64;
            this.path = path;
            this.error = error;
        }

        static Result fail(String error) {
            return new Result(false, null, null, error);
        }

        static Result success(String base64, String path) {
            return new Result(true, base64, path, null);
        }
    }

    @SuppressLint("MissingPermission")
    public Result capture(boolean front, int jpegQuality) {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return Result.fail("CAMERA permission not granted");
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return Result.fail("no CameraManager");

        HandlerThread thread = new HandlerThread("dsh-camera");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        try {
            String[] ids = manager.getCameraIdList();
            String cameraId = chooseCamera(manager, ids, front);
            if (cameraId == null) return Result.fail("no suitable camera");

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            android.util.Size size = chooseSize(chars);
            if (size == null) return Result.fail("no supported JPEG size");

            final ImageReader reader = ImageReader.newInstance(
                    size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            final CountDownLatch imageLatch = new CountDownLatch(1);
            final AtomicReference<byte[]> captured = new AtomicReference<>();
            reader.setOnImageAvailableListener(r -> {
                Image image = r.acquireLatestImage();
                if (image != null) {
                    try {
                        ByteBuffer buf = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        captured.set(bytes);
                    } finally {
                        image.close();
                    }
                }
                imageLatch.countDown();
            }, handler);

            final CountDownLatch openLatch = new CountDownLatch(1);
            final AtomicReference<CameraDevice> deviceRef = new AtomicReference<>();
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    deviceRef.set(camera);
                    openLatch.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    openLatch.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.w(TAG, "camera open error: " + error);
                    openLatch.countDown();
                }
            }, handler);

            if (!openLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Result.fail("camera open timeout");
            }
            CameraDevice device = deviceRef.get();
            if (device == null) return Result.fail("camera open failed");

            try {
                CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                request.addTarget(reader.getSurface());
                request.set(CaptureRequest.JPEG_QUALITY, (byte) clamp(jpegQuality, 40, 95));
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                final CountDownLatch captureLatch = new CountDownLatch(1);
                device.createCaptureSession(java.util.Collections.singletonList(reader.getSurface()),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override
                                        public void onCaptureCompleted(
                                                CameraCaptureSession s,
                                                CaptureRequest req,
                                                android.hardware.camera2.TotalCaptureResult result) {
                                            captureLatch.countDown();
                                        }
                                    }, handler);
                                } catch (CameraAccessException e) {
                                    Log.w(TAG, "capture failed: " + e.getMessage());
                                    captureLatch.countDown();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                captureLatch.countDown();
                            }
                        }, handler);

                if (!captureLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return Result.fail("capture timeout");
                }
                if (!imageLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return Result.fail("image read timeout");
                }
            } finally {
                device.close();
            }

            byte[] jpeg = captured.get();
            if (jpeg == null || jpeg.length == 0) return Result.fail("no image data");

            File dir = new File(context.getFilesDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) return Result.fail("cannot create camera dir");
            File out = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(jpeg);
            }
            String base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
            return Result.success(base64, out.getAbsolutePath());
        } catch (CameraAccessException | InterruptedException | java.io.IOException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.e(TAG, "capture error: " + e.getMessage());
            return Result.fail(String.valueOf(e.getMessage()));
        } finally {
            thread.quitSafely();
        }
    }

    private static String chooseCamera(CameraManager manager, String[] ids, boolean front)
            throws CameraAccessException {
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing == null) continue;
            if (front && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
            if (!front && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private static android.util.Size chooseSize(CameraCharacteristics chars) {
        android.hardware.camera2.params.StreamConfigurationMap map =
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        android.util.Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return null;
        // Prefer a moderate size: enough for vision, small enough for base64.
        android.util.Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (android.util.Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            long target = 1280L * 720L;
            long score = Math.abs(pixels - target);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
