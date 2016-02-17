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

import com.intellij.psi.compiled.ClsStubBuilder
import com.intellij.psi.impl.compiled.ClassFileStubBuilder
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.builtins.BuiltInsBinaryVersion
import org.jetbrains.kotlin.builtins.BuiltInsClassDataFinder
import org.jetbrains.kotlin.builtins.BuiltInsSerializedResourcePaths
import org.jetbrains.kotlin.idea.decompiler.common.AnnotationLoaderForStubBuilderImpl
import org.jetbrains.kotlin.idea.decompiler.stubBuilder.*
import org.jetbrains.kotlin.serialization.builtins.BuiltInsProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.NameResolverImpl
import org.jetbrains.kotlin.serialization.deserialization.ProtoContainer
import org.jetbrains.kotlin.serialization.deserialization.TypeTable
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class KotlinBuiltInStubBuilder : ClsStubBuilder() {
    override fun getStubVersion() = ClassFileStubBuilder.STUB_VERSION + 2

    override fun buildFileStub(content: FileContent): PsiFileStub<*>? {
        val file = content.file
        if (file.fileType != KotlinBuiltInFileType) {
            error("Unexpected file type ${file.fileType}")
        }

        val stream = ByteArrayInputStream(content.content)

        val dataInput = DataInputStream(stream)
        val version = BuiltInsBinaryVersion(*(1..dataInput.readInt()).map { dataInput.readInt() }.toIntArray())
        if (!version.isCompatible()) {
            return createIncompatibleAbiVersionFileStub()
        }

        val proto = BuiltInsProtoBuf.BuiltIns.parseFrom(stream, BuiltInsSerializedResourcePaths.extensionRegistry)
        val nameResolver = NameResolverImpl(proto.strings, proto.qualifiedNames)

        val packageProto = proto.`package`
        val packageFqName = packageProto.packageFqName(nameResolver)
        val components = ClsStubBuilderComponents(
                BuiltInsClassDataFinder(proto, nameResolver),
                AnnotationLoaderForStubBuilderImpl(BuiltInSerializerProtocol),
                file
        )
        val context = components.createContext(nameResolver, packageFqName, TypeTable(packageProto.typeTable))

        val fileStub = createFileStub(packageFqName)
        createCallableStubs(
                fileStub, context,
                ProtoContainer.Package(packageFqName, context.nameResolver, context.typeTable, packagePartSource = null),
                packageProto.functionList, packageProto.propertyList
        )
        for (classProto in proto.classList) {
            val classId = nameResolver.getClassId(classProto.fqName)
            if (!classId.isNestedClass) {
                createClassStub(fileStub, classProto, nameResolver, classId, context)
            }
        }
        return fileStub
    }
}
