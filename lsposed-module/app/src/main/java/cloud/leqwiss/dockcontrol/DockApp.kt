package cloud.leqwiss.dockcontrol

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class DockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    ServiceHolder.bind(service)
                }

                override fun onServiceDied(service: XposedService) {
                    ServiceHolder.unbind()
                }
            })
        }
    }
}
