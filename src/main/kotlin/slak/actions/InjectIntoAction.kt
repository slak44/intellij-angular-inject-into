package slak.actions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.AbstractTreeClassChooserDialog
import com.intellij.lang.ecmascript6.actions.ES6AddImportExecutor
import com.intellij.lang.ecmascript6.actions.JSImportDescriptorBuilder
import com.intellij.lang.javascript.TypeScriptFileType
import com.intellij.lang.javascript.modules.JSImportPlaceInfo.ImportContext
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecma6.ES6Decorator
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunction
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.impl.JSPsiElementFactory
import com.intellij.lang.javascript.psi.stubs.JSClassIndex
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.parentOfType
import icons.JavaScriptPsiIcons
import org.angular2.index.Angular2MetadataClassNameIndexKey
import slak.TranslationsBundle.message
import java.util.*
import javax.swing.tree.DefaultMutableTreeNode

const val GROUP_ID = "inject-into"

data class InjectionTarget(val target: TypeScriptClass, val matchedDecoratorName: String)

fun findInjectionTargets(psiFile: PsiFile): List<InjectionTarget> {
  val injectionTargets = mutableListOf<InjectionTarget>()

  psiFile.accept(object : JSElementVisitor() {
    var enclosingClass: TypeScriptClass? = null

    override fun visitFile(file: PsiFile) {
      file.acceptChildren(this)
    }

    override fun visitTypeScriptClass(typeScriptClass: TypeScriptClass) {
      enclosingClass = typeScriptClass
      typeScriptClass.attributeList?.accept(this)
      enclosingClass = null
    }

    override fun visitJSAttributeList(attributeList: JSAttributeList) {
      attributeList.acceptChildren(this)
    }

    override fun visitES6Decorator(decorator: ES6Decorator) {
      val decoratorName = decorator.decoratorName ?: return

      if (decoratorName !in listOf("Component", "Directive", "Injectable", "NgModule")) {
        return
      }

      val decoratorCall = decorator.expression

      if (decoratorCall !is JSCallExpression) {
        return
      }

      val methodReference = decoratorCall.methodExpression?.reference ?: return
      val resolvedReference = methodReference.resolve() ?: return
      val resolvedFile = resolvedReference.parentOfType<JSFile>() ?: return
      val resolvedDirectory = resolvedFile.containingDirectory.virtualFile

      if ("node_modules/@angular/core" !in resolvedDirectory.path) {
        return
      }

      injectionTargets += InjectionTarget(enclosingClass!!, decoratorName)
    }
  })

  return injectionTargets
}

class InjectIntoAction : AnAction() {
  private val hintManager = HintManager.getInstance()
  private val popupFactory = JBPopupFactory.getInstance()

  private fun hasTrailingComma(paramList: JSParameterList): Boolean {
    return try {
      var trailingComma = paramList.lastChild.prevSibling
      while (trailingComma != null && trailingComma is PsiWhiteSpace) {
        trailingComma = trailingComma.prevSibling
      }

      trailingComma != null && trailingComma.text == ","
    } catch (e: Exception) {
      e.printStackTrace()

      false
    }
  }

  private fun injectSelectedIntoConstructor(
    editor: Editor,
    constructor: TypeScriptFunction,
    selected: TypeScriptClass
  ) {
    val selectedName = selected.name!!
    val lowerCamelCased = selectedName.replaceFirstChar { it.lowercase(Locale.getDefault()) }

    val fakeParam = "private readonly $lowerCamelCased: $selectedName"
    val fakeClass = JSPsiElementFactory.createJSClass("class X { constructor($fakeParam,) {} }", constructor)
    val parameterPsi = fakeClass.constructor!!.parameters.single()
    val commaElement = parameterPsi.nextSibling

    val paramList = constructor.parameterList!!
    val isAddingFirstParam = paramList.parameters.isEmpty()

    val hasTrailingComma = hasTrailingComma(paramList)
    val maybeTrailingWhitespace = paramList.lastChild.prevSibling.copy()

    val importExecutor = object : ES6AddImportExecutor(constructor.containingFile) {
      fun executeNotDeprecated(importedName: String, elementToImport: JSElement) {
        val descriptor =
          JSImportDescriptorBuilder(place).createDescriptor(importedName, elementToImport, ImportContext.SIMPLE)
        if (descriptor != null) {
          this.createImportOrUpdateExisting(descriptor)
        }
      }
    }

    val isInjectingFromCurrentFile = selected.containingFile == constructor.containingFile

    WriteCommandAction.runWriteCommandAction(editor.project, message("add_constructor_parameter"), GROUP_ID, {
      // Last child is the closing paren
      val addedParam = paramList.addBefore(parameterPsi, paramList.lastChild)

      if (!isAddingFirstParam && !hasTrailingComma) {
        paramList.addBefore(commaElement, addedParam)
      }

      if (hasTrailingComma) {
        paramList.addAfter(commaElement, addedParam)
      }

      if (!isAddingFirstParam && maybeTrailingWhitespace is PsiWhiteSpace) {
        paramList.addBefore(maybeTrailingWhitespace, paramList.lastChild)
      }

      if (!isInjectingFromCurrentFile) {
        importExecutor.executeNotDeprecated(selectedName, selected)
      }

      hintManager.showInformationHint(editor, message("injected_as", selectedName, lowerCamelCased))
    }, constructor.containingFile)
  }

