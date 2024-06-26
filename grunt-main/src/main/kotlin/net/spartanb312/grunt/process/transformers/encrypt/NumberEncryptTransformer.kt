package net.spartanb312.grunt.process.transformers.encrypt

import net.spartanb312.grunt.config.value
import net.spartanb312.grunt.process.Transformer
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.utils.Counter
import net.spartanb312.grunt.utils.builder.*
import net.spartanb312.grunt.utils.count
import net.spartanb312.grunt.utils.extensions.isAbstract
import net.spartanb312.grunt.utils.extensions.isNative
import net.spartanb312.grunt.utils.logging.Logger
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import java.util.*
import java.util.concurrent.ThreadLocalRandom

/**
 * Encrypt numbers
 */
object NumberEncryptTransformer : Transformer("NumberEncrypt", Category.Encryption) {

    private val encryptFloatingPoint by value("FloatingPoint", true)
    private val times by value("Intensity", 1)
    private val exclusion by value("Exclusions", listOf())

    override fun ResourceCache.transform() {
        Logger.info(" - Encrypting numbers...")
        val floatCount = count {
            if (encryptFloatingPoint) {
                Logger.info("    Encrypting floating point numbers")
                nonExcluded.asSequence()
                    .filter { c -> exclusion.none { c.name.startsWith(it) } }
                    .forEach { classNode ->
                        classNode.methods.asSequence()
                            .filter { !it.isAbstract && !it.isNative }
                            .forEach { methodNode: MethodNode ->
                                encryptFloatingPoint(methodNode)
                            }
                    }
            }
        }.get()
        val count = count {
            add(floatCount)
            repeat(times) { t ->
                if (times > 1) Logger.info("    Encrypting integers ${t + 1} of $times times")
                nonExcluded.asSequence()
                    .filter { c -> exclusion.none { c.name.startsWith(it) } }
                    .forEach { classNode ->
                        classNode.methods.asSequence()
                            .filter { !it.isAbstract && !it.isNative }
                            .forEach { methodNode: MethodNode ->
                                methodNode.instructions
                                    .filter { it.opcode != Opcodes.NEWARRAY }
                                    .forEach {
                                        if (it.opcode in 0x2..0x8) {
                                            methodNode.instructions.insertBefore(it, xor(it.opcode - 0x3))
                                            methodNode.instructions.remove(it)
                                            if (t == 0) add()
                                        } else if (it is IntInsnNode) {
                                            methodNode.instructions.insertBefore(it, xor(it.operand))
                                            methodNode.instructions.remove(it)
                                            if (t == 0) add()
                                        } else if (it is LdcInsnNode && it.cst is Int) {
                                            val value = it.cst as Int
                                            if (value < -(Short.MAX_VALUE * 8) + Int.MAX_VALUE) {
                                                methodNode.instructions.insertBefore(it, xor(value))
                                                methodNode.instructions.remove(it)
                                                if (t == 0) add()
                                            }
                                        }
                                    }
                            }
                    }
            }
        }.get()
        Logger.info("    Encrypted $count numbers")
    }

    private fun Counter.encryptFloatingPoint(methodNode: MethodNode) {
        methodNode.instructions.toList().forEach {
            fun encryptFloat(cst: Float) {
                val intBits = cst.asInt()
                val key = kotlin.random.Random.nextInt()
                val encryptedIntBits = intBits xor key
                val insnList = insnList {
                    INT(encryptedIntBits)
                    INT(key)
                    IXOR
                    INVOKESTATIC("java/lang/Float", "intBitsToFloat", "(I)F")
                }
                methodNode.instructions.insertBefore(it, insnList)
                methodNode.instructions.remove(it)
                add()
            }

            fun encryptDouble(cst: Double) {
                val longBits = cst.asLong()
                val key = kotlin.random.Random.nextLong()
                val encryptedLongBits = longBits xor key
                val insnList = insnList {
                    LDC(encryptedLongBits)
                    LDC(key)
                    LXOR
                    INVOKESTATIC("java/lang/Double", "longBitsToDouble", "(J)D")
                }
                methodNode.instructions.insertBefore(it, insnList)
                methodNode.instructions.remove(it)
                add()
            }
            when {
                it is LdcInsnNode -> when (val cst = it.cst) {
                    is Float -> encryptFloat(cst)
                    is Double -> encryptDouble(cst)
                }

                it.opcode == Opcodes.FCONST_0 -> encryptFloat(0.0f)
                it.opcode == Opcodes.FCONST_1 -> encryptFloat(1.0f)
                it.opcode == Opcodes.FCONST_2 -> encryptFloat(2.0f)
                it.opcode == Opcodes.DCONST_0 -> encryptDouble(0.0)
                it.opcode == Opcodes.DCONST_1 -> encryptDouble(1.0)
            }
        }
    }

    private fun Double.asLong(): Long = java.lang.Double.doubleToRawLongBits(this)

    private fun Float.asInt(): Int = java.lang.Float.floatToRawIntBits(this)

    private fun xor(value: Int) = insnList {
        val first = Random(ThreadLocalRandom.current().nextInt().toLong()).nextInt(Short.MAX_VALUE.toInt()) + value
        val second = -Random(ThreadLocalRandom.current().nextInt().toLong()).nextInt(Short.MAX_VALUE.toInt()) + value
        val random = Random(ThreadLocalRandom.current().nextInt().toLong()).nextInt(Short.MAX_VALUE.toInt())
        INT(first xor value)
        INT(second xor value + random)
        IXOR
        INT(first xor value + random)
        IXOR
        INT(second)
        IXOR
    }

}