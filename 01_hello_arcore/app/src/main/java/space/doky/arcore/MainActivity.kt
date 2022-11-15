package space.doky.arcore

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException



class MainActivity : AppCompatActivity() {

    lateinit var btnStart: Button
    lateinit var btnAr: Button

    var userRequestedInstall = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart  = findViewById<Button>(R.id.btnStart)
        btnAr  = findViewById<Button>(R.id.btnAr)

        enableArButton()
    }


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


    override fun onResume() {
        super.onResume()

        var session: Session? = null

        // 카메라 권한 확인
        if (!CameraPermissionHelper.hasCameraPermission(this)){
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }

        try {
            if(session == null){
                when(ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(this)
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

}