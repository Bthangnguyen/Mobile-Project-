package com.lttbdd.socialagent.feature.channel

/**
 * Logic thuần chọn cookie phiên Facebook cần xóa để hộp thoại đăng nhập hiện form đăng nhập
 * thay vì màn hình "Tiếp tục dưới tên ...".
 *
 * Giữ lại cookie nhận diện thiết bị (`datr`, `sb`) để Facebook không coi là thiết bị lạ và dễ
 * chặn "thao tác quá nhanh"; mọi cookie còn lại (phiên đăng nhập, `c_user`, `xs`, `fr`...) bị xóa.
 */
object FacebookSessionCookies {

    private val KEEP = setOf("datr", "sb")

    /** Tên các cookie cần xóa, lấy từ chuỗi `Cookie` (dạng `a=1; b=2`). */
    fun namesToExpire(cookieHeader: String?): List<String> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() && it !in KEEP }
            .distinct()
    }

    /** Chuỗi `Set-Cookie` làm cookie hết hạn ngay, dùng với `CookieManager.setCookie`. */
    fun expireCookie(name: String, domain: String): String =
        "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$domain; Path=/"
}
