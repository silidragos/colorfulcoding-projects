package com.colorfulcoding.lowresscanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.JsonReader;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.PointCloud;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.ux.ArFragment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Text;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MainActivity extends AppCompatActivity {

    private final String TAG="LOW_RES_SCANNER";
    private final float MIN_DIST_THRESHOLD = 0.01f; // 1cm
    private ArFragment fragment;
    private TextView debugText;

    private WorldToScreenTranslator worldToScreenTranslator;

    private List<Float[]> positions3D;
    private List<Integer[]> colorsRGB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);
        findViewById(R.id.test_but).setOnClickListener(this::testingMethod);
        findViewById(R.id.save_but).setOnClickListener(this::createJsonFromFeaturePoints);

        debugText = (TextView) findViewById(R.id.text_debug);

        worldToScreenTranslator = new WorldToScreenTranslator();

        positions3D = new ArrayList<>();
        colorsRGB = new ArrayList<>();
    }

    private boolean scanning = false;
    private void testingMethod(View v){
        if(scanning){
            scanning = false;
            return;
        }
        if(fragment.getArSceneView().getSession() == null){
            Toast.makeText(this, "No session found", Toast.LENGTH_SHORT);
            return;
        }

        if(fragment.getArSceneView().getArFrame() == null){
            Toast.makeText(this, "No frame found!", Toast.LENGTH_SHORT);
        }


        scanning = true;
        //X Y Z confidence
        //84-604
        PointCloudNode pcNode = new PointCloudNode(getApplicationContext());
        fragment.getArSceneView().getScene().addChild(pcNode);

        fragment.getArSceneView().getScene().addOnUpdateListener(frameTime -> {
            if(!scanning) return;

            PointCloud pc = fragment.getArSceneView().getArFrame().acquirePointCloud();
            pcNode.update(pc);

            try {
                FloatBuffer points = pc.getPoints();
                Log.i(TAG, "" + points.limit());

                for(int i=0; i< points.limit(); i+=4) {
                    float[] w = new float[]{points.get(i), points.get(i + 1), points.get(i + 2)};

                    //Test if the feature point is not stored already.
                    Optional<Float> minDist = positions3D.stream()
                            .map(vec -> this.squaredDistance(vec, w))
                            .min((d1, d2) -> d1 - d2 < 0? -1:1);
                    if (minDist.orElse(1000f) < MIN_DIST_THRESHOLD * MIN_DIST_THRESHOLD){
                        continue;
                    }

                    int[] color = getScreenPixel(w);
                    if(color == null || color.length != 3)
                        continue;

                    positions3D.add(new Float[]{points.get(i), points.get(i + 1), points.get(i + 2)});
                    colorsRGB.add(new Integer[]{color[0], color[1], color[2]});

                    debugText.setText("" + positions3D.size() + " points scanned.");

                    return;
                }
                //ResourceExhaustedException - Acquire failed because there are too many objects already acquired.
                // For example, the developer may acquire up to N point clouds.
                pc.release();
            } catch (NotYetAvailableException e) {
                Log.e(TAG, e.getMessage());
            }
        });
    }

    private float squaredDistance(Float[] v, float[] w){
        float sumSquare = 0;
        if(v.length != w.length) return -1;
        for(int i =0 ; i < v.length; i++){
            sumSquare += (v[i] - w[i]) * ((v[i] - w[i]));
        }
        return sumSquare;
    }


    int[] getScreenPixel(float[] worldPos) throws NotYetAvailableException {
//        int[] dims = fragment.getArSceneView().getArFrame().getCamera().getImageIntrinsics().getImageDimensions();
        Image img = fragment.getArSceneView().getArFrame().acquireCameraImage();

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        int width = displayMetrics.widthPixels;

        double[] pos2D = worldToScreenTranslator.worldToScreen(img.getWidth(), img.getHeight(), fragment.getArSceneView().getArFrame().getCamera(), worldPos);

        Bitmap bmp = imageToBitmap(img);

        //Otherwise the CPU will overload and crash
        img.close();

        if(pos2D[0] < 0 || pos2D[0] > bmp.getWidth() || pos2D[1] < 0 || pos2D[1] > bmp.getHeight()){
            return null;
        }

        int pixel = bmp.getPixel((int) pos2D[0], (int) pos2D[1]);
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);

        return new int[]{r,g,b};
    }

    private Bitmap imageToBitmap(Image image){
        int width = image.getWidth();
        int height = image.getHeight();

        byte[] nv21;
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        nv21 = new byte[ySize + uSize + vSize];

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, os);
        byte[] jpegByteArray = os.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.length);

        Matrix matrix = new Matrix();
        matrix.setRotate(90);

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void createJsonFromFeaturePoints(View v){
        try {
            JSONArray keypointJson = new JSONArray();
            for(int i=0;i<positions3D.size();i++){
                JSONObject point = new JSONObject();
                JSONArray position = new JSONArray(positions3D.get(i));
                JSONArray color = new JSONArray(colorsRGB.get(i));
                point.put("position", position);
                point.put("color", color);
                keypointJson.put(point);
            }

            JSONObject jsonParent = new JSONObject();
            jsonParent.put("keypoints", keypointJson);

            saveJsonToFile("pointcloud.json", jsonParent.toString());
            Toast.makeText(this, "Keypoints JSON saved to file!", Toast.LENGTH_SHORT);
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void saveJsonToFile(String filename, String json){
        File file = new File(this.getApplicationContext().getFilesDir(), "scanapp");
        if(!file.exists()){
            file.mkdir();
        }

        try {
            File jsonFile = new File(file, filename);
            FileWriter writer = new FileWriter(jsonFile);
            writer.append(json);
            writer.flush();
            writer.close();

            Toast.makeText(this,"JSON written to disk" + jsonFile.getAbsolutePath(), Toast.LENGTH_LONG);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}