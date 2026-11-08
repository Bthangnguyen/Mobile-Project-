# Shared UI Foundation

The shared Android Views live in `com.lttbdd.socialagent.core.ui.components`.
They use XML, ViewBinding and the existing Material 3 dependency. The showcase is
available only in the debug source set and does not participate in app navigation.

## Theme resources

- `res/values/colors.xml` defines the light semantic palette; `values-night/colors.xml`
  supplies its dark-mode equivalents.
- `res/values/styles.xml` contains text appearances and component styles.
- `res/values/dimens.xml` is the source for common spacing, sizing and corner radii.
- `Theme.SocialAgent` maps these resources to Material 3 attributes. The existing
  DayNight parent selects the matching palette automatically.

Use semantic colors and shared dimensions rather than adding screen-specific copies.
The typography styles are `TextAppearance.SocialAgent.Display`, `.Headline`, `.Title`,
`.Body`, `.Label` and `.Caption`.

## Components

### Buttons

`PrimaryButton`, `SecondaryButton`, `OutlinedButton`, `TextButton` and `IconButton`
extend Material `MaterialButton`. Select the matching style:

```xml
<com.lttbdd.socialagent.core.ui.components.PrimaryButton
    style="@style/Widget.SocialAgent.Button.Primary"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/save" />
```

The remaining styles are `Widget.SocialAgent.Button.Secondary`, `.Outlined`,
`.Text` and `.Icon`. Button text and content descriptions remain string resources.

### Inputs

`AppTextField`, `AppPasswordField` and `AppSearchField` are `TextInputLayout`
containers. Add a `TextInputEditText` child and set its hint on the container.
Password fields enable the Material password visibility toggle; search fields show
the shared search icon.

```xml
<com.lttbdd.socialagent.core.ui.components.AppTextField
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="@string/topic_hint">
    <com.google.android.material.textfield.TextInputEditText
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="textCapSentences" />
</com.lttbdd.socialagent.core.ui.components.AppTextField>
```

### Cards and sections

`AppCard` is a styled `MaterialCardView`. `AppSection` is a vertical `LinearLayout`
that can optionally show a heading with `setTitle(@StringRes)` or `setTitle(CharSequence)`.
`AppDivider` is a one-dp content separator.

### Navigation

`AppToolbar` wraps `MaterialToolbar`. `AppBottomNavigation` wraps
`BottomNavigationView` and can use a menu in XML or be populated with
`setItems(List<NavigationItem>)`. `NavigationItem` takes a menu item ID, a string
resource for its label, and a drawable resource for its icon. Existing app navigation
can continue using the XML menu and Navigation Component bindings.

### Feedback

- `AppLoading` is a circular Material progress indicator.
- `AppProgressIndicator` is a linear Material progress indicator.
- `AppEmptyState` and `AppErrorState` provide title/message content and an optional
  action using `setContent(title, message)` and `setAction(label, callback)`.
- `AppSnackbar.show(view, messageRes, duration)` displays a localized message.
- `AppDialog.show(context, titleRes, messageRes, positiveActionRes, negativeActionRes,
  onPositiveAction)` displays a localized Material confirmation dialog.

### Status and selection

`StatusIndicator.setStatus(PostStatus)` displays post lifecycle values including
`QUEUED`; `setStatus(ConnectionStatus)` displays the shared connection model.
`setConnecting()` and `setPendingApproval()` support transient/UI workflow states
that are not represented in those core models. Labels are localized and semantic
colors are shared.
`StyledCheckbox`, `StyledRadioButton`, `StyledSwitch` and `AppDropdown` wrap the
corresponding Material controls.

### AI components

`AgentProgress` lists the six writing steps in product order. Update one step or all
steps using `core.model.AgentStep` without introducing agent/backend logic into the view:

```kotlin
binding.agentProgress.setStepStatus(
    AgentStep.DRAFT,
    AgentStepStatus.IN_PROGRESS,
)
```

`AgentStepStatus` is `WAITING`, `IN_PROGRESS`, `COMPLETED` or `FAILED`.
The shared brief row can be updated with `setBriefStatus(status)`.
`setStatuses(Map<AgentStep, AgentStepStatus>)` resets omitted core steps to `WAITING`.

`ChannelStatus.setChannel(...)` accepts the platform and account names, a
`core.model.ConnectionStatus`, an optional drawable resource, an optional action
label/callback, and an optional `isConnecting` flag for an active connection attempt.
It only renders information supplied by its caller; it does not connect accounts,
refresh tokens or perform OAuth.

```kotlin
binding.channelStatus.setChannel(
    channelName = getString(R.string.channel_linkedin),
    accountName = account.displayName,
    connectionState = ConnectionStatus.CONNECTED,
    actionLabel = getString(R.string.disconnect),
    onAction = ::confirmDisconnect,
)
```

## Showcase

`UiShowcaseActivity` and `activity_ui_showcase.xml` are under `app/src/debug/`.
They demonstrate button variants, all input wrappers, cards, navigation, feedback,
all statuses and selection controls, plus AgentProgress and ChannelStatus states.
The activity is non-exported and is not the launcher. To open it from a debug build,
use an explicit launch from the app's package:

```shell
adb shell am start -n com.lttbdd.socialagent/.core.ui.demo.UiShowcaseActivity
```

All user-visible text is stored in both `values/strings.xml` (English) and
`values-vi/strings.xml` (Vietnamese). Components do not own network, AI, OAuth,
navigation policy or persistence behavior; feature screens provide those concerns.
