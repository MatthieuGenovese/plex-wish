package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.BuildConfig
import fr.plexwish.anime.feature.login.LoginState
import fr.plexwish.anime.feature.login.LoginViewModel
import fr.plexwish.anime.ui.components.AppIconButton
import fr.plexwish.anime.ui.components.PrimaryButton
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens

/** Actions de l'écran de connexion. */
data class LoginActions(
    val onServer: (String) -> Unit = {},
    val onLogin: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val toggleVisible: () -> Unit = {},
    val submit: () -> Unit = {},
)

/** Connexion : identifiant, mot de passe (affichable), aide ; adresse du serveur seulement si aucune n'est fixée à la compilation. */
@Composable
fun LoginScreen(vm: LoginViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    LoginContent(s, LoginActions(vm::onServer, vm::onLogin, vm::onPassword, vm::toggleVisible, vm::submit))
}

@Composable
fun LoginContent(s: LoginState, a: LoginActions) {
    val p = AppTheme.palette
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.gutter)
                .border(1.dp, p.outline, AppShapes.large).background(p.surface1, AppShapes.large).padding(Dimens.s5),
            verticalArrangement = Arrangement.spacedBy(Dimens.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).background(p.accent, AppShapes.small), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.PlayArrowFill, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(24.dp))
                }
                Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.titleMedium)
            }
            Text("Connexion", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (s.expired) {
                Text("Votre session a expiré : reconnectez-vous.", style = MaterialTheme.typography.bodyMedium, color = p.text2)
            }
            if (!s.fixedServer) {
                OutlinedTextField(
                    value = s.server, onValueChange = a.onServer, singleLine = true, enabled = !s.loading,
                    label = { Text("Adresse du serveur") },
                    placeholder = { Text("https://anime.mondomaine.fr") },
                    supportingText = { Text("Celle que vous a donnée l'administrateur.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    shape = AppShapes.medium, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = s.login, onValueChange = a.onLogin, singleLine = true, enabled = !s.loading,
                label = { Text("Identifiant ou e-mail") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                shape = AppShapes.medium, modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
            OutlinedTextField(
                value = s.password, onValueChange = a.onPassword, singleLine = true, enabled = !s.loading,
                label = { Text("Mot de passe") },
                visualTransformation = if (s.visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    AppIconButton(if (s.visible) AppIcons.VisibilityOff else AppIcons.Visibility,
                        if (s.visible) "Masquer le mot de passe" else "Afficher le mot de passe", a.toggleVisible)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { a.submit() }),
                shape = AppShapes.medium, modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            s.error?.let {
                Text(it, color = p.err, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            PrimaryButton(if (s.loading) "Connexion…" else "Se connecter", a.submit, Modifier.fillMaxWidth(), enabled = s.canSubmit)
            Text("Mot de passe oublié ? Demandez à l'administrateur de le réinitialiser.", style = MaterialTheme.typography.bodySmall,
                color = p.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
