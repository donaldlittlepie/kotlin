/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.decompiler.builtIns

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiManager
import com.intellij.psi.compiled.ClassFileDecompilers
import org.jetbrains.kotlin.builtins.BuiltInsBinaryVersion
import org.jetbrains.kotlin.builtins.BuiltInsSerializedResourcePaths
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.decompiler.KotlinDecompiledFileViewProvider
import org.jetbrains.kotlin.idea.decompiler.KtDecompiledFile
import org.jetbrains.kotlin.idea.decompiler.classFile.CURRENT_ABI_VERSION_MARKER
import org.jetbrains.kotlin.idea.decompiler.classFile.FILE_ABI_VERSION_MARKER
import org.jetbrains.kotlin.idea.decompiler.classFile.INCOMPATIBLE_ABI_VERSION_COMMENT
import org.jetbrains.kotlin.idea.decompiler.textBuilder.DecompiledText
import org.jetbrains.kotlin.idea.decompiler.textBuilder.buildDecompiledText
import org.jetbrains.kotlin.idea.decompiler.textBuilder.defaultDecompilerRendererOptions
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.serialization.ProtoBuf
import org.jetbrains.kotlin.serialization.builtins.BuiltInsProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.NameResolverImpl
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class KotlinBuiltInDecompiler : ClassFileDecompilers.Full() {
    private val stubBuilder = KotlinBuiltInStubBuilder()

    override fun accepts(file: VirtualFile): Boolean {
        return file.fileType == KotlinBuiltInFileType
    }

    override fun getStubBuilder() = stubBuilder

    override fun createFileViewProvider(file: VirtualFile, manager: PsiManager, physical: Boolean): FileViewProvider {
        return KotlinDecompiledFileViewProvider(manager, file, physical) { provider ->
            KtDecompiledFile(provider) { file ->
                buildDecompiledTextForBuiltIns(file)
            }
        }
    }
}

private val decompilerRendererForBuiltIns = DescriptorRenderer.withOptions { defaultDecompilerRendererOptions() }

fun buildDecompiledTextForBuiltIns(builtInFile: VirtualFile): DecompiledText {
    if (builtInFile.fileType != KotlinBuiltInFileType) {
        error("Unexpected file type ${builtInFile.fileType}")
    }

    val stream = ByteArrayInputStream(builtInFile.contentsToByteArray())

    val dataInput = DataInputStream(stream)
    val version = BuiltInsBinaryVersion(*(1..dataInput.readInt()).map { dataInput.readInt() }.toIntArray())
    if (!version.isCompatible()) {
        // TODO: test
        return DecompiledText(
                INCOMPATIBLE_ABI_VERSION_COMMENT
                        .replace(CURRENT_ABI_VERSION_MARKER, BuiltInsBinaryVersion.INSTANCE.toString())
                        .replace(FILE_ABI_VERSION_MARKER, version.toString()),
                mapOf()
        )
    }

    val proto = BuiltInsProtoBuf.BuiltIns.parseFrom(stream, BuiltInsSerializedResourcePaths.extensionRegistry)
    val nameResolver = NameResolverImpl(proto.strings, proto.qualifiedNames)
    val packageFqName = proto.`package`.packageFqName(nameResolver)
    val resolver = KotlinBuiltInDeserializerForDecompiler(builtInFile.parent!!, packageFqName, proto, nameResolver)
    val declarations = arrayListOf<DeclarationDescriptor>()
    if (proto.hasPackage()) {
        declarations.addAll(resolver.resolveDeclarationsInFacade(packageFqName))
    }
    for (classProto in proto.classList) {
        val classId = nameResolver.getClassId(classProto.fqName)
        if (!classId.isNestedClass) {
            declarations.add(resolver.resolveTopLevelClass(classId)!!)
        }
    }
    return buildDecompiledText(packageFqName, declarations, decompilerRendererForBuiltIns)
}

internal fun ProtoBuf.Package.packageFqName(nameResolver: NameResolverImpl): FqName {
    return nameResolver.getPackageFqName(getExtension(BuiltInsProtoBuf.packageFqName))
}
