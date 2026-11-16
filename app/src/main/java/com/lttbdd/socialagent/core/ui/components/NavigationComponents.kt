package com.lttbdd.socialagent.core.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Menu
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.appbar.MaterialToolbar

class AppToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.toolbarStyle,
) : MaterialToolbar(context, attrs, defStyleAttr)

class AppBottomNavigation @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle,
) : BottomNavigationView(context, attrs, defStyleAttr) {

    fun setItems(items: List<NavigationItem>) {
        menu.clear()
        items.forEachIndexed { order, item ->
            menu.add(Menu.NONE, item.id, order, item.titleRes)
                .icon = AppCompatResources.getDrawable(context, item.iconRes)
        }
    }
}

data class NavigationItem(
    @param:IdRes val id: Int,
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
)
