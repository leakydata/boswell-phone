package net.boswell.phone.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** The one icon the app needs beyond material-icons-core, drawn here rather than pulling in 40 MB of the extended set. */
val Icons.Filled.PauseBars: ImageVector by lazy {
    materialIcon(name = "Filled.PauseBars") {
        materialPath {
            moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); verticalLineToRelative(14f); close()
            moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineToRelative(-4f); close()
        }
    }
}
