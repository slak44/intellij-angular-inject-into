package slak.actions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.AbstractTreeClassChooserDialog
import com.intellij.lang.javascript.TypeScriptFileType
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSElementVisitor
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.ecma6.ES6Decorator
import com.intellij.lang.javascript.psi.ecma6.TypeScriptClass
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunction
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.impl.JSPsiElementFactory
import com.intellij.lang.javascript.psi.stubs.JSClassIndex
import com.intellij.lang.typescript.psi.TypeScriptAutoImportUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import icons.JavaScriptPsiIcons
import javax.swing.tree.DefaultMutableTreeNode

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

  private fun injectSelectedIntoConstructor(
    editor: Editor,
    constructor: TypeScriptFunction,
    selected: TypeScriptClass
  ) {
    val selectedName = selected.name!!
    val lowerCamelCased = selectedName.decapitalize()

    val fakeParam = "private readonly $lowerCamelCased: $selectedName"
    val fakeClass = JSPsiElementFactory.createJSClass("class X { constructor($fakeParam) {} }", constructor)
    val parameterPsi = fakeClass.constructor!!.parameters.single()

    val paramList = constructor.parameterList!!

    WriteCommandAction.runWriteCommandAction(editor.project, "Add Constructor Parameter", "inject-into", {
      // Last child is the closing paren
      paramList.addBefore(parameterPsi, paramList.lastChild)

      TypeScriptAutoImportUtil.addImportStatement(editor, selectedName, selected, constructor.containingFile)

      hintManager.showInformationHint(editor, "Injected $selectedName as $lowerCamelCased")
    }, constructor.containingFile)
  }

  private fun handleConstructorParameterInjection(editor: Editor, constructor: TypeScriptFunction) {
    val project = editor.project!!

    val dialog = object :
      AbstractTreeClassChooserDialog<TypeScriptClass>("Select Class To Inject", project, TypeScriptClass::class.java) {
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
        val a = JSClassIndex.getElements(name, project, GlobalSearchScope.everythingScope(project))
        return a
          .filterIsInstance<TypeScriptClass>()
//          .filter { tsClass -> tsClass.attributeList?.decorators?.any { it.decoratorName == "Injectable" } == true }
          .toMutableList()
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
      hintManager.showErrorHint(editor, "TypeScript classes must not have more than one constructor.")
      return
    }

    if (inClass.constructors.isEmpty()) {
      val fakeClass = JSPsiElementFactory.createJSClass("class X { constructor() {} }", inClass)
      val constructorPsi = fakeClass.constructor!!

      WriteCommandAction.runWriteCommandAction(editor.project, "Add Constructor", "inject-into", {
        val firstFunction = inClass.functions.firstOrNull()
        val inserted = if (firstFunction != null) {
          firstFunction.parent.addBefore(constructorPsi, firstFunction)
        } else {
          val func = inClass.addBefore(constructorPsi, inClass.lastChild)
          CodeStyleManager.getInstance(editor.project!!).reformat(inClass)
          func
        }

        check(inserted is TypeScriptFunction)
        handleConstructorParameterInjection(editor, inserted)
      }, inClass.containingFile)
    } else {
      val constructor = inClass.constructor
      check(constructor is TypeScriptFunction)
      handleConstructorParameterInjection(editor, constructor)
    }
  }

  private fun selectInjectionTarget(editor: Editor, injectionTargets: List<InjectionTarget>) {
    val tsClassIcons = List(injectionTargets.size) { JavaScriptPsiIcons.Classes.TypeScriptClass }

    popupFactory.createListPopup(object :
      BaseListPopupStep<InjectionTarget>("Select Class To Inject Into", injectionTargets, tsClassIcons) {
      var chosen: InjectionTarget? = null

      override fun getTextFor(value: InjectionTarget): String {
        return value.target.name ?: "Unknown class name"
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
      hintManager.showErrorHint(editor, "No injection targets found in this file.")
      return
    }

    if (injectionTargets.size == 1) {
      handleConstructorInjection(editor, injectionTargets.single())
    } else {
      selectInjectionTarget(editor, injectionTargets)
    }
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
