package app.aemu.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.AgentSupport
import app.aemu.core.GuestImage
import app.aemu.core.VmSettings

/**
 * The guest agent's master switch — the only part of the agent this page owns. The hide list the agent
 * serves is managed inside the guest, in the Root Manager app (the agent's own app there, talking to
 * the agent service): the list is the guest's own file, and this page deliberately has no editor for
 * it, not even a read-only copy — a second surface here would be a second truth.
 */
@Composable
internal fun AgentSection(img: GuestImage, s: VmSettings, set: (VmSettings) -> Unit) {
    if (!AgentSupport.supported(img.engine, img.api, false)) return
    Toggle(stringResource(R.string.vs_agent), stringResource(R.string.vs_agent_sub), s.agent) { set(s.copy(agent = it)) }
}
