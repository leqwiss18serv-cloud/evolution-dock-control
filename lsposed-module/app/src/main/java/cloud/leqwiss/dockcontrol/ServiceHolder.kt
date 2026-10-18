package cloud.leqwiss.dockcontrol

import io.github.libxposed.service.XposedService
import java.util.concurrent.CopyOnWriteArraySet

object ServiceHolder {
    @Volatile
    var service: XposedService? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        service?.let { listener() }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun bind(service: XposedService) {
        this.service = service
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    fun unbind() {
        service = null
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }
}
