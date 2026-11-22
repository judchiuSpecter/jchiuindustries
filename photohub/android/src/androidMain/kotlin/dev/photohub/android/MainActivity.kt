package dev.photohub.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.photohub.shared.data.AccountsController
import dev.photohub.shared.data.TimelineController
import dev.photohub.shared.ui.App
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val controller: TimelineController by inject()
    private val accounts: AccountsController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // MediaStore returns nothing without the read-media permission, so the
            // first scan is deferred until the user has answered the prompt.
            var granted by remember { mutableStateOf(hasMediaPermission()) }

            val request = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { granted = hasMediaPermission() }

            LaunchedEffect(Unit) {
                if (!granted) request.launch(requiredPermissions())
            }

            App(controller = controller, accounts = accounts, refreshOnStart = granted)
        }
    }

    private fun hasMediaPermission(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Android 13 split media access into per-type permissions; older versions
     * use the single storage-read permission.
     */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}
