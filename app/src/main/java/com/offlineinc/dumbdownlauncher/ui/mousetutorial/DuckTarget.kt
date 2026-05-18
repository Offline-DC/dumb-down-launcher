package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.R
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/** Clickable duck target.
 *
 *  Uses [R.drawable.duck_icon_clear] — a transparent-background PNG that
 *  drops onto the black page without a visible bounding rectangle.  The
 *  hit / hover area is slightly larger than the visible duck (100 dp
 *  vs 96 dp) so the user only has to get *close* to the duck with the
 *  FlipMouse cursor to highlight it — landing pixel-perfect on a small
 *  target with a d-pad-driven cursor is hard, so we give them a generous
 *  margin.  When the cursor enters that hit area and the duck is
 *  [enabled], a faint yellow circular halo paints behind the duck.
 */
@Composable
internal fun DuckTarget(
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .size(100.dp)
            .background(
                color = if (enabled && isHovered) {
                    DumbTheme.Colors.Yellow.copy(alpha = 0.3f)
                } else Color.Transparent,
                shape = CircleShape
            )
            .hoverable(interactionSource = interactionSource, enabled = enabled)
            .then(
                // When enabled, advertise this as a clickable target so a physical
                // mouse / Magic Mouse swaps to the hand-pointer cursor on hover
                // (matches standard "this is a button" affordance).
                if (enabled) Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.duck_icon_clear),
            contentDescription = "duck",
            modifier = Modifier.size(96.dp)
        )
    }
}
