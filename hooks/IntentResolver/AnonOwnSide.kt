/*
 * SPDX-License-Identifier: Apache-2.0 OR MIT
 *
 * Copied into packages/modules/IntentResolver by scripts/apply.py.
 */
package com.android.intentresolver.domain.interactor

import android.os.UserHandle
import com.android.intentresolver.shared.model.User
import com.android.intentresolver.shared.model.User.Role

/**
 * The share sheet shows only the side it was opened on, as on Motorola phones: no Personal /
 * Private tabs. Opened on the main side, the hidden profile (AnonpheneOS's Secure Folder) isn't
 * there at all. Opened inside the folder, the folder is the only profile, so its own apps are
 * all that's offered. Moving something across goes through Secure Folder's own "Into Secure
 * Folder" and "Outside Secure Folder" targets. Work profiles are left as they are.
 */
internal fun anonOwnSide(users: List<User>, launchedAs: UserHandle): List<User> {
    val hidden = users.filter { it.role == Role.PRIVATE }
    if (hidden.isEmpty()) return users
    val inside = hidden.firstOrNull { it.id == launchedAs.identifier }
    // The share sheet needs exactly one PERSONAL profile; inside the folder, that's the folder.
    return if (inside != null) listOf(User(inside.id, Role.PERSONAL))
    else users.filter { it.role != Role.PRIVATE }
}

/** Looks a user up by id, since [anonOwnSide] may hand back a user with another role. */
internal fun <V> Map<User, V>.anonById(user: User): V? =
    entries.firstOrNull { it.key.id == user.id }?.value
