package com.lifelink.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifelink.app.R

/** Displays [title] as an accessibility heading with an optional [subtitle] beneath it. */
@Composable
fun LifeLinkPageHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Centered loading indicator for a screen or list that is still fetching its first
 * batch of data. A non-null [label] is announced to accessibility services and
 * shown beneath the spinner; a null label keeps the indicator indeterminate.
 */
@Composable
fun LifeLinkLoadingIndicator(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { label?.let { contentDescription = it } },
        )
        label?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The LifeLink brand lockup: the app icon placed literally beside the
 * "LifeLink" title. Used wherever the title appears (home header, profile
 * header, welcome copy) so the icon and wordmark travel together.
 */
@Composable
fun LifeLinkBrand(modifier: Modifier = Modifier, subtitle: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(
            painter = painterResource(R.drawable.lifelink_icon),
            contentDescription = null,
            modifier = Modifier.size(34.dp).clip(MaterialTheme.shapes.small),
        )
        LifeLinkPageHeader("LifeLink", subtitle = subtitle)
    }
}
