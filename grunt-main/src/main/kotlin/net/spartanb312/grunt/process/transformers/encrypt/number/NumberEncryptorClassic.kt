package net.spartanb312.grunt.process.transformers.encrypt.number

import net.spartanb312.genesis.kotlin.extensions.INT
import net.spartanb312.genesis.kotlin.extensions.LONG
import net.spartanb312.genesis.kotlin.extensions.insn.*
import net.spartanb312.genesis.kotlin.extensions.toInsnNode
import net.spartanb312.genesis.kotlin.instructions
import org.objectweb.asm.tree.InsnList
import kotlin.random.Random

object NumberEncryptorClassic : NumberEncryptor {

    fun <T : Number> encrypt(value: T): InsnList {
        return when (value) {
            is Int -> encrypt(value.toInt())
            is Long -> encrypt(value.toLong())
            is Float -> encrypt(value.toFloat())
            is Double -> encrypt(value.toDouble())
            else -> throw Exception("Not yet implemented")
        }
    }

    fun encrypt(value: Float): InsnList {
        val intBits = value.asInt()
        val key = Random.nextInt()
        val encryptedIntBits = intBits xor key
        return instructions {
            INT(encryptedIntBits)
            INT(key)
            IXOR
            INVOKESTATIC("java/lang/Float", "intBitsToFloat", "(I)F")
        }
    }

    fun encrypt(value: Double): InsnList {
        val longBits = value.asLong()
        val key = Random.nextLong()
        val encryptedLongBits = longBits xor key
        return instructions {
            LONG(encryptedLongBits)
            LONG(key)
            LXOR
            INVOKESTATIC("java/lang/Double", "longBitsToDouble", "(J)D")
        }
    }

    fun encrypt(value: Int): InsnList {
        val random = Random.nextInt(Int.MAX_VALUE)
        val negative = (if (Random.nextBoolean()) random else -random) + value
        val obfuscated = value xor negative
        return instructions {
            if (Random.nextBoolean()) {
                +negative.toInsnNode()
                I2L
                +obfuscated.toInsnNode()
                I2L
                LXOR
                L2I
            } else {
                LDC(negative.toLong())
                L2I
                +obfuscated.toInsnNode()
                IXOR
            }
        }
    }

    fun encrypt(value: Long): InsnList {
        val random = Random.nextLong()
        val obfuscated = value xor random
        return instructions {
            +obfuscated.toInsnNode()
            +random.toInsnNode()
            LXOR
        }
    }

}