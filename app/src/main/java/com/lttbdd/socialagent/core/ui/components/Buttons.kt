package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.lttbdd.socialagent.R

open class PrimaryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.socialAgentPrimaryButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr)

class SecondaryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.socialAgentSecondaryButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr)

class OutlinedButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.socialAgentOutlinedButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr)

class TextButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.socialAgentTextButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr)

class IconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.socialAgentIconButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr)
