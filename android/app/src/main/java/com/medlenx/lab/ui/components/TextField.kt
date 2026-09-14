package com.medlenx.lab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/**
 * Text field with the web's focus treatment: 1px #E2E8F0 border, and on focus
 * 1px #2563EB plus a 2dp #2563EB/20 outer ring.
 *
 * [highlighted] reproduces the amber tint the web applies to low-confidence reads.
 */
@Composable
fun MlxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    highlighted: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    trailing: (@Composable () -> Unit)? = null,
) {
    val border = if (highlighted) Mlx.Warn200 else Mlx.Brand200
    val background = if (highlighted) Mlx.Warn50 else Mlx.Surface

    Column(modifier = modifier) {
        if (label != null) {
            Text(text = label.uppercase(), style = MlxType.FieldLabel, color = Mlx.Text500)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            textStyle = MlxType.BodySmall.copy(color = Mlx.Text900),
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .background(background, MlxShape.Medium)
                        .border(BorderStroke(1.dp, border), MlxShape.Medium)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MlxType.BodySmall,
                                color = Mlx.Text400,
                            )
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
