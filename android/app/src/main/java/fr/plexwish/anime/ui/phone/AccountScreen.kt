package fr.plexwish.anime.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.plexwish.anime.feature.account.AccountViewModel
import fr.plexwish.anime.feature.account.PasswordForm
import fr.plexwish.anime.ui.components.Choice
import fr.plexwish.anime.ui.components.GhostButton
import fr.plexwish.anime.ui.components.LinkButton
import fr.plexwish.anime.ui.components.Panel
import fr.plexwish.anime.ui.components.PrimaryButton
import fr.plexwish.anime.ui.components.ScreenTitle
import fr.plexwish.anime.ui.components.Segmented
import fr.plexwish.anime.ui.components.focusRing
import fr.plexwish.anime.ui.components.initials
import fr.plexwish.anime.ui.theme.AppIcons
import fr.plexwish.anime.ui.theme.AppShapes
import fr.plexwish.anime.ui.theme.AppTheme
import fr.plexwish.anime.ui.theme.Dimens
import fr.plexwish.anime.ui.theme.ThemeMode

/** Onglet Compte : qui est connecté, thème, mot de passe (S4), À propos, déconnexion. */
@Composable
fun AccountScreen(
    vm: AccountViewModel, username: String?, server: String?, theme: ThemeMode, onTheme: (ThemeMode) -> Unit,
    onAbout: () -> Unit, onLogout: () -> Unit, padding: PaddingValues,
) {
    val form by vm.form.collectAsStateWithLifecycle()
    AccountContent(username, server, theme, onTheme, form, vm::onCurrent, vm::onNew, vm::onConfirm, vm::toggleVisible, vm::submit,
        onAbout, onLogout, padding)
}

@Composable
fun AccountContent(
    username: String?, server: String?, theme: ThemeMode, onTheme: (ThemeMode) -> Unit,
    form: PasswordForm, onCurrent: (String) -> Unit, onNew: (String) -> Unit, onConfirm: (String) -> Unit,
    onToggleVisible: () -> Unit, onSubmit: () -> Unit, onAbout: () -> Unit, onLogout: () -> Unit, padding: PaddingValues,
) {
    val p = AppTheme.palette
    Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.gutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s4)) {
                Box(Modifier.size(56.dp).background(p.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(username ?: "?").take(1), style = MaterialTheme.typography.headlineSmall, color = p.accent)
                }
                Column(Modifier.weight(1f)) {
                    ScreenTitle(username ?: "Compte")
                    server?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }

            Panel(title = "Thème") {
                Text("Enregistré sur ce téléphone. « Système » suit le réglage d'Android.", style = MaterialTheme.typography.bodyMedium, color = p.text2)
                Segmented(
                    listOf(
                        Choice(ThemeMode.SYSTEM, "Système"),
                        Choice(ThemeMode.DARK, "Sombre"),
                        Choice(ThemeMode.LIGHT, "Clair"),
                    ),
                    theme, onTheme,
                )
            }

            Panel(title = "Mot de passe") {
                Text("Les autres appareils connectés à votre compte seront déconnectés ; celui-ci reste connecté.",
                    style = MaterialTheme.typography.bodyMedium, color = p.text2)
                PasswordField("Mot de passe actuel", form.current, onCurrent, form.visible, ContentType.Password, ImeAction.Next, enabled = !form.saving)
                PasswordField("Nouveau mot de passe", form.new, onNew, form.visible, ContentType.NewPassword, ImeAction.Next,
                    enabled = !form.saving, supporting = "${AccountViewModel.MIN_LENGTH} caractères au moins.")
                PasswordField("Confirmer le nouveau mot de passe", form.confirm, onConfirm, form.visible, ContentType.NewPassword,
                    ImeAction.Done, enabled = !form.saving, onDone = onSubmit)
                Row(
                    Modifier.focusRing(AppShapes.small).heightIn(min = Dimens.target)
                        .toggleable(value = form.visible, role = Role.Checkbox, onValueChange = { onToggleVisible() }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = form.visible, onCheckedChange = null)
                    Text("Afficher les mots de passe", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Dimens.s2))
                }
                form.error?.let {
                    Text(it, color = p.err, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
                form.done?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s2), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        Icon(AppIcons.CheckCircleFill, contentDescription = null, tint = p.ok)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                PrimaryButton(if (form.saving) "Enregistrement…" else "Changer le mot de passe", onSubmit,
                    enabled = form.canSubmit, icon = AppIcons.Key)
            }

            Panel {
                LinkButton("À propos", onAbout, icon = AppIcons.Info)
                GhostButton("Se déconnecter", onLogout, icon = AppIcons.Logout)
            }
        }
    }
}

@Composable
private fun PasswordField(
    label: String, value: String, onChange: (String) -> Unit, visible: Boolean, type: ContentType, ime: ImeAction,
    enabled: Boolean, supporting: String? = null, onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = AppShapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { contentType = type },
    )
}
