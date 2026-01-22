package com.panling.basic.util

import org.bukkit.plugin.java.JavaPlugin
import java.util.jar.JarFile

object ClassScanner {

    /**
     * 扫描插件 JAR 包中指定包名下的所有类
     * @param plugin 插件实例
     * @param packageName 包名前缀 (例如 "com.panling.basic")
     * @param parentClass 要查找的父类或接口 (例如 WorldScript::class.java)
     */
    fun <T> scanClasses(plugin: JavaPlugin, packageName: String, parentClass: Class<T>): Set<Class<out T>> {
        val classes = HashSet<Class<out T>>()
        val packageDir = packageName.replace('.', '/')

        // 获取插件的 jar 文件路径
        val srcFile = plugin.javaClass.protectionDomain.codeSource.location
        if (srcFile == null) return classes

        try {
            val jarPath = srcFile.path
            // 处理路径中的 URL 编码 (如空格变%20)
            val decodedPath = java.net.URLDecoder.decode(jarPath, "UTF-8")
            val jarFile = JarFile(decodedPath)

            val entries = jarFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                // 只处理 .class 文件，且在指定包路径下
                if (name.startsWith(packageDir) && name.endsWith(".class")) {
                    // 转换为类名 (com.panling.xxx)
                    val className = name.replace('/', '.').substring(0, name.length - 6)

                    try {
                        // [修复] 使用 plugin.javaClass.classLoader 替代 plugin.classLoader
                        // 因为 JavaPlugin.getClassLoader() 是 protected 的，
                        // 但我们可以通过获取插件主类的 Class 对象来获取其 ClassLoader (这是 public 的)
                        val clazz = Class.forName(className, false, plugin.javaClass.classLoader)

                        // 判定是否是我们需要的子类，且不是接口或抽象类
                        if (parentClass.isAssignableFrom(clazz)
                            && !clazz.isInterface
                            && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {

                            @Suppress("UNCHECKED_CAST")
                            classes.add(clazz as Class<out T>)
                        }
                    } catch (e: ClassNotFoundException) {
                        // 忽略无法加载的类
                    } catch (e: NoClassDefFoundError) {
                        // 忽略依赖缺失的类
                    }
                }
            }
            jarFile.close()
        } catch (e: Exception) {
            plugin.logger.warning("扫描类文件时出错: ${e.message}")
            e.printStackTrace()
        }

        return classes
    }
}