  private fun handleConstructorParameterInjection(editor: Editor, constructor: TypeScriptFunction) {
    val project = editor.project!!

    val dialog = object : AbstractTreeClassChooserDialog<TypeScriptClass>(
      message("select_class_to_inject"),
      project,
      TypeScriptClass::class.java
    ) {
      override fun getSelectedFromTreeUserObject(node: DefaultMutableTreeNode): TypeScriptClass? {
        val treeUserObject = node.userObject
        if (treeUserObject !is PsiFileNode) {
          return null
        }

        val psiFile = treeUserObject.element!!.value

        if (psiFile.fileType !is TypeScriptFileType) {
          return null
        }

        return findInjectionTargets(psiFile).firstOrNull()?.target
      }

      override fun getClassesByName(
        name: String,
        checkBoxState: Boolean,
        pattern: String,
        searchScope: GlobalSearchScope
      ): MutableList<TypeScriptClass> {
        val jsClasses = JSClassIndex.getElements(name, project, GlobalSearchScope.projectScope(project))
        val projectTsClasses = jsClasses.filterIsInstance<TypeScriptClass>()

        val hasNoAngularClass = StubIndex.getInstance().processAllKeys(Angular2MetadataClassNameIndexKey, project) { it != name }
        if (hasNoAngularClass) {
          return projectTsClasses.toMutableList()
        }

        val allClasses = JSClassIndex.getElements(name, project, GlobalSearchScope.everythingScope(project))
        val angularClasses = allClasses.filterIsInstance<TypeScriptClass>().filter { it.isExported }
        return (projectTsClasses.toSet() + angularClasses).toMutableList()
      }
    }

    dialog.showDialog()

    if (dialog.selected != null) {
      injectSelectedIntoConstructor(editor, constructor, dialog.selected)
    }
  }

  private fun handleConstructorInjection(editor: Editor, injectionTarget: InjectionTarget) {
    val (inClass) = injectionTarget

    if (inClass.constructors.size > 1) {
      hintManager.showErrorHint(editor, message("typescript_single_constructor"))
      return
    }

    if (inClass.constructors.isEmpty()) {
      val fakeClass = JSPsiElementFactory.createJSClass("class X { constructor() {} }", inClass)
      val constructorPsi = fakeClass.constructor!!

      var insertedFunction: TypeScriptFunction? = null

      WriteCommandAction.runWriteCommandAction(editor.project, message("add_constructor"), GROUP_ID, {
        val firstFunction = inClass.functions.firstOrNull()
        val inserted = if (firstFunction != null) {
          firstFunction.parent.addBefore(constructorPsi, firstFunction)
        } else {
          val func = inClass.addBefore(constructorPsi, inClass.lastChild)
          CodeStyleManager.getInstance(editor.project!!).reformat(inClass)
          func
        }

        check(inserted is TypeScriptFunction)
        insertedFunction = inserted
      }, inClass.containingFile)

      handleConstructorParameterInjection(editor, requireNotNull(insertedFunction))
    } else {
      val constructor = inClass.constructor
      check(constructor is TypeScriptFunction)
      handleConstructorParameterInjection(editor, constructor)
    }
  }

  private fun selectInjectionTarget(editor: Editor, injectionTargets: List<InjectionTarget>) {
    val tsClassIcons = List(injectionTargets.size) { JavaScriptPsiIcons.Classes.TypeScriptClass }

    popupFactory.createListPopup(object :
      BaseListPopupStep<InjectionTarget>(message("select_class_to_inject_into"), injectionTargets, tsClassIcons) {
      var chosen: InjectionTarget? = null

      override fun getTextFor(value: InjectionTarget): String {
        return value.target.name ?: message("unknown_class_name")
      }

      override fun onChosen(selectedValue: InjectionTarget, finalChoice: Boolean): PopupStep<*>? {
        chosen = selectedValue

        return null
      }

      override fun getFinalRunnable() = Runnable { handleConstructorInjection(editor, chosen!!) }
    }).showInBestPositionFor(editor)
  }

  override fun actionPerformed(event: AnActionEvent) {
    val editor = requireNotNull(event.getData(PlatformDataKeys.EDITOR))
    val psiFile = requireNotNull(event.getData(PlatformDataKeys.PSI_FILE))

    val injectionTargets = findInjectionTargets(psiFile)

    if (injectionTargets.isEmpty()) {
      hintManager.showErrorHint(editor, message("no_injection_targets"))
      return
    }

    if (injectionTargets.size == 1) {
      handleConstructorInjection(editor, injectionTargets.single())
    } else {
      selectInjectionTarget(editor, injectionTargets)
    }
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }

  override fun update(event: AnActionEvent) {
    val project = event.project
    val vFile = event.getData(PlatformDataKeys.VIRTUAL_FILE)
    val editor = event.getData(PlatformDataKeys.EDITOR)

    if (project == null || vFile == null || editor == null) {
      event.presentation.isEnabledAndVisible = false
      return
    }

    val isTypeScript = vFile.fileType is TypeScriptFileType

    event.presentation.isEnabledAndVisible = isTypeScript
  }
}
