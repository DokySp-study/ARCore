/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.cloudanchor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.GuardedBy;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.CloudAnchorMode;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.cloudanchor.PrivacyNoticeDialogFragment.HostResolveListener;
import com.google.ar.core.examples.java.cloudanchor.PrivacyNoticeDialogFragment.NoticeDialogListener;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.common.base.Preconditions;
import com.google.firebase.database.DatabaseError;
import java.io.IOException;
import java.sql.Array;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Main Activity for the Cloud Anchor Example
 *
 * <p>This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
public class CloudAnchorActivity extends AppCompatActivity
    implements GLSurfaceView.Renderer, NoticeDialogListener {
  private static final String TAG = CloudAnchorActivity.class.getSimpleName();

  private enum HostResolveMode {
    NONE,
    HOSTING,
    RESOLVING,
  }

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();


//  private final ObjectRenderer virtualObject = new ObjectRenderer();
//  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
  private ArrayList<ObjectRenderer> virtualObjectList = new ArrayList<ObjectRenderer>();
  private ArrayList<ObjectRenderer> virtualObjectShadowList = new ArrayList<ObjectRenderer>();

  final String[][] modelFileNames = {
          {"models/tree.obj", "models/default_diffuse.png"},
          {"models/table.obj", "models/default_diffuse.png"},
          {"models/chair.obj", "models/default_diffuse.png"},
          {"models/couch.obj", "models/default_diffuse.png"},
          {"models/andy.obj", "models/andy.png"},
  };
  final String[][] modelShadowFileNames = { // TODO: andy ?????? ?????? ???????????? ???????????? ??????
          {"models/andy_shadow.obj", "models/andy_shadow.png"},
          {"models/andy_shadow.obj", "models/andy_shadow.png"},
          {"models/andy_shadow.obj", "models/andy_shadow.png"},
          {"models/andy_shadow.obj", "models/andy_shadow.png"},
          {"models/andy_shadow.obj", "models/andy_shadow.png"},
  };
  private final float[] scaleFactors = {
          0.25f,
          0.01f,
          0.01f,
          0.01f,
          2.0f,
  };
  private static final float[][] objectColors = {
          new float[]{84.0f, 107.0f, 53.0f, 255.0f}, // tree
          new float[]{200.0f, 200.0f, 200.0f, 255.0f}, // table
          new float[]{47.0f, 85.0f, 151.0f, 255.0f}, // chair
          new float[]{161.0f, 64.0f, 0.0f, 255.0f}, // couch
          new float[]{139.0f, 195.0f, 74.0f, 255.0f}, // andy
  };


  private ArrayList<Integer> objectIndexQueue = new ArrayList<Integer>();


  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  private boolean installRequested;

  // Temporary matrices allocated here to reduce number of allocations for each frame.
  private final ArrayList<float[]> anchorMatrixList = new ArrayList<float[]>(16);
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];

  // Locks needed for synchronization
  private final Object singleTapLock = new Object();
  private final Object anchorLock = new Object();
  private final Object resolveListenerLock = new Object();

  // Tap handling and UI.
  private GestureDetector gestureDetector;
  private final SnackbarHelper snackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private Button hostButton;
  private Button resolveButton;
  private TextView roomCodeText;
  private SharedPreferences sharedPreferences;
  private static final String PREFERENCE_FILE_KEY = "allow_sharing_images";

  private Button model0Button;
  private Button model1Button;
  private Button model2Button;
  private Button model3Button;
  private Button model4Button;
  private int selectedObjectIndex = 0;

  /** ????????? ????????? ?????? ?????? ?????? */
  private static final String ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES";




  @GuardedBy("singleTapLock")
  private MotionEvent queuedSingleTap;

  private Session session;

  @GuardedBy("anchorLock")
  private ArrayList<Anchor> anchors = new ArrayList<Anchor>();
  private ArrayList<String> cloudAnchors = new ArrayList<String>();

