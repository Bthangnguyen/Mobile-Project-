package com.lttbdd.socialagent.feature.apikeys

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.databinding.FragmentApiKeysEntryBinding

/** Điểm vào tối thiểu từ tab Settings đang là placeholder; không triển khai cài đặt F9. */
class ApiKeysEntryFragment : Fragment() {
    private var _binding: FragmentApiKeysEntryBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentApiKeysEntryBinding.inflate(inflater, container, false).also { _binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding!!.manageApiKeys.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_api_keys)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
