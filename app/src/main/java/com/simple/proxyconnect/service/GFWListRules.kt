package com.simple.proxyconnect.service

/**
 * Built-in GFW domain rules for bypass-China-mainland routing.
 * Domains that should go through the proxy, and domains that should be direct.
 */
object GFWListRules {

    private val manualProxyDomains: Set<String> = setOf(
        "bgp.he.net",
    )

    val proxyDomains: Set<String> = GeneratedGfwDomains.domains + manualProxyDomains

    val directDomains: Set<String> = setOf(
        "baidu.com", "bdstatic.com", "bdimg.com", "baidubce.com",
        "qq.com", "gtimg.com", "qpic.cn", "qcloud.com",
        "weixin.qq.com", "wechat.com", "wx.qq.com",
        "taobao.com", "tmall.com", "alicdn.com", "aliyun.com", "alibaba.com", "alipay.com",
        "jd.com", "360buyimg.com", "jdcloud.com",
        "163.com", "126.com", "netease.com", "ydstatic.com",
        "sina.com.cn", "weibo.com", "sinaimg.cn",
        "sohu.com", "sogou.com", "sogo.com",
        "bilibili.com", "hdslb.com", "bilivideo.com", "b23.tv",
        "douyin.com", "tiktokv.com", "bytedance.com", "byteimg.com", "pstatp.com",
        "zhihu.com", "zhimg.com",
        "douban.com",
        "meituan.com", "dianping.com",
        "ctrip.com", "trip.com",
        "pinduoduo.com",
        "xiaomi.com", "mi.com", "miui.com",
        "huawei.com", "vmall.com",
        "csdn.net",
        "cnblogs.com",
        "jianshu.com",
        "toutiao.com",
        "iqiyi.com", "qiyi.com",
        "youku.com", "tudou.com",
        "kuaishou.com",
        "58.com", "ganji.com",
        "ele.me",
        "didi.com",
    )

    /**
     * Determine whether a domain should be proxied.
     * Returns true if the domain should go through proxy, false for direct.
     */
    fun shouldProxy(domain: String): Boolean {
        val lowered = domain.lowercase()

        // Chinese TLDs go direct
        if (lowered.endsWith(".cn") || lowered.endsWith(".com.cn")) {
            return false
        }

        if (matchesDomain(lowered, directDomains)) return false
        if (matchesDomain(lowered, proxyDomains)) return true

        // Default: proxy
        return true
    }

    /**
     * Strict DNS split decision for Bypass mode.
     * Only domains explicitly matched by proxyDomains will use remote DNS.
     */
    fun shouldRemoteResolveInBypass(domain: String): Boolean {
        val lowered = domain.lowercase()

        if (lowered.endsWith(".cn") || lowered.endsWith(".com.cn")) return false
        return matchesDomain(lowered, proxyDomains)
    }

    private fun matchesDomain(domain: String, domainSet: Set<String>): Boolean {
        if (domain in domainSet) return true
        val parts = domain.split(".")
        for (i in 1 until parts.size) {
            val suffix = parts.subList(i, parts.size).joinToString(".")
            if (suffix in domainSet) return true
        }
        return false
    }
}
