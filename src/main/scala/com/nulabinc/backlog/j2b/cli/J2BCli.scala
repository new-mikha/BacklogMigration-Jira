package com.nulabinc.backlog.j2b.cli

import com.google.inject.Guice
import com.nulabinc.backlog.j2b.conf.{AppConfigValidator, AppConfiguration}
import com.nulabinc.backlog.j2b.exporter.Exporter
import com.nulabinc.backlog.j2b.modules.JiraDefaultModule
import com.nulabinc.backlog.migration.common.conf.BacklogConfiguration
import com.nulabinc.backlog.migration.common.utils.{ConsoleOut, Logging}
import com.nulabinc.jira.client.JiraRestClient
import com.nulabinc.jira.client.domain.field.Field
import com.osinka.i18n.Messages

object J2BCli extends BacklogConfiguration
    with Logging
    with HelpCommand {

  def export(config: AppConfiguration): Unit = {

    val injector = Guice.createInjector(new JiraDefaultModule(config))

    if (validateConfig(config)) {
      val exporter = injector.getInstance(classOf[Exporter])
//      val jiraClient = injector.getInstance(classOf[JiraRestClient])
//      val fields = jiraClient.fieldRestClient.all()

      exporter.export()
    }
  }

  def migrate(config: AppConfiguration): Unit = {
    if (validateConfig(config)) {

    }
  }

  def doImport(config: AppConfiguration): Unit = {
    if (validateConfig(config)) {

    }
  }

  private[this] def validateConfig(config: AppConfiguration): Boolean = {
    val validator = new AppConfigValidator()
    val errors = validator.validate(config)
    if (errors.isEmpty) true
    else {
      val message =
        s"""
           |
           |${Messages("cli.param.error")}
           |--------------------------------------------------
           |${errors.mkString("\n")}
           |
        """.stripMargin
      ConsoleOut.error(message)
      false
    }
  }
}
