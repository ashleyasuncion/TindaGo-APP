package com.example.tindago.ui.util

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Shared haptic helper — Stage 4 (web v2.63/v2.64 parity polish).
 * Uses View.performHapticFeedback so it can be called from
 * clickable {} / onClick {} lambdas (non-composable scopes).
 * Call with `val view = LocalView.current` captured at composable scope.
 */
fun performHapticFeedback(view: View, type: Int = HapticFeedbackConstants.VIRTUAL_KEY) {
    view.performHapticFeedback(type)
}
