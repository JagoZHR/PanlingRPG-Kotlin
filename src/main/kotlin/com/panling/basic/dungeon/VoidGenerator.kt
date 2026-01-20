package com.panling.basic.dungeon

import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

class VoidGenerator : ChunkGenerator() {

    /**
     * 重写 generateNoise 方法。
     * 这里的逻辑是“什么都不做”，不填充任何方块。
     * 默认情况下，空的 ChunkData 就意味着全是空气（虚空）。
     */
    override fun generateNoise(
        worldInfo: WorldInfo,
        random: Random,
        x: Int,
        z: Int,
        chunkData: ChunkGenerator.ChunkData
    ) {
        // 保持为空 -> 生成虚空
        // 效率极高，因为跳过了所有的噪声计算
    }
}