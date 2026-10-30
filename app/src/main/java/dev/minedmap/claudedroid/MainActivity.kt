package dev.minedmap.claudedroid
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
class MainActivity : ComponentActivity() {
  override fun onCreate(s: Bundle?) { super.onCreate(s); setContent { Text("Claude Droid") } }
}
