package com.coral.gwigaon.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonDimens
import com.coral.gwigaon.ui.theme.GwigaonType

/** Pleos text field (medium: 100 dp, radius 24, border 3) with its error shown inline below. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = Gwigaon.colors
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = GwigaonDimens.fieldHeight),
            textStyle = GwigaonType.bodyMedium,
            placeholder = { Text(placeholder, style = GwigaonType.bodyMedium) },
            isError = error != null,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            shape = RoundedCornerShape(GwigaonDimens.radiusMedium),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.textPrimary,
                unfocusedTextColor = c.textPrimary,
                focusedContainerColor = c.fieldBackground,
                unfocusedContainerColor = c.fieldBackground,
                errorContainerColor = c.fieldBackground,
                focusedBorderColor = c.positive,
                unfocusedBorderColor = c.dividerTertiary,
                errorBorderColor = c.negative,
                focusedPlaceholderColor = c.textTertiary,
                unfocusedPlaceholderColor = c.textTertiary,
                cursorColor = c.positive,
            ),
        )
        ErrorText(error)
    }
}

@Composable
fun ErrorText(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(8.dp))
    Text(error, style = GwigaonType.labelMedium, color = Gwigaon.colors.negative)
}
