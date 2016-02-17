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

package org.jetbrains.kotlin.idea.decompiler.stubBuilder

import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileContentImpl
import org.jetbrains.kotlin.builtins.BuiltInsSerializedResourcePaths
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.idea.decompiler.builtIns.KotlinBuiltInDecompiler
import org.jetbrains.kotlin.idea.decompiler.classFile.KotlinClassFileDecompiler
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.psi.stubs.KotlinClassStub
import org.junit.Assert

class BuiltInStubConsistencyTest : KotlinLightCodeInsightFixtureTestCase() {
    private val classFileDecompiler = KotlinClassFileDecompiler()
    private val builtInsDecompiler = KotlinBuiltInDecompiler()

    fun testSameAsClsDecompilerForCompiledBuiltInClasses() {
        for (packageFqName in KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAMES) {
            checkStubTreeMatchForClassesInDirectory(packageFqName.asString())
        }
    }

    // Check stubs for decompiled built-in classes against stubs for decompiled JVM class files, assuming the latter are well tested
    // Check only those classes, stubs for which are present in the giant stub for a decompiled .kotlin_builtins file
    private fun checkStubTreeMatchForClassesInDirectory(packageFqName: String) {
        val dir = findDir(packageFqName, project)
        val groupedByExtension = dir.children.groupBy { it.extension }
        val classFiles = groupedByExtension[JavaClassFileType.INSTANCE.defaultExtension]!!.map { it.nameWithoutExtension }
        val builtInsFile = groupedByExtension[BuiltInsSerializedResourcePaths.BUILTINS_FILE_EXTENSION]!!.single()

        val builtInFileStub = builtInsDecompiler.stubBuilder.buildFileStub(FileContentImpl.createByFile(builtInsFile))!!

        for (className in classFiles) {
            val classFile = dir.findChild(className + ".class")!!
            val classFileStub =
                    classFileDecompiler.stubBuilder.buildFileStub(FileContentImpl.createByFile(classFile)) as? KotlinClassStub ?: continue
            val classFqName = classFileStub.getFqName()
            val builtInClassStub = builtInFileStub.childrenStubs.firstOrNull {
                it is KotlinClassStub && it.getFqName() == classFqName
            } ?: continue
            Assert.assertEquals("Stub mismatch for $classFqName", classFileStub.serializeToString(), builtInClassStub.serializeToString())
        }
    }

    override fun getProjectDescriptor() = KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE
}

fun findDir(packageFqName: String, project: Project): VirtualFile {
    val classNameIndex = KotlinFullClassNameIndex.getInstance()
    val randomClassInPackage = classNameIndex.getAllKeys(project).find { it.startsWith(packageFqName + ".") && "." !in it.substringAfter(packageFqName + ".") }!!
    val classes = classNameIndex.get(randomClassInPackage, project, GlobalSearchScope.allScope(project))
    return classes.first().containingFile.virtualFile.parent!!
}