//  @GuardedBy("resolveListenerLock")



  // Cloud Anchor Components.
  private FirebaseManager firebaseManager;
  private final CloudAnchorManager cloudManager = new CloudAnchorManager();
  private HostResolveMode currentMode;
  private RoomCodeAndCloudAnchorIdListener hostListener;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    // Open GL??? ????????? ???????????? ???
    surfaceView = findViewById(R.id.surfaceview);

    // ?????? ?????? ??????
    displayRotationHelper = new DisplayRotationHelper(this);


    // ????????? ?????? ????????? ??????
    gestureDetector =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                  // ????????? ????????? ??? ?????? MotionEvent ??? ??????
                  if (currentMode == HostResolveMode.HOSTING) {
                    queuedSingleTap = e;
                  }
                }
                return true;
              }

              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }
            });
    surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

    // GL ????????? ??????
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);
    installRequested = false;

    // UI Framework ??????
    hostButton = findViewById(R.id.host_button);
    hostButton.setOnClickListener((view) -> onHostButtonPress());
    resolveButton = findViewById(R.id.resolve_button);
    resolveButton.setOnClickListener((view) -> onResolveButtonPress());
    roomCodeText = findViewById(R.id.room_code_text);

    // ?????? ?????? ??????
    model0Button = findViewById(R.id.model1);
    model0Button.setOnClickListener((view) -> onModelButtonPress(0));
    model1Button = findViewById(R.id.model2);
    model1Button.setOnClickListener((view) -> onModelButtonPress(1));
    model2Button = findViewById(R.id.model3);
    model2Button.setOnClickListener((view) -> onModelButtonPress(2));
    model3Button = findViewById(R.id.model4);
    model3Button.setOnClickListener((view) -> onModelButtonPress(3));
    model4Button = findViewById(R.id.model5);
    model4Button.setOnClickListener((view) -> onModelButtonPress(4));
    onModelButtonPress(0);

    // Cloud Anchor ??????
    firebaseManager = new FirebaseManager(this);

    // HOST, RESOLVE ?????? ??????
    currentMode = HostResolveMode.NONE;
    sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, Context.MODE_PRIVATE);
  }


  @Override
  protected void onDestroy() {
    // Clear all registered listeners.
    resetMode();

    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
      createSession();
    }
    snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message));
    surfaceView.onResume();

    // ?????? ?????? ??????
    displayRotationHelper.onResume();
  }


  // AR ?????? ??????
  private void createSession() {
    if (session == null) {
      Exception exception = null;
      int messageId = -1;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }


        // ARCore ????????? ?????? ??????
        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // ?????? ??????
        session = new Session(this);

      } catch (UnavailableArcoreNotInstalledException e) {
        messageId = R.string.snackbar_arcore_unavailable;
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        messageId = R.string.snackbar_arcore_too_old;
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        messageId = R.string.snackbar_arcore_sdk_too_old;
        exception = e;
      } catch (Exception e) {
        messageId = R.string.snackbar_arcore_exception;
        exception = e;
      }

      if (exception != null) {
        snackbarHelper.showError(this, getString(messageId));
        Log.e(TAG, "Exception creating session", exception);
        return;
      }

      // Create default config and check if supported.
      Config config = new Config(session);
      config.setCloudAnchorMode(CloudAnchorMode.ENABLED);
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
      session.configure(config);

      // Setting the session in the HostManager.
      cloudManager.setSession(session);
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable));
      session = null;
      return;
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.

      // ?????? ?????? ??????
      displayRotationHelper.onPause();

      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }


  /**
   * Handles the most recent user tap.
   *
   * <p>We only ever handle one tap at a time, since this app only allows for a single anchor.
   *
   * @param frame the current AR frame
   * @param cameraTrackingState the current camera tracking state
   */
  private void handleTap(Frame frame, TrackingState cameraTrackingState) {
    // Handle taps. Handling only one tap per frame, as taps are usually low frequency
    // compared to frame rate.
    // ????????? ?????? ??? ?????? ?????????

    synchronized (singleTapLock) { // singleTapLock mutex lock

      synchronized (anchorLock) { // anchor mutex lock

        // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
        // camera is currently tracking.

        // ????????? ????????? ??????, single tap queue??? ???????????? ?????? ???, camera??? tracking ????????? ???

        // ?????? ?????? ??? ??????
//        if (anchor == null  &&  queuedSingleTap != null  &&  cameraTrackingState == TrackingState.TRACKING) {
        if (queuedSingleTap != null  &&  cameraTrackingState == TrackingState.TRACKING) {

          //////////////////////////////////////////////////////////////////////////////////////////
          //////////////////////////////////////////////////////////////////////////////////////////
          // HOST??? ??????, ?????? ??????
          // ????????? ?????? ???????????? IllegalStateException

          // TODO: resolver??? ?????? ??????????????? ???
          Preconditions.checkState(
              currentMode == HostResolveMode.HOSTING,
              "We should only be creating an anchor in hosting mode.");

          // for (var a in array){} ???????????? ??????
          for (HitResult hit : frame.hitTest(queuedSingleTap)) {

            // Ray casting ??? ???????????? ?????? ????????? ????????? ??????
            if (shouldCreateAnchorWithHit(hit)) {

              // hit: HitResult ?????? ?????? ??? ????????? ?????? ??????
              Anchor newAnchor = hit.createAnchor();

              // hostListener
              Preconditions.checkNotNull(hostListener, "The host listener cannot be null.");

              // ????????? ????????? host??? ??????
              cloudManager.hostCloudAnchor(newAnchor, hostListener);

              // anchor??? ????????? ???????????? ??????
              setNewAnchor(newAnchor, selectedObjectIndex);

              if(snackbarHelper.isShowing()){
                snackbarHelper.hide(this);
              }
              snackbarHelper.showMessage(this, getString(R.string.snackbar_anchor_placed));

              break; // Only handle the first valid hit.
            }
          }
        }
      }
      queuedSingleTap = null;
    }
  }


  /** Returns {@code true} if and only if the hit can be used to create an Anchor reliably. */
  private static boolean shouldCreateAnchorWithHit(HitResult hit) {
    Trackable trackable = hit.getTrackable();
    if (trackable instanceof Plane) {
      // Check if the hit was within the plane's polygon.
      return ((Plane) trackable).isPoseInPolygon(hit.getHitPose());
    } else if (trackable instanceof Point) {
      // Check if the hit was against an oriented point.
      return ((Point) trackable).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL;
    }
    return false;
  }


  // ?????? ?????? ??? ?????? ????????? ??? ?????????
  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);

      // ????????? ?????? ??? ????????? ??????
      // ?????? ?????? ??????
      // virtualObject -> ????????? ???????????? ?????? idx queue??? ???????????? ??????

      for(int i = 0; i < modelFileNames.length; i++){

        ObjectRenderer virtualObject = new ObjectRenderer();
        virtualObject.createOnGlThread(this, modelFileNames[i][0], modelFileNames[i][1]);
        virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

        ObjectRenderer virtualObjectShadow = new ObjectRenderer();
        virtualObjectShadow.createOnGlThread(this, modelShadowFileNames[i][0], modelShadowFileNames[i][1]);
        virtualObjectShadow.setBlendMode(BlendMode.Shadow);
        virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

        virtualObjectList.add(virtualObject);
        virtualObjectShadowList.add(virtualObjectShadow);

      }

    } catch (IOException ex) {
      Log.e(TAG, "Failed to read an asset file", ex);
    }
  }


  // ?????? ?????? ??? ?????? ??????????????? ??? ?????????
  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    // ?????? ?????? ??????
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }


  // ???????????? ?????? ??? (???????????? ????????? ?????????) ?????????
  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    // ??????
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    // ????????? ?????? ??? ?????? ??????
    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.

    // ?????? ?????? ??????
    displayRotationHelper.updateSessionIfNeeded(session);

    // ????????? ????????? ??????
    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();
      TrackingState cameraTrackingState = camera.getTrackingState();

      // Notify the cloudManager of all the updates.
      cloudManager.onUpdate();

      // Handle user input.
      handleTap(frame, cameraTrackingState);

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // If not tracking, don't draw 3d objects.
      if (cameraTrackingState == TrackingState.PAUSED) {
        return;
      }

      // Get camera and projection matrices.
      camera.getViewMatrix(viewMatrix, 0);
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

      // Visualize tracked points.
      // Use try-with-resources to automatically release the point cloud.
      try (PointCloud pointCloud = frame.acquirePointCloud()) {
        pointCloudRenderer.update(pointCloud);
        pointCloudRenderer.draw(viewMatrix, projectionMatrix);
      }

      // Visualize planes.
      planeRenderer.drawPlanes(
          session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);

      // Check if the anchor can be visualized or not, and get its pose if it can be.


      // ?????? ?????? ??? ??????
      // anchor??? anchorMatrix??? ???????????? ????????? ??????
      // anchor, anchorMatrix ??? ??? ????????? ???????????? ???
      boolean shouldDrawAnchor = false;
      synchronized (anchorLock) {

        anchorMatrixList.clear();

        for (Anchor anchor : anchors){
          if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) {
            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to session.update() as ARCore refines its estimate of the world.

            final float[] anchorMatrix = new float[16];
            anchor.getPose().toMatrix(anchorMatrix, 0);

            anchorMatrixList.add(anchorMatrix);
          }
        }

        if(!anchorMatrixList.isEmpty()){
          shouldDrawAnchor = true;
        } else {
          shouldDrawAnchor = false;
        }
      }

      // Visualize anchor.
      if (shouldDrawAnchor) {
        float[] colorCorrectionRgba = new float[4];
        frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

        // Update and draw the model and its shadow.
//        float scaleFactor = 0.01f;

        int ii = 0;

        for (float[] anchorMatrix : anchorMatrixList) {

          int targetObjectIndx = objectIndexQueue.get(ii);

          // ?????? obj??? ????????????
          ObjectRenderer virtualObject = virtualObjectList.get(targetObjectIndx);
          ObjectRenderer virtualObjectShadow = virtualObjectShadowList.get(targetObjectIndx);

          virtualObject.updateModelMatrix(anchorMatrix, scaleFactors[targetObjectIndx]);
          virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactors[targetObjectIndx]);
          virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, objectColors[targetObjectIndx]);
          virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, objectColors[targetObjectIndx]);

          ii++;
        }
      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }


  /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null. */
  private void setNewAnchor(Anchor newAnchor, int objectIndex) {
    synchronized (anchorLock) {
      // ?????? ?????? ??? ??????
      // -> ?????? ????????? detach() ?????? ?????? ??????????????? ??????
//      if (anchor != null) {
//        anchor.detach();
//      }
//      anchor = newAnchor;

      anchors.add(newAnchor);
      objectIndexQueue.add(objectIndex);
    }
  }


  // cloud anchor id queue ??????
  private void setNewCloudAnchor(Anchor newAnchor) {
    String cloudAnchorId = newAnchor.getCloudAnchorId();
    if(!cloudAnchorId.isEmpty()) {
      cloudAnchors.add(newAnchor.getCloudAnchorId());
    }
  }

  /**
   * ## ?????? ????????? ?????????
   * - anchors
   * - cloudAnchors
   * - anchorMatrixList
   * - objectIndexQueue
   */
  private void resetAnchors(){
    anchors.clear();
    cloudAnchors.clear();
    anchorMatrixList.clear();
    objectIndexQueue.clear();
  }


  /** Resets the mode of the app to its initial state and removes the anchors. */
  private void resetMode() {
    hostButton.setText(R.string.host_button_text);
    hostButton.setEnabled(true);
    resolveButton.setText(R.string.resolve_button_text);
    resolveButton.setEnabled(true);
    roomCodeText.setText(R.string.initial_room_code);
    currentMode = HostResolveMode.NONE;
    firebaseManager.clearRoomListener();
    hostListener = null;
    resetAnchors();
    snackbarHelper.hide(this);
    cloudManager.clearListeners();
  }


  // HOST ?????? ????????? ???
  private void onHostButtonPress() {

    // HOST ???????????? ??????
    if (currentMode == HostResolveMode.HOSTING) {
      resetMode();
      return;
    }

    // ALLOW_SHARE_IMAGES_KEY?
    if (!sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
      showNoticeDialog(this::onPrivacyAcceptedForHost);
    } else {
      onPrivacyAcceptedForHost();
    }
  }

  // RESOLVE ?????? ????????? ???
  private void onResolveButtonPress() {
    // RESOLVE ???????????? ??????
    if (currentMode == HostResolveMode.RESOLVING) {
      resetMode();
      return;
    }

    if (!sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
      showNoticeDialog(this::onPrivacyAcceptedForResolve);
    } else {
      onPrivacyAcceptedForResolve();
    }
  }

  private void onPrivacyAcceptedForHost() {
    if (hostListener != null) {
      return;
    }
    resolveButton.setEnabled(false);
    hostButton.setText(R.string.cancel);
    snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_host));

    hostListener = new RoomCodeAndCloudAnchorIdListener();
    firebaseManager.getNewRoomCode(hostListener);
  }

  private void onPrivacyAcceptedForResolve() {
    ResolveDialogFragment dialogFragment = new ResolveDialogFragment();
    dialogFragment.setOkListener(this::onRoomCodeEntered);
    dialogFragment.show(getSupportFragmentManager(), "ResolveDialog");
  }

  // ?????? ????????? ???, ?????? ??????
  private void onModelButtonPress(int index){
    selectedObjectIndex = index;

    switch (selectedObjectIndex){
      case 0:
        model0Button.setTextColor(Color.BLUE);
        model1Button.setTextColor(Color.GRAY);
        model2Button.setTextColor(Color.GRAY);
        model3Button.setTextColor(Color.GRAY);
        model4Button.setTextColor(Color.GRAY);
        break;
      case 1:
        model0Button.setTextColor(Color.GRAY);
        model1Button.setTextColor(Color.BLUE);
        model2Button.setTextColor(Color.GRAY);
        model3Button.setTextColor(Color.GRAY);
        model4Button.setTextColor(Color.GRAY);
        break;
      case 2:
        model0Button.setTextColor(Color.GRAY);
        model1Button.setTextColor(Color.GRAY);
        model2Button.setTextColor(Color.BLUE);
        model3Button.setTextColor(Color.GRAY);
        model4Button.setTextColor(Color.GRAY);
        break;
      case 3:
        model0Button.setTextColor(Color.GRAY);
        model1Button.setTextColor(Color.GRAY);
        model2Button.setTextColor(Color.GRAY);
        model3Button.setTextColor(Color.BLUE);
        model4Button.setTextColor(Color.GRAY);
        break;
      case 4:
        model0Button.setTextColor(Color.GRAY);
        model1Button.setTextColor(Color.GRAY);
        model2Button.setTextColor(Color.GRAY);
        model3Button.setTextColor(Color.GRAY);
        model4Button.setTextColor(Color.BLUE);
        break;
      default:
        break;
    }
  }

  private int prevQueueSize = 0;

  /** Callback function invoked when the user presses the OK button in the Resolve Dialog. */
  private void onRoomCodeEntered(Long roomCode) {
    currentMode = HostResolveMode.RESOLVING;
    hostButton.setEnabled(false);
    resolveButton.setText(R.string.cancel);
    roomCodeText.setText(String.valueOf(roomCode));
    snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve));


    // Register a new listener for the given room.
    firebaseManager.registerNewListenerForRoom(
        roomCode,
        // CloudAnchorIdListener::onNewCloudAnchorId(ArrayList<String>, ArrayList<Integer>)
        (cloudAnchorIdList, objectIdxList) -> {
          // When the cloud anchor ID is available from Firebase.
          CloudAnchorResolveStateListener resolveListener = new CloudAnchorResolveStateListener(roomCode);
          Preconditions.checkNotNull(resolveListener, "The resolve listener cannot be null.");

          //
          // ??? ?????? ?????? ?????? ???????????? ??? ???????????? ??????
          if(cloudAnchorIdList.size() == objectIdxList.size()) {
            // cloudAnchor??? ???????????? ????????? ???????????? ??? ??????
            if(prevQueueSize < cloudAnchorIdList.size()){

              // ?????? ?????? ?????? ???????????? ??????
              // ??????????????? ?????? ????????? ??????
              prevQueueSize = cloudAnchorIdList.size();
              resetAnchors();

              for (int ii=0; ii<cloudAnchorIdList.size(); ii++) {
                cloudManager.resolveCloudAnchor(cloudAnchorIdList.get(ii), objectIdxList.get(ii), resolveListener, SystemClock.uptimeMillis());
              }
            }
          }

          // ?????? ??????
          // ????????? ??????, cloud anchor ?????? ????????????
          // ?????? ??????, ????????? cloud anchor ??? ????????????

        });
  }

  /**
   * Listens for both a new room code and an anchor ID, and shares the anchor ID in Firebase with
   * the room code when both are available.
   */
  private final class RoomCodeAndCloudAnchorIdListener
      implements CloudAnchorManager.CloudAnchorHostListener, FirebaseManager.RoomCodeListener {

    private Long roomCode;
//    private String cloudAnchorId;

    @Override
    public void onNewRoomCode(Long newRoomCode) {
      Preconditions.checkState(roomCode == null, "The room code cannot have been set before.");
      roomCode = newRoomCode;
      roomCodeText.setText(String.valueOf(roomCode));
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_room_code_available));
      checkAndMaybeShare();
      synchronized (singleTapLock) {
        // Change currentMode to HOSTING after receiving the room code (not when the 'Host' button
        // is tapped), to prevent an anchor being placed before we know the room code and able to
        // share the anchor ID.
        currentMode = HostResolveMode.HOSTING;
      }
    }

    @Override
    public void onError(DatabaseError error) {
      Log.w(TAG, "A Firebase database error happened.", error.toException());
      snackbarHelper.showError(
          CloudAnchorActivity.this, getString(R.string.snackbar_firebase_error));
    }

    @Override
    public void onCloudTaskComplete(Anchor anchor) {
      CloudAnchorState cloudState = anchor.getCloudAnchorState();
      if (cloudState.isError()) {
        Log.e(TAG, "Error hosting a cloud anchor, state " + cloudState);
        snackbarHelper.showMessageWithDismiss(
            CloudAnchorActivity.this, getString(R.string.snackbar_host_error, cloudState));
        return;
      }

      setNewCloudAnchor(anchor);
      checkAndMaybeShare();
    }

    // ?????? ?????? ??? ??????
    // ??????????????? ????????? ?????? ??? ????????? ?????????
    private void checkAndMaybeShare() {
      if (roomCode == null) {
        return;
      }

      // ????????? ?????? ??????
      firebaseManager.storeAnchorIdInRoom(roomCode, cloudAnchors, objectIndexQueue);
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_cloud_id_shared));
    }
  }


  private final class CloudAnchorResolveStateListener
      implements CloudAnchorManager.CloudAnchorResolveListener {
    private final long roomCode;

    CloudAnchorResolveStateListener(long roomCode) {
      this.roomCode = roomCode;
    }

    @Override
    public void onCloudTaskComplete(Anchor anchor, int objectId) {
      // When the anchor has been resolved, or had a final error state.
      CloudAnchorState cloudState = anchor.getCloudAnchorState();
      if (cloudState.isError()) {
        Log.w(
            TAG,
            "The anchor in room "
                + roomCode
                + " could not be resolved. The error state was "
                + cloudState);
        snackbarHelper.showMessageWithDismiss(
            CloudAnchorActivity.this, getString(R.string.snackbar_resolve_error, cloudState));
        return;
      }

      // ??????????????? resolved ??? ??????
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_resolve_success));

      setNewAnchor(anchor, objectId);
    }

    @Override
    public void onShowResolveMessage() {
      snackbarHelper.setMaxLines(4);
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_resolve_no_result_yet));
    }
  }


  public void showNoticeDialog(HostResolveListener listener) {
    DialogFragment dialog = PrivacyNoticeDialogFragment.createDialog(listener);
    dialog.show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
  }

  @Override
  public void onDialogPositiveClick(DialogFragment dialog) {
    if (!sharedPreferences.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
      throw new AssertionError("Could not save the user preference to SharedPreferences!");
    }
    createSession();
  }
}
