package dev.androidcad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import dev.androidcad.model.ModelSession
import dev.androidcad.project.ModelRepository
import dev.androidcad.project.ProjectRepository
import java.io.File

class MainActivity : ComponentActivity() {
    // Der Zustand lebt in der Activity (nicht in der Komposition) und wird beim Start aus dem
    // zuletzt geöffneten Projekt wiederhergestellt – auch nach Prozess-Tod.
    private val state by lazy {
        CadState(ProjectRepository(File(filesDir, "projects")), lifecycleScope, getString(R.string.script_default_name), DEFAULT_SOURCE, getString(R.string.script_new_project_name))
    }

    // Skizzen und Modell werden im Projektspeicher (filesDir/models) gesichert und beim Start wiederhergestellt.
    private val workspace by lazy {
        WorkspaceState(lifecycleScope, ModelSession(ModelRepository(File(filesDir, "models")), getString(R.string.model_default_name)), ::getString, UiPrefs(File(filesDir, "ui.properties")))
    }

    // Eine Projektverwaltung für Modelle und Skripte; merkt sich, welche Art zuletzt bearbeitet wurde.
    private val hub by lazy { ProjectHub(workspace, state, File(filesDir, "active-kind")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Startbildschirm (Icon auf Indigo) – einheitlich ab Android 8 über die SplashScreen-Bibliothek, vor super.onCreate
        installSplashScreen()
        super.onCreate(savedInstanceState)
        AndroidTextOutlines.init()
        setContent {
            val mode = workspace.prefs.themeMode
            val dark = isDarkTheme(mode)
            // Systemleisten passend zum Thema: helle Symbole auf dunklem Grund und umgekehrt
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
                onDispose { }
            }
            AxisWrightTheme(mode) {
                AppRoot(hub)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        hub.flush() // ausstehende Änderungen sofort sichern, bevor Android den Prozess beenden darf
    }
}
