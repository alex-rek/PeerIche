package com.arcticfoxapps.peeriche

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp

@Composable internal fun LinkedWatchDescription(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val body = buildAnnotatedString {
        var offset = 0
        for (match in Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text)) {
            append(text.substring(offset, match.range.first))
            val url = match.value.trimEnd('.', ',', ')', ']')
            withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = linkColor)))) { append(url) }
            append(match.value.substring(url.length))
            offset = match.range.last + 1
        }
        append(text.substring(offset))
    }
    SelectionContainer { Text(body, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
