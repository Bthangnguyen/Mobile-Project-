package com.lttbdd.socialagent.feature.usage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.core.ui.components.AppDialog
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.data.usage.UsageDashboard
import com.lttbdd.socialagent.data.usage.UsageTotals
import com.lttbdd.socialagent.databinding.FragmentUsageBinding
import com.lttbdd.socialagent.databinding.ItemUsageRowBinding
import com.lttbdd.socialagent.databinding.ItemUsageSummaryBinding
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

/** Màn "Token & chi phí" (F8, #74), mở từ Cài đặt. Chi phí là ước tính theo bảng giá cục bộ. */
@AndroidEntryPoint
class UsageFragment : Fragment() {
    private var _binding: FragmentUsageBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UsageViewModel by viewModels()

    /** `true` khi đang vẽ lại từ state, để không gửi ngược lựa chọn về ViewModel. */
    private var rendering = false
    private lateinit var formatter: UsageFormatter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        formatter = UsageFormatter(resources.configuration.locales[0])
        listOf(binding.usageTitle, binding.historyTitle, binding.breakdownTitle).forEach {
            ViewCompat.setAccessibilityHeading(it, true)
        }
        binding.todayCard.summaryTitle.setText(R.string.usage_today)
        binding.monthCard.summaryTitle.setText(R.string.usage_this_month)
        binding.historyToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !rendering) {
                viewModel.onModeSelected(
                    if (checkedId == R.id.by_month_button) UsageHistoryMode.MONTH else UsageHistoryMode.DAY,
                )
            }
        }
        binding.clearButton.setOnClickListener {
            AppDialog.show(
                context = requireContext(),
                titleRes = R.string.usage_clear_confirm_title,
                messageRes = R.string.usage_clear_confirm_message,
                positiveActionRes = R.string.usage_clear_confirm,
                negativeActionRes = R.string.action_cancel,
                onPositiveAction = viewModel::clear,
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
    }

    private fun render(state: UsageUiState) {
        val dashboard = state.dashboard
        binding.progress.visibility = visibleIf(state.loading || state.clearing)
        binding.loadError.visibility = visibleIf(state.loadFailed)
        binding.emptyText.visibility = visibleIf(dashboard?.isEmpty == true)
        binding.content.visibility = visibleIf(dashboard != null)
        binding.usageDescription.text = getString(R.string.usage_description, pricingDate(state.pricingVersion))
        binding.clearButton.isEnabled = !state.clearing && dashboard?.isEmpty == false
        if (dashboard != null) renderDashboard(dashboard, state.mode)
        state.message?.let { message ->
            AppSnackbar.show(binding.root, messageRes(message))
            viewModel.onMessageShown()
        }
    }

    @StringRes
    private fun messageRes(message: UsageMessage): Int = when (message) {
        UsageMessage.CLEARED -> R.string.usage_cleared
        UsageMessage.CLEAR_FAILED -> R.string.usage_clear_failed
    }

    private fun renderDashboard(dashboard: UsageDashboard, mode: UsageHistoryMode) {
        renderSummary(binding.todayCard, dashboard.today)
        renderSummary(binding.monthCard, dashboard.thisMonth)

        rendering = true
        try {
            binding.historyToggle.check(
                if (mode == UsageHistoryMode.MONTH) R.id.by_month_button else R.id.by_day_button,
            )
        } finally {
            rendering = false
        }
        val history = when (mode) {
            UsageHistoryMode.DAY -> dashboard.days.map { formatter.day(it.date) to rowDetail(it.totals) }
            UsageHistoryMode.MONTH -> dashboard.months.map { formatter.month(it.month) to rowDetail(it.totals) }
        }
        fillRows(binding.historyList, history)
        binding.historyEmpty.visibility = visibleIf(history.isEmpty())

        val breakdown = dashboard.breakdown.map { item ->
            getString(R.string.usage_breakdown_row_title, providerName(item.provider), item.model) to
                getString(R.string.usage_breakdown_row_detail, getString(taskName(item.task)), rowDetail(item.totals))
        }
        fillRows(binding.breakdownList, breakdown)
        binding.breakdownEmpty.visibility = visibleIf(breakdown.isEmpty())
    }

    private fun renderSummary(card: ItemUsageSummaryBinding, totals: UsageTotals) {
        card.summaryTokens.text = getString(
            R.string.usage_tokens_detail,
            formatter.count(totals.totalTokens),
            formatter.count(totals.inputTokens),
            formatter.count(totals.outputTokens),
        )
        card.summaryCalls.text = callsText(totals)
        card.summaryCost.text = getString(R.string.usage_cost_label, costText(totals))
    }

    /** Danh sách ngắn (tối đa 31 ngày hoặc 12 tháng) nên dựng thẳng vào LinearLayout, không cần RecyclerView. */
    private fun fillRows(container: LinearLayout, rows: List<Pair<String, String>>) {
        container.removeAllViews()
        rows.forEach { (title, detail) ->
            val row = ItemUsageRowBinding.inflate(layoutInflater, container, true)
            row.rowTitle.text = title
            row.rowDetail.text = detail
        }
    }

    private fun rowDetail(totals: UsageTotals): String = getString(
        R.string.usage_row_detail,
        getString(R.string.usage_tokens_short, formatter.count(totals.totalTokens)),
        callsText(totals),
        costText(totals),
    )

    private fun callsText(totals: UsageTotals): String =
        resources.getQuantityString(R.plurals.usage_calls, totals.calls, formatter.count(totals.calls.toLong()))

    /** Lượt chưa rõ giá không bao giờ được tính là 0 USD: nói rõ tổng chưa gồm những lượt đó. */
    private fun costText(totals: UsageTotals): String = when {
        totals.isCostUnknown -> getString(R.string.usage_cost_unknown)
        totals.unknownCostCalls > 0 -> resources.getQuantityString(
            R.plurals.usage_cost_partial,
            totals.unknownCostCalls,
            formatter.usd(totals.knownCostMicros),
            formatter.count(totals.unknownCostCalls.toLong()),
        )
        else -> formatter.usd(totals.knownCostMicros)
    }

    private fun pricingDate(version: String): String = try {
        formatter.day(LocalDate.parse(version))
    } catch (_: DateTimeParseException) {
        version
    }

    private fun providerName(provider: LlmProvider): String = getString(
        when (provider) {
            LlmProvider.OPENAI -> R.string.api_keys_openai
            LlmProvider.GEMINI -> R.string.api_keys_gemini
            LlmProvider.ANTHROPIC -> R.string.usage_provider_anthropic
            LlmProvider.OPENROUTER -> R.string.usage_provider_openrouter
        },
    )

    @StringRes
    private fun taskName(task: LlmTask): Int = when (task) {
        LlmTask.WRITE_POST -> R.string.models_task_write_post
        LlmTask.CLASSIFY_COMMENT -> R.string.models_task_classify
        LlmTask.GENERATE_IMAGE -> R.string.models_task_image
    }

    private fun visibleIf(visible: Boolean) = if (visible) View.VISIBLE else View.GONE

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
