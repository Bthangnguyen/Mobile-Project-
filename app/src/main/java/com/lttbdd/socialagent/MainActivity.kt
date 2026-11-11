package com.lttbdd.socialagent

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.lttbdd.socialagent.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity duy nhất của app: chứa NavHostFragment và bottom navigation 5 tab.
 *
 * Không nhận deep link OAuth: đăng nhập Facebook/LinkedIn đều chặn redirect ngay trong hộp thoại
 * ([FacebookAuthRedirect], [LinkedInAuthRedirect]) và kiểm tra `state`, nên Activity exported này
 * không được phép tự kết nối kênh từ một intent bên ngoài.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)
    }
}
