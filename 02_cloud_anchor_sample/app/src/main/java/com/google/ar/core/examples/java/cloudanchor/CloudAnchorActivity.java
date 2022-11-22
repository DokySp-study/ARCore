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
  private static final float[] OBJECT_COLOR = new float[] {139.0f, 195.0f, 74.0f, 255.0f};

  private enum HostResolveMode {
    NONE,
    HOSTING,
    RESOLVING,
  }

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
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

  /** 카메라 사용자 권한 유무 저장 */
  private static final String ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES";



  @GuardedBy("singleTapLock")
  private MotionEvent queuedSingleTap;

  private Session session;

  @GuardedBy("anchorLock")
  private ArrayList<Anchor> anchors = new ArrayList<Anchor>();



  // Cloud Anchor Components.
  private FirebaseManager firebaseManager;
  private final CloudAnchorManager cloudManager = new CloudAnchorManager();
  private HostResolveMode currentMode;
  private RoomCodeAndCloudAnchorIdListener hostListener;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    // Open GL로 이미지 표시하는 뷰
    surfaceView = findViewById(R.id.surfaceview);

    // 화면 회전 관리
    displayRotationHelper = new DisplayRotationHelper(this);


    // 사용자 입력 옵저버 탑재
    gestureDetector =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                  // 호스트 모드일 때 큐를 MotionEvent 로 할당
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

    // GL 랜더러 설정
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);
    installRequested = false;

    // UI Framework 설정
    hostButton = findViewById(R.id.host_button);
    hostButton.setOnClickListener((view) -> onHostButtonPress());
    resolveButton = findViewById(R.id.resolve_button);
    resolveButton.setOnClickListener((view) -> onResolveButtonPress());
    roomCodeText = findViewById(R.id.room_code_text);

    // Cloud Anchor 설정
    firebaseManager = new FirebaseManager(this);

    // HOST, RESOLVE 모드 설정
    currentMode = HostResolveMode.NONE;
    sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, Context.MODE_PRIVATE);
  }






  // [X]
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

  // [X]
  @Override
  protected void onResume() {
    super.onResume();

    if (sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
      createSession();
    }
    snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message));
    surfaceView.onResume();

    // 화면 회전 관리
    displayRotationHelper.onResume();
  }

  // [X] 세션 생성 (분석 불필요)
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


        // ARCore 사용자 권한 관리
        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // 세션 생성
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

  // [X]
  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.

      // 화면 회전 관리
      displayRotationHelper.onPause();

      surfaceView.onPause();
      session.pause();
    }
  }

  // [X]
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

  // [X]
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
    // 프레임 흐를 때 계속 구동됨

    synchronized (singleTapLock) { // singleTapLock mutex lock

      synchronized (anchorLock) { // anchor mutex lock

        // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
        // camera is currently tracking.

        // 설정된 엥커가 없고, single tap queue가 비어있지 않을 때, camera가 tracking 상태일 때



//        Log.e("=========", anchor + ", " + queuedSingleTap + ", " + cameraTrackingState);

        // TODO: 앵커 여러 개 배치
//        if (anchor == null  &&  queuedSingleTap != null  &&  cameraTrackingState == TrackingState.TRACKING) {
        if (queuedSingleTap != null  &&  cameraTrackingState == TrackingState.TRACKING) {

          //////////////////////////////////////////////////////////////////////////////////////////
          //////////////////////////////////////////////////////////////////////////////////////////
          // HOST일 경우, 앵커 생성
          // 조건이 맞지 않을경우 IllegalStateException

          // TODO: resolver도 배치 가능하도록 함
//          Preconditions.checkState(
//              currentMode == HostResolveMode.HOSTING,
//              "We should only be creating an anchor in hosting mode.");



          // for (var a in array){} 문법이랑 동일
          for (HitResult hit : frame.hitTest(queuedSingleTap)) {

            // Ray casting 된 포인트에 엥커 설치가 가능한 경우
            if (shouldCreateAnchorWithHit(hit)) {

              // hit: HitResult 안에 좌표 등 다양한 정보 있음
              Anchor newAnchor = hit.createAnchor();

              // hostListener
              Preconditions.checkNotNull(hostListener, "The host listener cannot be null.");

              // 새로운 엥커를 host로 발송
              cloudManager.hostCloudAnchor(newAnchor, hostListener);



              // anchor에 새로운 엥커값을 대입
              setNewAnchor(newAnchor);

              if(snackbarHelper.isShowing()){
                snackbarHelper.hide(this);
              }
              snackbarHelper.showMessage(this, getString(R.string.snackbar_anchor_placed));
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


  // 세션 시작 후 평면 잡았을 때 호출됨
  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);

      // TODO: 모델을 여러 개 바꾸는 기능
      // 모델 세팅 코드
      // virtualObject -> 배열로 관리하고 별도 idx queue를 만들어서 관리
      virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png");
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

      virtualObjectShadow.createOnGlThread(
          this, "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);



    } catch (IOException ex) {
      Log.e(TAG, "Failed to read an asset file", ex);
    }
  }


  // 세션 시작 후 평면 변경되었을 때 호출됨
  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    // 화면 회전 관리
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }


  // 프레임을 그릴 때 (프레임이 넘어갈 때마다) 호출됨
  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    // 리셋
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    // 세션이 없을 때 동작 안함
    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.

    // 화면 회전 관리
    displayRotationHelper.updateSessionIfNeeded(session);

    // 화면을 그리는 부분
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


      // TODO: 앵커 여러 개 배치
      // anchor를 anchorMatrix로 변환하는 과정이 있음
      // anchor, anchorMatrix 둘 다 배열로 관리해야 함
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
        float scaleFactor = 1.0f;


        for (float[] anchorMatrix : anchorMatrixList) {

          virtualObject.updateModelMatrix(anchorMatrix, scaleFactor);
          virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
          virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR);
          virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR);

        }


      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }


  /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null. */
  private void setNewAnchor(Anchor newAnchor) {

    Log.e("====================", "setNewAnchor");
//    if (anchors != null)
//      Log.e("====================", anchor.toString());

    synchronized (anchorLock) {

      // TODO: 앵커 여러 개 배치
      // -> 기존 앵커를 detach() 하지 않고 지속적으로 유지
      // -> 추후 anchor를 배열에 넣어서 관리하면 삭제도 가능할 것 같다!
//      if (anchor != null) {
//        anchor.detach();
//      }

      // TODO: 앵커 여러 개 배치
//      anchor = newAnchor;
      anchors.add(newAnchor);
    }
  }

  private void resetAnchors(){
    anchors.clear();
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











  // [X]
  // HOST 버튼 눌렀을 때
  private void onHostButtonPress() {

    // HOST 모드이면 리셋
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

  // [X]
  // RESOLVE 버튼 눌렀을 시
  private void onResolveButtonPress() {

    // RESOLVE 모드이면 리셋
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

  // [X]
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

  // [X]
  private void onPrivacyAcceptedForResolve() {
    ResolveDialogFragment dialogFragment = new ResolveDialogFragment();
    dialogFragment.setOkListener(this::onRoomCodeEntered);
    dialogFragment.show(getSupportFragmentManager(), "ResolveDialog");
  }




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
        cloudAnchorId -> {
          // When the cloud anchor ID is available from Firebase.
          CloudAnchorResolveStateListener resolveListener =
              new CloudAnchorResolveStateListener(roomCode);
          Preconditions.checkNotNull(resolveListener, "The resolve listener cannot be null.");
          cloudManager.resolveCloudAnchor(
              cloudAnchorId, resolveListener, SystemClock.uptimeMillis());
        });
  }

  /**
   * Listens for both a new room code and an anchor ID, and shares the anchor ID in Firebase with
   * the room code when both are available.
   */
  private final class RoomCodeAndCloudAnchorIdListener
      implements CloudAnchorManager.CloudAnchorHostListener, FirebaseManager.RoomCodeListener {

    private Long roomCode;
    private String cloudAnchorId;

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


    // [X]
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


      // TODO: 앵커 여러 개 배치
//      Preconditions.checkState(
//          cloudAnchorId == null, "The cloud anchor ID cannot have been set before.");


      cloudAnchorId = anchor.getCloudAnchorId();
      setNewAnchor(anchor);
      checkAndMaybeShare();
    }

    // TODO: 앵커 여러 개 배치
    // 클라우드에 배포한 이후 이 코드가 실행됨
    private void checkAndMaybeShare() {
      if (roomCode == null || cloudAnchorId == null) {
        return;
      }
      firebaseManager.storeAnchorIdInRoom(roomCode, cloudAnchorId);
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_cloud_id_shared));
    }
  }


  // [X]
  private final class CloudAnchorResolveStateListener
      implements CloudAnchorManager.CloudAnchorResolveListener {
    private final long roomCode;

    CloudAnchorResolveStateListener(long roomCode) {
      this.roomCode = roomCode;
    }

    @Override
    public void onCloudTaskComplete(Anchor anchor) {
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

      // 성공적으로 resolved 된 경우
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_resolve_success));
      setNewAnchor(anchor);
    }

    @Override
    public void onShowResolveMessage() {
      snackbarHelper.setMaxLines(4);
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_resolve_no_result_yet));
    }
  }


  // [X]
  public void showNoticeDialog(HostResolveListener listener) {
    DialogFragment dialog = PrivacyNoticeDialogFragment.createDialog(listener);
    dialog.show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
  }

  // [X]
  @Override
  public void onDialogPositiveClick(DialogFragment dialog) {
    if (!sharedPreferences.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
      throw new AssertionError("Could not save the user preference to SharedPreferences!");
    }
    createSession();
  }



}
