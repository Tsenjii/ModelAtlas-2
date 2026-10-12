package dev.modelatlas.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.modelatlas.app.ui.AtlasScreen
import dev.modelatlas.app.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {

  private val atlasViewModel: AtlasViewModel by viewModels()
  private val onboardingViewModel: OnboardingViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE,
    )
    setContent {
      AtlasTheme {
        AtlasScreen(viewModel = atlasViewModel, onboardingViewModel = onboardingViewModel)
      }
    }
  }
}

private val LightPrimary = Color(0xFF00796B)
private val LightSecondary = Color(0xFF00897B)
private val DarkPrimary = Color(0xFF80CBC4)
private val DarkSecondary = Color(0xFF4DB6AC)

@Composable
private fun AtlasTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val scheme = if (dark) {
    darkColorScheme(primary = DarkPrimary, secondary = DarkSecondary)
  } else {
    lightColorScheme(primary = LightPrimary, secondary = LightSecondary)
  }
  MaterialTheme(colorScheme = scheme, content = content)
}
