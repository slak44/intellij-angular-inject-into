package com.github.slak44.intellijangularinjectinto.services

import com.intellij.openapi.project.Project
import com.github.slak44.intellijangularinjectinto.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
