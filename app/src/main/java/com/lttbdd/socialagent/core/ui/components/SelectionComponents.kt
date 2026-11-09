package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class StyledCheckbox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.checkboxStyle,
) : MaterialCheckBox(context, attrs, defStyleAttr)

class StyledRadioButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.radioButtonStyle,
) : MaterialRadioButton(context, attrs, defStyleAttr)

class StyledSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle,
) : SwitchMaterial(context, attrs, defStyleAttr)

class AppDropdown @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    init {
        inputType = android.text.InputType.TYPE_NULL
        threshold = 0
    }
}
