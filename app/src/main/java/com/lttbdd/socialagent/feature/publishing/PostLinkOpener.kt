package com.lttbdd.socialagent.feature.publishing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Mở bài gốc đã đăng (#40) bằng trình duyệt hoặc app mạng xã hội. Tách khỏi Fragment để test được Intent và
 * trường hợp máy không có app nào mở được link.
 */
object PostLinkOpener {

    /** Intent mở [url]; `CATEGORY_BROWSABLE` để chỉ app nhận link web an toàn mới được chọn. */
    fun intentFor(url: String): Intent =
        Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * Mở [url], trả false khi không có app nào nhận link (`ActivityNotFoundException`) để màn hình báo lỗi
     * thay vì crash. [url] phải đã qua [PublishStatusViewModel.openableUrl].
     */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(intentFor(url))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
