package space.doky.arcore

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.*


class MainActivity : AppCompatActivity() {

    lateinit var btnStart: Button
    lateinit var btnAr: Button

    var userRequestedInstall = true

    var session: Session? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart  = findViewById<Button>(R.id.btnStart)
        btnAr  = findViewById<Button>(R.id.btnAr)

        enableArButton()
    }


    // AR 활성화 버튼
    private fun enableArButton(){

        val availability = ArCoreApk.getInstance().checkAvailability(this)

        // 지속적으로 확인
        if(availability.isTransient){
            Handler(Looper.getMainLooper()).postDelayed({ // https://velog.io/@kimbsu00/Android-4
                enableArButton()
            }, 200)
        }

        if(availability.isSupported){
            btnAr.visibility = View.VISIBLE
            btnAr.isEnabled = true
        } else {
            btnAr.visibility = View.INVISIBLE
            btnAr.isEnabled = false
        }
    }


    // onResume 시, 카메라 권한 및 AR 상태 확인
    override fun onResume() {
        super.onResume()

        // 카메라 권한 확인
        if (!CameraPermissionHelper.hasCameraPermission(this)){
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        try {
            if(session == null){
                when(ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        // session = Session(this)
                        createSession()
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        // 이 상태가 반환되는 경우
                        // 1. ARCore가 해당 액티비티를 pause 하는 경우
                        // 2. ARcore가 사용자에게 설치를 안내창을 띄운 경우 (market://details?id=com.google.ar.core)
                        // 3. ARCore가 최신 디바이스 프로필을 다운로드하는 경우
                        // 4. ARCore가 해당 액티비티를 resume하는 경우, 다음 requestInstall() 호출에
                        //    따라 INSTALLED 혹은 실패 시 예외 처리

                        userRequestedInstall = false
                        return
                    }
                }
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            // 사용자가 설치 거절 시
            Toast.makeText(this, "Error: " + e, Toast.LENGTH_LONG).show()
            return
        }
    }


    // 권한 확인 이후 작업
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 카메라 권한 받지 못한 경우
        if (!CameraPermissionHelper.hasCameraPermission(this)){
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()

            if(!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)){
                // "앱 사용 중에만 허용" 말고
                // "이번만 허용", "허용 안함" 선택한 경우,
                // 설정으로 보내버림
                CameraPermissionHelper.launchPermissionSettings(this)
            }
        }
    }


    // # Android에서 ARCore 세션 구성
    // 모든 AR 프로세스는 ARCore 세션 내에서 발생합니다.
    // Session는 ARCore API의 기본 진입점입니다. AR 시스템 상태를 관리하고 세션 수명 주기를 처리하므로 앱이 세션을 생성, 구성, 시작 또는 중지할 수 있습니다.
    // 무엇보다도 앱이 카메라 이미지 및 기기 포즈에 액세스할 수 있는 프레임을 수신할 수 있습니다.
    //
    // 세션에서 사용 가능한 기능들
    // - 조명 추정, 앵커, 증강 이미지, 증강 얼굴, 심도 API, 빠른 게재위치, ARCore Geospatial API

    val TAG: String = "ARCore"

    fun isARCoreSupportedAndUpToDate(): Boolean {
        return when (ArCoreApk.getInstance().checkAvailability(this)){
            // 정상적으로 지원하는 경우
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> true

            // APK 버전 및 설치가 되지 않은 경우
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                try {
                    // 설치 재요청
                    when (ArCoreApk.getInstance().requestInstall(this, true)) {
                        ArCoreApk.InstallStatus.INSTALLED -> true
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            Log.i(TAG, "ARCore installation requested.")
                            false
                        }
                    }

                } catch (e: UnavailableException){
                    Log.e(TAG, "ARCore not installed", e)
                    false
                }
            }

            // 사용자 기기가 AR 지원하지 않 경우
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> false

            // ARCore is checking the availability with a remote query.
            // This function should be called again after waiting 200 ms to determine the query result.
            ArCoreApk.Availability.UNKNOWN_CHECKING -> false

            // There was an error checking for AR availability. This may be due to the device being offline.
            // Handle the error appropriately.
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT, ArCoreApk.Availability.UNKNOWN_ERROR -> false

        }
    }


    fun createSession() {

        session = Session(this)

        // AR 설정 관련 작업을 여기에 작성
        // depth, turning (Augumented Faces) 지원 등

        val config = Config(session)
        config.focusMode = Config.FocusMode.AUTO // 자동초점

        val filter = CameraConfigFilter(session)
        filter.targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30) // 30 FPS (60 FPS 지원이 안됨...)
        filter.depthSensorUsage = EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE) // 심도센서 없이 카메라만 사용




        session?.let {
            val cameraConfigList = it.getSupportedCameraConfigs(filter)
            Log.e(TAG, cameraConfigList.toString())
            it.cameraConfig = cameraConfigList[0] // 최선의 세팅값 가져옴
        }

        // 설정 적용
        session?.configure(config)

        // session.close()
    }



}