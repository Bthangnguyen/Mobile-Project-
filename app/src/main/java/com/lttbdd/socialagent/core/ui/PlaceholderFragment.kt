package com.lttbdd.socialagent.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.lttbdd.socialagent.databinding.FragmentPlaceholderBinding

/**
 * Màn hình tạm cho các tab chưa làm. Khi bắt đầu tính năng, chủ mảng tạo Fragment riêng
 * trong `feature/<tên>/` và thay `android:name` tương ứng trong `res/navigation/nav_graph.xml`.
 */
class PlaceholderFragment : Fragment() {

    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.setText(requireArguments().getInt(ARG_TITLE))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_TITLE = "titleRes"
    }
}
