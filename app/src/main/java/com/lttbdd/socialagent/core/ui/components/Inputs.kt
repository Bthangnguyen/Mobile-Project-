package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.textfield.TextInputLayout
import com.lttbdd.socialagent.R

open class AppTextField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle,
) : TextInputLayout(context, attrs, defStyleAttr)

class AppPasswordField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle,
) : AppTextField(context, attrs, defStyleAttr) {

    override fun onFinishInflate() {
        super.onFinishInflate()
        editText?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        endIconMode = END_ICON_PASSWORD_TOGGLE
    }
}

class AppSearchField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.textInputStyle,
) : AppTextField(context, attrs, defStyleAttr) {

    init {
        startIconDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_search_24)
    }
}
