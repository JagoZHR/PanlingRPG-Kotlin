package com.panling.basic.api

interface Reloadable {
    /**
     * 执行重载逻辑 (读取配置文件、清除缓存等)
     */
    fun reload()
}