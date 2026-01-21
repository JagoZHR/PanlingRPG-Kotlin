package com.panling.basic.shop

class Shop(
    val id: String,
    val title: String,
    val size: Int // 9, 18, 27, ... 54
) {
    // Slot -> Product 映射
    // 为了保持与 Java 逻辑一致（外部通过 getProducts 获取后可能修改），这里直接公开 MutableMap
    val products: MutableMap<Int, ShopProduct> = HashMap()

    fun addProduct(product: ShopProduct) {
        products[product.slot] = product
    }

    fun getProduct(slot: Int): ShopProduct? {
        return products[slot]
    }

    // Kotlin 专属优化：允许使用 shop[slot] 语法访问 (Operator Overloading)
    operator fun get(slot: Int): ShopProduct? = products[slot]
}