/*
 *   Jagr - SourceGrade.org
 *   Copyright (C) 2021-2022 Alexander Staeding
 *   Copyright (C) 2021-2022 Contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.sourcegrade.jagr.core.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.sourcegrade.jagr.api.testing.ClassTransformer
import org.sourcegrade.jagr.api.testing.ClassTransformerOrder
import org.sourcegrade.jagr.core.compiler.java.CompiledClass
import org.sourcegrade.jagr.core.compiler.java.JavaCompiledContainer
import java.util.function.Supplier
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

fun interface TransformationApplier {
    fun transform(result: JavaCompiledContainer, classLoader: ClassLoader): JavaCompiledContainer
}

operator fun TransformationApplier.plus(other: TransformationApplier): TransformationApplier {
    if (this is NoOpTransformerAppliedImpl) return other
    if (other is NoOpTransformerAppliedImpl) return this
    return TransformationApplier { result, classLoader -> other.transform(transform(result, classLoader), classLoader) }
}

private object NoOpTransformerAppliedImpl : TransformationApplier {
    override fun transform(result: JavaCompiledContainer, classLoader: ClassLoader): JavaCompiledContainer = result
}

private class TransformerApplierImpl(private val transformer: ClassTransformer) : TransformationApplier {
    override fun transform(result: JavaCompiledContainer, classLoader: ClassLoader): JavaCompiledContainer = result.copy(
        runtimeResources = result.runtimeResources.copy(
            classes = transformer.transform(result.runtimeResources.classes, classLoader),
        ),
    )
}

private class MultiTransformerApplierImpl(private vararg val transformers: Supplier<ClassTransformer>) : TransformationApplier {
    override fun transform(result: JavaCompiledContainer, classLoader: ClassLoader): JavaCompiledContainer {
        var classes = result.runtimeResources.classes
        for (transformer in transformers) {
            val classTransformer = transformer.get()
            val transformedClasses: MutableMap<String, CompiledClass> = mutableMapOf()
            transformedClasses += classTransformer.transform(classes, classLoader)
            transformedClasses += classTransformer.injectClasses().mapValues { (name, bytecode) -> CompiledClass.Existing(name, bytecode) }
            classes = transformedClasses
        }
        return result.copy(runtimeResources = result.runtimeResources.copy(classes = classes))
    }
}

fun applierOf(vararg transformers: Supplier<ClassTransformer>): TransformationApplier {
    return when (transformers.size) {
        0 -> NoOpTransformerAppliedImpl
        1 -> TransformerApplierImpl(transformers[0].get())
        else -> MultiTransformerApplierImpl(*transformers)
    }
}

fun Map<ClassTransformerOrder, List<Supplier<ClassTransformer>>>.createApplier(
    order: ClassTransformerOrder,
    predicate: (JavaCompiledContainer) -> Boolean,
): TransformationApplier {
    val backing = MultiTransformerApplierImpl(*(this[order] ?: return applierOf()).toTypedArray())
    return TransformationApplier { result, classLoader ->
        if (predicate(result)) backing.transform(result, classLoader) else result
    }
}

fun ClassTransformer.transform(classes: Map<String, CompiledClass>, classLoader: ClassLoader): Map<String, CompiledClass> {
    return classes.map { (_, compiledClass) ->
        val transformedClass = compiledClass.transformed(transform(compiledClass.bytecode, classLoader))
        transformedClass.className to transformedClass
    }.toMap()
}

fun ClassTransformer.transform(byteArray: ByteArray, classLoader: ClassLoader): ByteArray {
    val reader = ClassReader(byteArray)
    val writer = object : ClassWriter(reader, writerFlags) {
        override fun getClassLoader() = classLoader
    }
    transform(reader, writer)
    return writer.toByteArray()
}

fun MethodVisitor.visitMethodInsn(
    opcode: Int,
    function: KFunction<*>,
    isInterface: Boolean = false,
) {
    val method = requireNotNull(function.javaMethod)
    visitMethodInsn(
        opcode,
        Type.getInternalName(method.declaringClass),
        method.name,
        Type.getMethodDescriptor(method),
        isInterface,
    )
}
