package com.nighthawkapps.wallet.android

import android.app.Application
import android.os.Build
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.nighthawkapps.wallet.android.di.component.AppComponent
import com.nighthawkapps.wallet.android.di.component.DaggerAppComponent
import com.nighthawkapps.wallet.android.feedback.FeedbackCoordinator
import cash.z.ecc.android.sdk.ext.twig
import kotlinx.coroutines.*
import javax.inject.Inject

class NighthawkWalletApp : Application(), CameraXConfig.Provider {

    @Inject
    lateinit var coordinator: FeedbackCoordinator

    var creationTime: Long = 0
        private set

    var creationMeasured: Boolean = false

    /**
     * Intentionally private Scope for use with launching Feedback jobs. The feedback object has the
     * longest scope in the app because it needs to be around early in order to measure launch times
     * and stick around late in order to catch crashes. We intentionally don't expose this because
     * application objects can have odd lifecycles, given that there is no clear onDestroy moment in
     * many cases.
     */
    private var feedbackScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler(ExceptionReporter(Thread.getDefaultUncaughtExceptionHandler()))
        creationTime = System.currentTimeMillis()
        instance = this
        // Setup handler for uncaught exceptions.
        super.onCreate()

        component = DaggerAppComponent.factory().create(this)
        component.inject(this)
        feedbackScope.launch {
            coordinator.feedback.start()
        }
    }

    override fun getCameraXConfig(): CameraXConfig {
        return Camera2Config.defaultConfig()
    }

    companion object {
        lateinit var instance: NighthawkWalletApp
        lateinit var component: AppComponent
    }

    /**
     * @param feedbackCoordinator inject a provider so that if a crash happens before configuration
     * is complete, we can lazily initialize all the feedback objects at this moment so that we
     * don't have to add any time to startup.
     */
    inner class ExceptionReporter(private val ogHandler: Thread.UncaughtExceptionHandler) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread?, e: Throwable?) {
            twig("Uncaught Exception: $e caused by: ${e?.cause}")
            // these are the only reported crashes that are considered fatal
            coordinator.feedback.report(e, true)
            coordinator.flush()
            // can do this if necessary but first verify that we need it
            runBlocking {
                coordinator.await()
                coordinator.feedback.stop()
            }
            ogHandler.uncaughtException(t, e)
            Thread.sleep(2000L)
        }
    }
}


fun NighthawkWalletApp.isEmulator(): Boolean {
    val goldfish = Build.HARDWARE.contains("goldfish");
    val emu = (System.getProperty("ro.kernel.qemu", "")?.length ?: 0) > 0;
    val sdk = Build.MODEL.toLowerCase().contains("sdk")
    return goldfish || emu || sdk;
}
