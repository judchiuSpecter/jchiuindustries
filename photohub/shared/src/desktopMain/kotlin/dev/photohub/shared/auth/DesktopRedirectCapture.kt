package dev.photohub.shared.auth

/**
 * Desktop [RedirectCapture] backed by the loopback HTTP listener.
 *
 * A fresh [LoopbackReceiver] is created per attempt so a cancelled sign-in
 * cannot leave a stale socket bound.
 */
class DesktopRedirectCapture : RedirectCapture {

    private var receiver: LoopbackReceiver? = null

    override fun begin(): String =
        LoopbackReceiver().also { receiver = it }.start()

    override suspend fun await(): Result<RedirectResult> {
        val active = receiver
            ?: return Result.failure(IllegalStateException("begin() was not called"))

        return active.awaitRedirect().map {
            RedirectResult(code = it.code, state = it.state, error = it.error)
        }
    }

    override fun cancel() {
        receiver?.close()
        receiver = null
    }
}
