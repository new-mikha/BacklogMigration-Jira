package com.nulabinc.backlog.j2b.cli

import com.google.inject.{Guice, Injector}
import com.nulabinc.backlog.j2b.conf.{AppConfigValidator, AppConfiguration, ConfigValidateFailure}
import com.nulabinc.backlog.j2b.exporter.Exporter
import com.nulabinc.backlog.j2b.jira.conf.{JiraApiConfiguration, JiraBacklogPaths}
import com.nulabinc.backlog.j2b.jira.converter.MappingConverter
import com.nulabinc.backlog.j2b.jira.domain.mapping.MappingCollectDatabase
import com.nulabinc.backlog.j2b.jira.service._
import com.nulabinc.backlog.j2b.jira.writer.ProjectUserWriter
import com.nulabinc.backlog.j2b.mapping.converter.writes.MappingUserWrites
import com.nulabinc.backlog.j2b.modules._
import com.nulabinc.backlog.migration.common.conf.BacklogConfiguration
import com.nulabinc.backlog.migration.common.convert.Convert
import com.nulabinc.backlog.migration.common.modules.{ServiceInjector => BacklogInjector}
import com.nulabinc.backlog.migration.common.service.{ProjectService, SpaceService, PriorityService => BacklogPriorityService, StatusService => BacklogStatusService, UserService => BacklogUserService}
import com.nulabinc.backlog.migration.common.utils.{ConsoleOut, Logging}
import com.nulabinc.backlog.migration.importer.core.Boot
import com.nulabinc.jira.client.JiraRestClient
import com.nulabinc.jira.client.domain.User
import com.osinka.i18n.Messages

import scala.util.{Failure, Success, Try}

