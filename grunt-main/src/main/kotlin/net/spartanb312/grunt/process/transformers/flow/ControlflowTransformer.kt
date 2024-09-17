package net.spartanb312.grunt.process.transformers.flow

import net.spartanb312.grunt.config.setting
import net.spartanb312.grunt.process.MethodProcessor
import net.spartanb312.grunt.process.Transformer
import net.spartanb312.grunt.process.hierarchy.Hierarchy
import net.spartanb312.grunt.process.hierarchy.ReferenceSearch
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.process.transformers.flow.process.JunkCode
import net.spartanb312.grunt.process.transformers.flow.process.ReplaceGoto
import net.spartanb312.grunt.process.transformers.flow.process.ReplaceIf
import net.spartanb312.grunt.utils.builder.insnList
import net.spartanb312.grunt.utils.count
import net.spartanb312.grunt.utils.logging.Logger
import net.spartanb312.grunt.utils.notInList
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.random.Random

/**
 * Obfuscating the controlflow
 * Last update on 24/09/16
 */
object ControlflowTransformer : Transformer("Controlflow", Category.Controlflow), MethodProcessor {

    private var beforeEncrypt by setting("ExecuteBeforeEncrypt", false)
    private val replaceGoto by setting("ReplaceGoto", true)
    private val replaceIf by setting("ReplaceIf", true)
    private val intensity by setting("Intensity", 1)
    private val reverse by setting("RandomReversedCondition", true)
    val useLocalVar by setting("UseLocalVar", true)
    val junkCode by setting("JunkCode", true)
    val expandedJunkCode by setting("ExpandedJunkCode", true)
    val exclusion by setting("Exclusion", listOf())

    override var order: Int
        get() = if (beforeEncrypt) 20 else 40
        set(value) {
            beforeEncrypt = value == 20
        }

    override fun ResourceCache.transform() {
        Logger.info(" - Replacing jumps to implicit operations")
        JunkCode.refresh(this)
        val hierarchy = Hierarchy(this)
        hierarchy.build(true)
        val count = count {
            nonExcluded.asSequence()
                .filter { it.name.notInList(exclusion) && !it.missingReference(hierarchy) }
                .forEach { classNode ->
                    classNode.methods.forEach { methodNode ->
                        add(processMethodNode(methodNode))
                    }
                }
        }
        Logger.info("    Replaced ${count.get()} jumps")
    }

    override fun transformMethod(owner: ClassNode, method: MethodNode) {
        processMethodNode(method)
    }

    fun processMethodNode(methodNode: MethodNode): Int {
        var count = 0
        repeat(intensity) {
            if (replaceGoto) {
                val newInsn = insnList {
                    val returnType = Type.getReturnType(methodNode.desc)
                    methodNode.instructions.forEach { insnNode ->
                        if (insnNode is JumpInsnNode && insnNode.opcode == Opcodes.GOTO) {
                            +ReplaceGoto.generate(
                                insnNode.label,
                                methodNode,
                                returnType,
                                reverse && Random.nextBoolean()
                            )
                            count++
                        } else +insnNode
                    }
                }
                methodNode.instructions = newInsn
            }
            if (replaceIf) {
                val newInsn = insnList {
                    val returnType = Type.getReturnType(methodNode.desc)
                    methodNode.instructions.forEach { insnNode ->
                        if (insnNode is JumpInsnNode && (insnNode.opcode == Opcodes.IF_ICMPEQ
                                    || insnNode.opcode == Opcodes.IF_ICMPLT
                                    || insnNode.opcode == Opcodes.IF_ICMPGE
                                    || insnNode.opcode == Opcodes.IF_ICMPGT
                                    || insnNode.opcode == Opcodes.IF_ICMPLE
                                    || insnNode.opcode == Opcodes.IF_ICMPNE)
                        ) {
                            +ReplaceIf.generate(
                                insnNode,
                                insnNode.label,
                                methodNode,
                                returnType,
                                reverse && Random.nextBoolean()
                            )
                            count++
                        } else +insnNode
                    }
                }
                methodNode.instructions = newInsn
            }
        }
        if ((replaceIf || replaceGoto) && useLocalVar) methodNode.maxLocals += 1
        return count
    }

    private fun ClassNode.missingReference(hierarchy: Hierarchy): Boolean {
        return ReferenceSearch.checkMissing(this, hierarchy).isNotEmpty()
    }

}