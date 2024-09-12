package net.spartanb312.grunt.process.transformers.redirect

import net.spartanb312.grunt.config.setting
import net.spartanb312.grunt.process.Transformer
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.process.transformers.misc.NativeCandidateTransformer
import net.spartanb312.grunt.utils.*
import net.spartanb312.grunt.utils.builder.*
import net.spartanb312.grunt.utils.extensions.isInitializer
import net.spartanb312.grunt.utils.extensions.isPublic
import net.spartanb312.grunt.utils.logging.Logger
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Scramble field calls
 * Last update on 2024/07/08
 */
object FieldScrambleTransformer : Transformer("FieldScramble", Category.Redirect) {

    private val intensity by setting("Intensity", 1)
    private val randomName by setting("RandomName", false)
    private val redirectGetStatic by setting("RedirectGetStatic", true)
    private val redirectSetStatic by setting("RedirectSetStatic", true)
    private val redirectGetField by setting("RedirectGetValue", true)
    private val redirectSetField by setting("RedirectSetField", true)

    private val generateOuterClass by setting("GenerateOuterClass", false)
    private val excludedClasses by setting("ExcludedClasses", listOf())
    private val excludedFieldName by setting("ExcludedFieldName", listOf())

    private val downCalls by setting("NativeDownCalls", true)
    private val upCalls by setting("NativeUpCalls", false)

    val blackList = mutableListOf<ClassNode>()

    override fun ResourceCache.transform() {
        Logger.info(" - Redirecting field calls...")
        val newClasses = mutableMapOf<ClassNode, ClassNode>() // Owner Companion
        var count = 0
        repeat(intensity) {
            count += process(newClasses)
        }
        blackList.clear()
        Logger.info("    Redirected $count field calls")
    }

    private fun ResourceCache.process(newClasses: MutableMap<ClassNode, ClassNode>): Int {
        val count = count {
            nonExcluded.asSequence()
                .filter { it.name.notInList(excludedClasses) && !blackList.contains(it) }
                .forEach { classNode ->
                    classNode.methods.toList().asSequence()
                        .filter { !it.isInitializer }
                        .forEach { methodNode ->
                            methodNode.instructions.toList().forEach {
                                if (it is FieldInsnNode && it.name.notInList(excludedFieldName)) {
                                    val callingOwner = getClassNode(it.owner)
                                    val callingField = callingOwner?.fields?.find { field ->
                                        field.name == it.name && field.desc == it.desc
                                    }
                                    if (callingField != null) {
                                        val shouldOuter =
                                            generateOuterClass && callingOwner.isPublic && callingField.isPublic
                                        val genMethod = when {
                                            it.opcode == Opcodes.GETSTATIC && redirectGetStatic ->
                                                genMethod(
                                                    it,
                                                    if (randomName) getRandomString(10)
                                                    else "get_${it.name}${getRandomString(5)}",
                                                    callingField.signature
                                                ).appendAnnotation(false)

                                            it.opcode == Opcodes.PUTSTATIC && redirectSetStatic ->
                                                genMethod(
                                                    it,
                                                    if (randomName) getRandomString(10)
                                                    else "set_${it.name}${getRandomString(5)}",
                                                    callingField.signature
                                                ).appendAnnotation(true)

                                            it.opcode == Opcodes.GETFIELD && redirectGetField ->
                                                genMethod(
                                                    it,
                                                    if (randomName) getRandomString(10)
                                                    else "get_${it.name}${getRandomString(5)}",
                                                    callingField.signature
                                                ).appendAnnotation(false)

                                            it.opcode == Opcodes.PUTFIELD && redirectSetField ->
                                                genMethod(
                                                    it,
                                                    if (randomName) getRandomString(10)
                                                    else "set_${it.name}${getRandomString(5)}",
                                                    callingField.signature
                                                ).appendAnnotation(true)

                                            else -> null
                                        }

                                        if (genMethod != null) {
                                            if (shouldOuter) {
                                                genMethod.access = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC
                                                val clazz = newClasses.getOrPut(classNode) {
                                                    ClassNode().apply {
                                                        visit(
                                                            classNode.version,
                                                            Opcodes.ACC_PUBLIC,
                                                            "${classNode.name}\$FieldStatic",
                                                            null,
                                                            "java/lang/Object",
                                                            null
                                                        )
                                                    }
                                                }
                                                methodNode.instructions.set(
                                                    it,
                                                    MethodInsnNode(
                                                        Opcodes.INVOKESTATIC,
                                                        clazz.name,
                                                        genMethod.name,
                                                        genMethod.desc
                                                    )
                                                )
                                                clazz.methods.add(genMethod)
                                            } else {
                                                methodNode.instructions.set(
                                                    it,
                                                    MethodInsnNode(
                                                        Opcodes.INVOKESTATIC,
                                                        classNode.name,
                                                        genMethod.name,
                                                        genMethod.desc
                                                    )
                                                )
                                                classNode.methods.add(genMethod)
                                            }
                                            add()
                                        }
                                    }
                                }
                            }
                        }
                }
            newClasses.forEach { (_, c) ->
                classes[c.name] = c
            }
        }.get()
        return count
    }

    private fun MethodNode.appendAnnotation(downCall: Boolean): MethodNode {
        if (NativeCandidateTransformer.enabled) {
            if (downCall && downCalls) {
                NativeCandidateTransformer.appendedMethods.add(this)
                visitAnnotation(NativeCandidateTransformer.nativeAnnotation, false)
            } else if (upCalls) {
                NativeCandidateTransformer.appendedMethods.add(this)
                visitAnnotation(NativeCandidateTransformer.nativeAnnotation, false)
            }
        }
        return this
    }

    private fun genMethod(field: FieldInsnNode, methodName: String, signature: String?): MethodNode {
        val access = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC
        return when (field.opcode) {
            Opcodes.GETFIELD -> method(
                access,
                methodName,
                "(L${field.owner};)${field.desc}",
                signature,
                null
            ) {
                InsnList {
                    ALOAD(0)
                    GETFIELD(field.owner, field.name, field.desc)
                    INSN(methodNode.desc.getReturnType())
                }
            }

            Opcodes.PUTFIELD -> method(
                access,
                methodName,
                "(L${field.owner};${field.desc})V",
                signature,
                null,
            ) {
                InsnList {
                    var stack = 0
                    Type.getArgumentTypes(methodNode.desc).forEach {
                        VAR(it.getLoadType(), stack)
                        stack += it.size
                    }
                    PUTFIELD(field.owner, field.name, field.desc)
                    RETURN
                }
            }

            Opcodes.GETSTATIC -> method(
                access,
                methodName,
                "()${field.desc}",
                signature,
                null
            ) {
                InsnList {
                    GETSTATIC(field.owner, field.name, field.desc)
                    INSN(methodNode.desc.getReturnType())
                }
            }

            Opcodes.PUTSTATIC -> method(
                access,
                methodName,
                "(${field.desc})V",
                signature,
                null,
            ) {
                InsnList {
                    var stack = 0
                    Type.getArgumentTypes(methodNode.desc).forEach {
                        VAR(it.getLoadType(), stack)
                        stack += it.size
                    }
                    PUTSTATIC(field.owner, field.name, field.desc)
                    RETURN
                }
            }

            else -> throw Exception("Unsupported")
        }
    }

}