object J2BCli extends BacklogConfiguration
    with Logging
    with HelpCommand
    with ConfigValidator
    with MappingValidator
    with MappingConsole
    with ProgressConsole
    with InteractiveConfirm
    with Tracker {

  def export(config: AppConfiguration): Unit = {

    def createJiraExportingInjector(config: AppConfiguration): Option[Injector] =
      Try(Guice.createInjector(new ExportModule(config))) match {
        case Success(jiraInjector) => Some(jiraInjector)
        case Failure(error)        =>
          logger.error(error.getMessage)
          None
      }

    val backlogInjector = BacklogInjector.createInjector(config.backlogConfig)

    for {
      _            <- checkJiraApiAccessible(config.jiraConfig)
      jiraInjector <- createJiraExportingInjector(config)
      _            <- validateConfig(config, jiraInjector.getInstance(classOf[JiraRestClient]), backlogInjector.getInstance(classOf[SpaceService]))
    } yield {
      startExportMessage()

      val backlogUserService      = backlogInjector.getInstance(classOf[BacklogUserService])
      val backlogPriorityService  = backlogInjector.getInstance(classOf[BacklogPriorityService])
      val backlogStatusService    = backlogInjector.getInstance(classOf[BacklogStatusService])

      // Delete old exports
      val jiraBacklogPaths = new JiraBacklogPaths(config.jiraConfig.projectKey, config.backlogConfig.projectKey)

      jiraBacklogPaths.outputPath.deleteRecursively(force = true, continueOnFailure = true)

      // Export
      val exporter     = jiraInjector.getInstance(classOf[Exporter])
      val collectData  = exporter.export(jiraBacklogPaths)

      // Mapping file
      val mappingFileService  = jiraInjector.getInstance(classOf[MappingFileService])

      List(
        mappingFileService.createUserMappingFile(collectData.users.map(u => User(u.name, u.displayName)), backlogUserService.allUsers()),
        mappingFileService.createPriorityMappingFile(collectData.priorities, backlogPriorityService.allPriorities()),
        mappingFileService.createStatusMappingFile(collectData.statuses, backlogStatusService.allStatuses())
      ).foreach { mappingFile =>
        if (mappingFile.isExists) {
          displayMergedMappingFileMessageToConsole(mappingFile)
        } else {
          mappingFile.create()
          displayCreateMappingFileMessageToConsole(mappingFile)
        }
      }

      finishExportMessage()
    }

  }

  def `import`(config: AppConfiguration): Unit = {

    def createJiraImportingInjector(config: AppConfiguration): Option[Injector] =
      Try(Guice.createInjector(new ImportModule(config))) match {
        case Success(jiraInjector) => Some(jiraInjector)
        case Failure(error)        =>
          logger.error(error.getMessage)
          None
      }

    val backlogInjector = BacklogInjector.createInjector(config.backlogConfig)
    val spaceService    = backlogInjector.getInstance(classOf[SpaceService])

    for {
      _            <- checkJiraApiAccessible(config.jiraConfig)
      jiraInjector <- createJiraImportingInjector(config)
      _            <- validateConfig(config, jiraInjector.getInstance(classOf[JiraRestClient]), spaceService)
    } yield {
      val backlogUserService      = backlogInjector.getInstance(classOf[BacklogUserService])
      val backlogPriorityService  = backlogInjector.getInstance(classOf[BacklogPriorityService])
      val backlogStatusService    = backlogInjector.getInstance(classOf[BacklogStatusService])

      // Mapping file
      val jiraBacklogPaths    = new JiraBacklogPaths(config.jiraConfig.projectKey, config.backlogConfig.projectKey)
      val mappingFileService  = jiraInjector.getInstance(classOf[MappingFileService])
      val statusMappingFile   = mappingFileService.createStatusesMappingFileFromJson(jiraBacklogPaths.jiraStatusesJson, backlogStatusService.allStatuses())
      val priorityMappingFile = mappingFileService.createPrioritiesMappingFileFromJson(jiraBacklogPaths.jiraPrioritiesJson, backlogPriorityService.allPriorities())
      val userMappingFile     = mappingFileService.createUserMappingFileFromJson(jiraBacklogPaths.jiraUsersJson, backlogUserService.allUsers())

      for {
        _           <- mappingFileExists(statusMappingFile).right
        _           <- mappingFileExists(priorityMappingFile).right
        _           <- mappingFileExists(userMappingFile).right
        _           <- validateMapping(statusMappingFile).right
        _           <- validateMapping(priorityMappingFile).right
        _           <- validateMapping(userMappingFile).right
        projectKeys <- confirmProject(config, backlogInjector.getInstance(classOf[ProjectService])).right
        _           <- finalConfirm(projectKeys, statusMappingFile, priorityMappingFile, userMappingFile).right
      } yield {

        // Collect database
        val database = jiraInjector.getInstance(classOf[MappingCollectDatabase])
        mappingFileService.usersFromJson(jiraBacklogPaths.jiraUsersJson).foreach { user =>
          database.add(user)
        }

        // Convert
        val converter = jiraInjector.getInstance(classOf[MappingConverter])
        converter.convert(
          database      = database,
          userMaps      = userMappingFile.tryUnMarshal(),
          priorityMaps  = priorityMappingFile.tryUnMarshal(),
          statusMaps    = statusMappingFile.tryUnMarshal()
        )

        // Project users mapping
        implicit val mappingUserWrites: MappingUserWrites = new MappingUserWrites
        val projectUserWriter = jiraInjector.getInstance(classOf[ProjectUserWriter])
        val projectUsers = userMappingFile.tryUnMarshal().map(Convert.toBacklog(_))
        projectUserWriter.write(projectUsers)

        // Import
        Boot.execute(config.backlogConfig, false)

        // Tracking
        if (!config.isOptOut) {
          val userService = backlogInjector.getInstance(classOf[BacklogUserService])
          tracking(config, spaceService, userService)
        }
      }
    }
  }

  private def checkJiraApiAccessible(config: JiraApiConfiguration): Option[Unit] = {
    // Check JIRA configuration is correct. Before creating injector.
    val jiraClient = JiraRestClient(config.url, config.username, config.password)
    AppConfigValidator.validateConfigJira(jiraClient) match {
      case ConfigValidateFailure(failure) =>
        ConsoleOut.println(Messages("cli.param.error.disable.access.jira", Messages("common.jira")))
        logger.error(failure)
        None
      case _ => Some(())
    }
  }

}
