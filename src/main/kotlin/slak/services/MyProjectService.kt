package slak.services

import com.intellij.openapi.project.Project
import slak.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
