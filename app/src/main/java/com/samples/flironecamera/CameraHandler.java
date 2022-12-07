/*******************************************************************
 * @title FLIR THERMAL SDK
 * @file CameraHandler.java
 * @Author FLIR Systems AB
 *
 * @brief Helper class that encapsulates *most* interactions with a FLIR ONE camera
 *
 * Copyright 2019:    FLIR Systems
 ********************************************************************/
package com.samples.flironecamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;

import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.image.palettes.Palette;
import com.flir.thermalsdk.image.palettes.PaletteManager;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.streaming.ThermalImageStreamListener;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.android.gms.vision.face.Landmark;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Encapsulates the handling of a FLIR ONE camera or built in emulator, discovery, connecting and start receiving images.
 * All listeners are called from Thermal SDK on a non-ui thread
 * <p/>
 * Usage:
 * <pre>
 * Start discovery of FLIR FLIR ONE cameras or built in FLIR ONE cameras emulators
 * {@linkplain #startDiscovery(DiscoveryEventListener, DiscoveryStatus)}
 * Use a discovered Camera {@linkplain Identity} and connect to the Camera
 * (note that calling connect is blocking and it is mandatory to call this function from a background thread):
 * {@linkplain #connect(Identity, ConnectionStatusListener)}
 * Once connected to a camera
 * {@linkplain #startStream(StreamDataListener)}
 * </pre>
 * <p/>
 * You don't *have* to specify your application to listen or USB intents but it might be beneficial for you application,
 * we are enumerating the USB devices during the discovery process which eliminates the need to listen for USB intents.
 * See the Android documentation about USB Host mode for more information
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    private StreamDataListener streamDataListener;

    private  DataRecord dataRecord;


    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);
        void images(Bitmap msxBitmap, Bitmap dcBitmap);
    }

    public interface DataRecord{
        void record(FrameDataHolder dataHolder);
        void record(Bitmap thermalBitmap, Bitmap rgbBitmap);
    }

    //Discovered FLIR cameras
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    //A FLIR Camera
    private Camera camera;

    LinkedList<Double> thermalLinkedList = new LinkedList<Double>();
    int count = 0;
    int ITERATIVE_MAXIMUM = 200;

    Context context;


    public interface DiscoveryStatus {
        void started();
        void stopped();
    }

    public CameraHandler(Context context) {
        this.context = context;
    }

    /**
     * Start discovery of USB and Emulators
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Stop discovery of USB and Emulators
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        camera = new Camera();
        camera.connect(identity, connectionStatusListener);
    }

    public void disconnect() {
        if (camera == null) {
            return;
        }
        if (camera.isGrabbing()) {
            camera.unsubscribeAllStreams();
        }
        camera.disconnect();
    }

    /**
     * Start a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        camera.subscribeStream(thermalImageStreamListener);
    }

    public void startRecord(DataRecord listener){
        this.dataRecord = listener;
        camera.subscribeStream(thermalImageRecordListener);
    }

    /**
     * Stop a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void stopStream(ThermalImageStreamListener listener) {
        camera.unsubscribeStream(listener);
    }
    public void stopRecord(ThermalImageStreamListener listener) {
        camera.unsubscribeStream(listener);
    }

    public void stopRc(){
        stopRecord(thermalImageStreamListener);
    }

    /**
     * Add a found camera to the list of known cameras
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity get(int i) {
        return foundCameraIdentities.get(i);
    }

    /**
     * Get a read only list of all found cameras
     */
    @Nullable
    public List<Identity> getCameraList() {
        return Collections.unmodifiableList(foundCameraIdentities);
    }

    /**
     * Clear all known network cameras
     */
    public void clear() {
        foundCameraIdentities.clear();
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("C++ Emulator")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOne() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            boolean isFlirOneEmulator = foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE");
            boolean isCppEmulator = foundCameraIdentity.deviceId.contains("C++ Emulator");
            if (!isFlirOneEmulator && !isCppEmulator) {
                return foundCameraIdentity;
            }
        }

        return null;
    }

    private void withImage(ThermalImageStreamListener listener, Camera.Consumer<ThermalImage> functionToRun) {
        camera.withImage(listener, functionToRun);
    }


    /**
     * Called whenever there is a new Thermal Image available, should be used in conjunction with {@link Camera.Consumer}
     */
    private final ThermalImageStreamListener thermalImageStreamListener = new ThermalImageStreamListener() {
        @Override
        public void onImageReceived() {
            //Will be called on a non-ui thread
            Log.d(TAG, "onImageReceived(), we got another ThermalImage");
            withImage(this, handleIncomingImage);
//
        }
    };

    private final ThermalImageStreamListener thermalImageRecordListener = new ThermalImageStreamListener() {
        @Override
        public void onImageReceived() {
            //Will be called on a non-ui thread
            Log.d(TAG, "onImageReceived(), we got another ThermalImage");
            withImage(this, handleRecordImage);
        }
    };


    /**
     * Function to process a Thermal Image and update UI
     */
    private final Camera.Consumer<ThermalImage> handleIncomingImage = new Camera.Consumer<ThermalImage>() {
        @Override
        public void accept(ThermalImage thermalImage) {
            Log.d(TAG, "accept() called with: thermalImage = [" + thermalImage.getDescription() + "]");
            //Will be called on a non-ui thread,
            // extract information on the background thread and send the specific information to the UI thread
            //Get a bitmap with only IR data
            Bitmap thermalBitmap;
            Palette palette = PaletteManager.getDefaultPalettes().get(0);
            {
                thermalImage.getFusion().setFusionMode(FusionMode.THERMAL_ONLY);
                thermalImage.setPalette(palette);
                thermalBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();
            }
            //Get a bitmap with the visual image, it might have different dimensions then the bitmap from THERMAL_ONLY
            Bitmap rgbBitmap;
            {
                thermalImage.getFusion().setFusionMode(FusionMode.VISUAL_ONLY);
                rgbBitmap = BitmapAndroid.createBitmap(thermalImage.getFusion().getPhoto()).getBitMap();
            }
            streamDataListener.images(thermalBitmap, rgbBitmap);
        }
    };

    private final Camera.Consumer<ThermalImage> handleRecordImage = new Camera.Consumer<ThermalImage>() {
        @Override
        public void accept(ThermalImage thermalImage) {
            Log.d(TAG, "accept() called with: thermalImage = [" + thermalImage.getDescription() + "]");
            //Will be called on a non-ui thread,
            // extract information on the background thread and send the specific information to the UI thread
            //Get a bitmap with only IR data
//            stopStream(thermalImageStreamListener);

            Bitmap thermalBitmap;
            Palette palette = PaletteManager.getDefaultPalettes().get(0);
            {
                thermalImage.getFusion().setFusionMode(FusionMode.THERMAL_ONLY);
                thermalImage.setPalette(palette);
                thermalBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();
            }
            //Get a bitmap with the visual image, it might have different dimensions then the bitmap from THERMAL_ONLY
            Bitmap rgbBitmap;
            {
                thermalImage.getFusion().setFusionMode(FusionMode.VISUAL_ONLY);
                rgbBitmap = BitmapAndroid.createBitmap(thermalImage.getFusion().getPhoto()).getBitMap();
            }
//            new Thread(() ->{
            Bitmap cropRgbBitmap = Bitmap.createBitmap(rgbBitmap, 65, 160, 960, 1280);
            Canvas rgbCanvas = new Canvas(cropRgbBitmap);
            rgbCanvas.drawBitmap(cropRgbBitmap, 0, 0, null);
            Canvas thermalCanvas = new Canvas(thermalBitmap);
            thermalCanvas.drawBitmap(thermalBitmap, 0, 0, null);


            Paint myRectPaint = new Paint();
            myRectPaint.setStrokeWidth(5);
            myRectPaint.setColor(Color.RED);
            myRectPaint.setStyle(Paint.Style.STROKE);

            FaceDetector faceDetector = new FaceDetector
                    .Builder(context)
                    .setTrackingEnabled(false)
                    .setProminentFaceOnly(true)
                    .setMode(FaceDetector.FAST_MODE)
                    .setLandmarkType(FaceDetector.ALL_LANDMARKS)
                    .build();
            Paint hidungPaint;
            hidungPaint = new Paint();
            hidungPaint.setStrokeWidth(3);
            hidungPaint.setColor(Color.GREEN);
            hidungPaint.setStyle(Paint.Style.STROKE);
//            for(int j = 0; j<ITERATIVE_MAXIMUM; j++){

                if(faceDetector.isOperational()) {
                    Frame frame = new Frame.Builder().setBitmap(cropRgbBitmap).build();
                    SparseArray<Face> faces = faceDetector.detect(frame);
                    faceDetector.release();
                    if (faces.size() > 0) {
                        count++;
                        if (count == (ITERATIVE_MAXIMUM + 1)) {
                            count = 0;
                            thermalLinkedList.add(0, 0.0);
                        }
                        //Draw Rectangles on the Faces
                        for (int i = 0; i < faces.size(); i++) {
                            Face thisFace = faces.valueAt(i);
                            float x1 = thisFace.getPosition().x;
                            float y1 = thisFace.getPosition().y;
                            float x2 = x1 + thisFace.getWidth();
                            float y2 = y1 + thisFace.getHeight();
                            if (x1 <= 1)
                                x1 = 1;
                            if (x1 >= cropRgbBitmap.getWidth())
                                x1 = cropRgbBitmap.getWidth();
                            if (y1 <= 1)
                                y1 = 1;
                            if (y1 >= cropRgbBitmap.getHeight())
                                y1 = cropRgbBitmap.getHeight();
                            if (x2 <= 1)
                                x2 = 1;
                            if (x2 >= cropRgbBitmap.getWidth())
                                x2 = cropRgbBitmap.getWidth();
                            if (y2 <= 1)
                                y2 = 1;
                            if (y2 >= cropRgbBitmap.getHeight())
                                y2 = cropRgbBitmap.getHeight();

                            rgbCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
                            thermalCanvas.drawRoundRect(new RectF(x1 / 2, y1 / 2, x2 / 2, y2 / 2), 2, 2, hidungPaint);

                            float rgbWidth = rgbCanvas.getWidth();
                            float rgbHeight = rgbCanvas.getHeight();

                            for (Landmark landmark : thisFace.getLandmarks()) {
                                if (landmark.getType() == Landmark.NOSE_BASE) {
                                    int cx = (int) (landmark.getPosition().x * drawBitmap(rgbCanvas, cropRgbBitmap));
                                    int cy = (int) (landmark.getPosition().y * drawBitmap(rgbCanvas, cropRgbBitmap));
                                    float skalaWidth = skalaWidth(rgbWidth);
                                    float skalaHeight = skalaHeight(rgbHeight);
                                    float cLeft = cx - skalaWidth + 60;
                                    float cRight = cx + skalaWidth - 60;
                                    float cBottom = cy + skalaHeight - 80;

                                    if (cLeft <= x1) {
                                        cLeft = x1;
                                    }
                                    if (cRight >= x2) {
                                        cRight = x2;
                                    }
                                    if (cBottom >= y2) {
                                        cBottom = y2;
                                    }
                                    rgbCanvas.drawRoundRect(new RectF(cLeft, cy, cRight, cBottom), 2, 2, hidungPaint);
                                    thermalCanvas.drawRoundRect(new RectF(cLeft / 2, cy / 2, cRight / 2, cBottom / 2), 2, 2, hidungPaint);

                                    float wBlock = Math.abs(cRight - cLeft) / 4;
                                    float modBlock = Math.abs(cRight - cLeft) % 4;

                                    float sBlock = cLeft;
                                    float srBlock = cLeft + wBlock;
                                    double saveVarianceBlock = -100000;
                                    double saveTempBlock = -100000;

                                    for (int k = 0; k < 4; k++) {
                                        rgbCanvas.drawRoundRect(new RectF(sBlock, cy, srBlock, cBottom), 2, 2, hidungPaint);
                                        thermalCanvas.drawRoundRect(new RectF(sBlock / 2, cy / 2, srBlock / 2, cBottom / 2), 2, 2, hidungPaint);
                                        Rectangle tempBlock = new Rectangle((int) sBlock / 2, cy / 2, (int) wBlock, Math.abs((int) (cBottom / 2) - (cy / 2)));

                                        double[] temperatureBlock = thermalImage.getValues(tempBlock);
                                        double varianBlock = getVarianceNostril(temperatureBlock);
                                        if (varianBlock > saveVarianceBlock) {
                                            saveVarianceBlock = varianBlock;
                                            saveTempBlock = getMean(temperatureBlock) - 273.15;
                                        }

                                        sBlock = srBlock;
                                        if (k == 3) {
                                            wBlock = wBlock + modBlock;
                                        }
                                        srBlock = srBlock + wBlock;
                                    }

                                    thermalLinkedList.add(count,saveTempBlock);
                                }
                            }
                        }
                    }else{
                        thermalLinkedList.add(0,0.0 );
                    }
                    dataRecord.record(thermalBitmap, cropRgbBitmap);
    //            }).start();
                }
//            }
        }

        public double getVarianceNostril(double[] temperatureBlock){
            double hasil = 0;
            double mean = getMean(temperatureBlock);
            for(int i = 0; i<temperatureBlock.length; i++){
                double hasilKuadrat = Math.pow((temperatureBlock[i] - mean),2);
                hasil = hasil + hasilKuadrat;
            }
            return hasil/ temperatureBlock.length;
        }

        public double getMean(double[] data){
            double jumlah = 0;
            for(int i=0; i<data.length; i++){
                jumlah = jumlah + data[i];
            }

            double mean = jumlah/data.length;
            return  mean;
        }

        public float skalaWidth(float rgbWidth){
            float width = (4 * rgbWidth) / 11;
            return width / 2;
        }
        public float skalaHeight(float rgbHeight) {
            return rgbHeight / 8;
        }

        public double drawBitmap(Canvas canvas, Bitmap bitmap){
            double viewWidth = canvas.getWidth();
            double viewHeight = canvas.getHeight();
            double imageWidth = bitmap.getWidth();
            double imageHeigth = bitmap.getHeight();

            double scale = Math.min(viewWidth/imageWidth, viewHeight/imageHeigth);
            return  scale;
        }
    };
}
