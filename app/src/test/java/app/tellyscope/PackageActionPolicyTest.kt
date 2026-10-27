// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.PackageActionPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PackageActionPolicyTest {
    private fun app(system: Boolean, removable: Boolean = !system, protectedReason: String? = null,
                    verifiedRestorePath: Boolean = false, listKeep: Boolean = false) =
        AppInfo("org.example.demo", "Пример", null, system, true, protectedReason, "1",
            removable = removable, verifiedRestorePath = verifiedRestorePath, listKeep = listKeep)

    @Test fun protectedSystemCannotBeDisabledOrStopped() {
        assertNotNull(PackageActionPolicy.refusal(app(true, protectedReason = "критический"), Action.Disable))
        assertNotNull(PackageActionPolicy.refusal(app(true, protectedReason = "критический"), Action.Stop))
        assertNotNull(PackageActionPolicy.refusal(app(true, protectedReason = "критический"), Action.Uninstall))
    }

    @Test fun unknownSystemCannotBeDisabledUntilRestorePathIsVerified() {
        val system = app(true)
        assertNotNull(PackageActionPolicy.refusal(system, Action.Disable))
        assertNotNull(PackageActionPolicy.refusal(system, Action.Uninstall))
    }

    @Test fun exactFirmwareRecoveryEvidenceAllowsOnlyDisabling() {
        val system = app(true, verifiedRestorePath = true)
        assertNull(PackageActionPolicy.refusal(system, Action.Disable))
        assertNotNull(PackageActionPolicy.refusal(system, Action.Uninstall))
    }

    @Test fun keepListCannotOverrideSecurity() {
        val system = app(true, verifiedRestorePath = true, listKeep = true)
        assertNotNull(PackageActionPolicy.refusal(system, Action.Disable))
    }

    @Test fun removableUserAppMayBeUninstalled() {
        assertNull(PackageActionPolicy.refusal(app(false), Action.Uninstall))
    }

    @Test fun notRemovableUserAppCannotBeUninstalled() {
        assertNotNull(PackageActionPolicy.refusal(app(false, removable = false), Action.Uninstall))
    }

    @Test fun disappearingPackageCannotBeChanged() {
        assertNotNull(PackageActionPolicy.refusal(null, Action.Disable))
        assertNotNull(PackageActionPolicy.refusal(null, Action.Uninstall))
    }

    @Test fun recoveryEnableIsNotBlockedByDisableSafetyRule() {
        assertNull(PackageActionPolicy.refusal(app(true, protectedReason = "критический"), Action.Enable))
    }
}
