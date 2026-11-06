package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.lttbdd.socialagent.R

class AppCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr)

class AppSection @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val heading = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_SocialAgent_Title)
        visibility = View.GONE
    }

    init {
        orientation = VERTICAL
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTitle(@StringRes titleRes: Int) {
        heading.setText(titleRes)
        heading.visibility = View.VISIBLE
    }

    fun setTitle(title: CharSequence) {
        heading.text = title
        heading.visibility = View.VISIBLE
    }
}

class AppDivider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        setBackgroundColor(context.getColor(R.color.brand_surface_variant))
        minimumHeight = context.resources.getDimensionPixelSize(R.dimen.divider_thickness)
        contentDescription = context.getString(R.string.ui_divider_description)
    }
}
