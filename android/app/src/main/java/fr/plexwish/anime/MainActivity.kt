package fr.plexwish.anime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.plexwish.anime.ui.phone.PhoneApp
import fr.plexwish.anime.ui.theme.AnimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AnimeApp).container
        setContent { AnimeTheme { PhoneApp(container) } }
    }
}
