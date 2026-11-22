package com.lttbdd.socialagent.core.ui.demo

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.ui.components.AppDialog
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.core.ui.components.NavigationItem
import com.lttbdd.socialagent.core.ui.components.StatusIndicator
import com.lttbdd.socialagent.core.ui.components.ai.AgentProgress
import com.lttbdd.socialagent.core.ui.components.ai.AgentStepStatus
import com.lttbdd.socialagent.core.ui.components.ai.ChannelStatus
import com.lttbdd.socialagent.databinding.ActivityUiShowcaseBinding
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.core.model.PostStatus

class UiShowcaseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUiShowcaseBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUiShowcaseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.demoToolbar.title = getString(R.string.ui_demo_toolbar_title)
        binding.sectionChannels.setTitle(R.string.ui_demo_section_title)
        binding.sectionDescription.text = getString(R.string.ui_demo_section_description)
        binding.cardText.text = getString(R.string.ui_demo_card_text)

        binding.bottomNavigation.setItems(
            listOf(
                NavigationItem(R.id.composeFragment, R.string.nav_compose, R.drawable.ic_nav_compose),
                NavigationItem(R.id.postsFragment, R.string.nav_posts, R.drawable.ic_nav_posts),
                NavigationItem(R.id.commentsFragment, R.string.nav_comments, R.drawable.ic_nav_comments),
            ),
        )

        binding.emptyState.setContent(
            getString(R.string.ui_demo_empty_title),
            getString(R.string.ui_demo_empty_message),
        )
        binding.errorState.setContent(
            getString(R.string.ui_demo_error_title),
            getString(R.string.ui_demo_error_message),
        )
        binding.errorState.setAction(getString(R.string.ui_demo_retry)) {
            AppSnackbar.show(binding.root, R.string.ui_demo_retry)
        }
        binding.feedbackButton.setOnClickListener {
            AppSnackbar.show(binding.root, R.string.ui_demo_feedback_action)
        }
        binding.dialogButton.setOnClickListener {
            AppDialog.show(
                context = this,
                titleRes = R.string.ui_demo_dialog_title,
                messageRes = R.string.ui_demo_dialog_message,
                positiveActionRes = R.string.action_done,
                negativeActionRes = R.string.action_cancel,
            )
        }

        PostStatus.entries.forEach { postStatus ->
            binding.statusContainer.addView(StatusIndicator(this).apply {
                setStatus(postStatus)
            })
        }
        ConnectionStatus.entries.forEach { connectionStatus ->
            binding.statusContainer.addView(StatusIndicator(this).apply {
                setStatus(connectionStatus)
            })
        }
        binding.statusContainer.addView(StatusIndicator(this).apply { setConnecting() })
        binding.statusContainer.addView(StatusIndicator(this).apply { setPendingApproval() })

        binding.agentProgress.setBriefStatus(AgentStepStatus.COMPLETED)
        binding.agentProgress.setStatuses(
            mapOf(
                AgentStep.OUTLINE to AgentStepStatus.COMPLETED,
                AgentStep.DRAFT to AgentStepStatus.COMPLETED,
                AgentStep.SELF_REVIEW to AgentStepStatus.IN_PROGRESS,
                AgentStep.IMAGE_PROMPT to AgentStepStatus.WAITING,
                AgentStep.IMAGE to AgentStepStatus.FAILED,
                AgentStep.PACKAGE to AgentStepStatus.WAITING,
            ),
        )
        binding.channelStatus.setChannel(
            channelName = getString(R.string.ui_demo_channel_name),
            accountName = getString(R.string.ui_demo_account_name),
            connectionState = ConnectionStatus.CONNECTED,
            iconRes = R.drawable.ic_nav_posts,
            actionLabel = getString(R.string.ui_demo_action_connect),
        ) {
            binding.channelStatus.setChannel(
                channelName = getString(R.string.ui_demo_channel_name),
                accountName = getString(R.string.ui_demo_account_name),
                connectionState = ConnectionStatus.TOKEN_EXPIRED,
                iconRes = R.drawable.ic_nav_posts,
            )
        }
        ConnectionStatus.entries.forEach { connectionState ->
            binding.channelStatusContainer.addView(
                ChannelStatus(this).apply {
                    setChannel(
                        channelName = getString(R.string.ui_demo_channel_name),
                        accountName = getString(R.string.ui_demo_account_name),
                        connectionState = connectionState,
                        iconRes = R.drawable.ic_nav_posts,
                    )
                },
            )
        }
        binding.channelStatusContainer.addView(
            ChannelStatus(this).apply {
                setChannel(
                    channelName = getString(R.string.ui_demo_channel_name),
                    accountName = getString(R.string.ui_demo_account_name),
                    connectionState = ConnectionStatus.DISCONNECTED,
                    iconRes = R.drawable.ic_nav_posts,
                    isConnecting = true,
                )
            },
        )

        binding.dropdown.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                listOf(
                    getString(R.string.ui_demo_dropdown_option_one),
                    getString(R.string.ui_demo_dropdown_option_two),
                ),
            ),
        )
        binding.dropdown.setText(getString(R.string.ui_demo_dropdown_option_one), false)
        binding.progressIndicator.progress = 65
        binding.progressIndicator.isIndeterminate = false
        binding.progressIndicator.contentDescription = getString(R.string.ui_demo_progress_label)
        binding.loadingIndicator.contentDescription = getString(R.string.ui_demo_progress_label)
        binding.iconButton.setOnClickListener {
            AppSnackbar.show(binding.root, R.string.ui_demo_search_hint, Snackbar.LENGTH_SHORT)
        }
    }
}
