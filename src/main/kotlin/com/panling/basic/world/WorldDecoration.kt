package com.panling.basic.world

/**
 * 世界装饰物接口。
 * 所有需要在服务器启动时自动放置/修复的世界实体都应实现此接口。
 * 实现类只需放在 com.panling.basic 包树下，就会被 DecorationManager 自动发现并调用。
 */
interface WorldDecoration {
    /**
     * 确保装饰物存在。可被重复调用而不产生副作用（幂等）。
     */
    fun ensure()
}
