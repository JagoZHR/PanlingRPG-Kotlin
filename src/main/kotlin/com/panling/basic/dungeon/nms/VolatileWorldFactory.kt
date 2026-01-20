package com.panling.basic.dungeon.nms

import com.google.common.collect.ImmutableList
import com.mojang.serialization.Lifecycle
import com.panling.basic.dungeon.VoidGenerator
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.RandomSequences
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.level.DataPackConfig
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.storage.DerivedLevelData
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.PrimaryLevelData
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.craftbukkit.CraftServer
import java.nio.file.Files
import java.util.concurrent.Executor

object VolatileWorldFactory {

    fun create(name: String): World {
        // 1. 获取 Server
        val server = ((Bukkit.getServer() as Any) as CraftServer).server

        // 2. 准备 Level Key
        @Suppress("UNCHECKED_CAST")
        val dimensionKey = (Registries.DIMENSION as Any) as ResourceKey<Registry<Level>>

        val levelKey = (ResourceKey.create(
            dimensionKey,
            ResourceLocation.fromNamespaceAndPath("panling", name.lowercase())
        ) as Any) as ResourceKey<Level>

        // 3. 准备 LevelSettings
        val worldDataConfig = WorldDataConfiguration(DataPackConfig.DEFAULT, FeatureFlagSet.of())
        val settings = LevelSettings(
            name,
            GameType.ADVENTURE,
            false, // hardcore
            net.minecraft.world.Difficulty.NORMAL,
            true, // allowCommands
            GameRules(FeatureFlagSet.of()),
            worldDataConfig
        )

        // 4. 准备 LevelData
        val levelData = PrimaryLevelData(
            settings,
            WorldOptions(0L, false, false),
            PrimaryLevelData.SpecialWorldProperty.FLAT,
            Lifecycle.stable()
        )
        // 这里的 derivedData 虽然 ServerLevel 构造函数没直接用，
        // 但为了保持数据完整性逻辑（如果有需要），通常 NMS 会在内部处理。
        // 根据你提供的构造函数，它只需要 PrimaryLevelData (即 levelData)，所以我们不需要 DerivedLevelData。

        // 5. 准备 Session (临时目录)
        val session = try {
            val tempDir = Files.createTempDirectory("panling_volatile_")
            val source = LevelStorageSource.createDefault(tempDir)
            source.createAccess(name, LevelStem.OVERWORLD)
        } catch (e: Exception) {
            throw RuntimeException("无法创建临时 Session", e)
        }

        // 6. [移除] LevelLoadListener
        // 根据你提供的构造函数签名，ServerLevel 不再需要传入 Listener。
        // Paper/Purpur 内部会处理这个逻辑。

        // 7. 准备生成器
        val vanillaGen = server.overworld().chunkSource.generator
        // [新增] 你的 Bukkit 生成器
        val bukkitGen = VoidGenerator()

        // 8. 构造 LevelStem
        val levelStem = LevelStem(
            server.overworld().dimensionTypeRegistration(),
            vanillaGen
        )

        // 9. 构造 ServerLevel
        // [修复] 严格按照你提供的构造函数签名传参
        val internal = ServerLevel(
            server,                         // MinecraftServer
            server as Executor,             // Executor
            session,                        // LevelStorageAccess
            levelData,                      // PrimaryLevelData
            levelKey,                       // ResourceKey<Level>
            levelStem,                      // LevelStem
            false,                          // boolean isDebug
            BiomeManager.obfuscateSeed(0L),// long biomeZoomSeed
            ImmutableList.of(),             // List<CustomSpawner>
            true,                           // boolean tickTime
            RandomSequences(0L),            // RandomSequences
            org.bukkit.World.Environment.NORMAL, // World.Environment (Bukkit)
            bukkitGen,                      // ChunkGenerator (Bukkit) - Paper 会自动封装它！
            null                            // BiomeProvider (Bukkit) - 传 null 使用默认
        )

        // 10. [移除] 反射注入
        // 既然构造函数已经接收了 bukkitGen，Paper 内部会自动创建 CustomChunkGenerator 并赋值。
        // 所以这里不需要再手动反射注入了，直接注册即可。

        // 11. 注册并返回
        server.addLevel(internal)
        internal.noSave = true

        return internal.world
    }
}