package com.panling.basic.loot

fun interface LootCondition {
    fun test(context: LootContext?): Boolean
}