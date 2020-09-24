package com.nulabinc.backlog.j2b.cli

import com.google.inject.Guice
import com.nulabinc.backlog.j2b._
import com.nulabinc.backlog.j2b.conf.{AppConfigValidator, AppConfiguration}
import com.nulabinc.backlog.j2b.core.Finalizer
import com.nulabinc.backlog.j2b.exporter.Exporter
import com.nulabinc.backlog.j2b.jira.conf.JiraBacklogPaths
import com.nulabinc.backlog.j2b.jira.domain.mapping._
import com.nulabinc.backlog.j2b.jira.writer.ProjectUserWriter
import com.nulabinc.backlog.j2b.mapping.converter.MappingConvertService
import com.nulabinc.backlog.j2b.mapping.converter.writes.MappingUserWrites
import com.nulabinc.backlog.j2b.modules._
import com.nulabinc.backlog.migration.common.conf.{BacklogConfiguration, BacklogPaths, MappingDirectory}
import com.nulabinc.backlog.migration.common.convert.Convert
import com.nulabinc.backlog.migration.common.domain.mappings.{ValidatedPriorityMapping, ValidatedStatusMapping, ValidatedUserMapping}
import com.nulabinc.backlog.migration.common.dsl.{AppDSL, ConsoleDSL, StorageDSL}
import com.nulabinc.backlog.migration.common.interpreters.{JansiConsoleDSL, LocalStorageDSL, TaskAppDSL}
import com.nulabinc.backlog.migration.common.messages.ConsoleMessages
import com.nulabinc.backlog.migration.common.modules.{ServiceInjector => BacklogInjector}
import com.nulabinc.backlog.migration.common.service.{
  ProjectService,
  SpaceService,
  PriorityService => BacklogPriorityService,
  StatusService => BacklogStatusService,
  UserService => BacklogUserService
}
import com.nulabinc.backlog.migration.common.services.{PriorityMappingFileService, StatusMappingFileService, UserMappingFileService}
import com.nulabinc.backlog.migration.common.utils.Logging
import com.nulabinc.backlog.migration.importer.core.Boot
import com.nulabinc.jira.client.JiraRestClient
import com.osinka.i18n.Messages
import monix.eval.Task
import monix.execution.Scheduler

object J2BCli extends BacklogConfiguration with Logging with HelpCommand with MappingValidator with MappingConsole with ProgressConsole {

  import com.nulabinc.backlog.j2b.deserializers.JiraMappingDeserializer._
  import com.nulabinc.backlog.j2b.formatters.JiraFormatter._
  import com.nulabinc.backlog.j2b.mapping.JiraMappingHeader._
  import com.nulabinc.backlog.j2b.serializers.JiraMappingSerializer._
  import com.nulabinc.backlog.migration.common.shared.syntax._

  private implicit val appDSL: AppDSL[Task]         = TaskAppDSL()
  private implicit val storageDSL: StorageDSL[Task] = LocalStorageDSL()
  private implicit val consoleDSL: ConsoleDSL[Task] = JansiConsoleDSL()

  def export(config: AppConfiguration, nextCommandStr: String)(implicit
      s: Scheduler
  ): Task[Either[AppError, Unit]] = {
    val backlogInjector = BacklogInjector.createInjector(config.backlogConfig)
    val backlogUserService =
      backlogInjector.getInstance(classOf[BacklogUserService])
    val backlogPriorityService =
      backlogInjector.getInstance(classOf[BacklogPriorityService])
    val backlogStatusService =
      backlogInjector.getInstance(classOf[BacklogStatusService])
    val jiraInjector     = Guice.createInjector(new ExportModule(config))
    val jiraBacklogPaths = new JiraBacklogPaths(config.backlogConfig.projectKey)
//    val storeDSL                = SQLiteStoreDSL(jiraBacklogPaths.dbPath)
    val exporter = jiraInjector.getInstance(classOf[Exporter])

    val result = for {
//      _ <- checkJiraApiAccessible(config.jiraConfig).handleError
      _ <- validateConfig(
        config,
        jiraInjector.getInstance(classOf[JiraRestClient]),
        backlogInjector.getInstance(classOf[SpaceService])
      ).handleError
      _ <- startExportMessage().handleError
    } yield {
      // Delete old exports
      if (jiraBacklogPaths.outputPath.exists) {
        jiraBacklogPaths.outputPath.listRecursively.foreach(_.delete(false))
      }

      // Export
      val collectDataTask = exporter.export(jiraBacklogPaths)

      val collectedData        = collectDataTask.runSyncUnsafe()
      val statusMappingItems   = collectedData.statuses.map(status => JiraStatusMappingItem(status.name, status.name))
      val priorityMappingItems = collectedData.priorities.map(priority => JiraPriorityMappingItem(priority.name))
      val userMappingItems =
        collectedData.getUsers.map(JiraUserMappingItem.from)

      StatusMappingFileService
        .init[JiraStatusMappingItem, Task](
          mappingFilePath = MappingDirectory.default.statusMappingFilePath,
          mappingListPath = MappingDirectory.default.statusMappingListFilePath,
          srcItems = statusMappingItems,
          dstItems = backlogStatusService.allStatuses()
        )
        .runSyncUnsafe()

      PriorityMappingFileService
        .init[JiraPriorityMappingItem, Task](
          mappingFilePath = MappingDirectory.default.priorityMappingFilePath,
          mappingListPath = MappingDirectory.default.priorityMappingListFilePath,
          srcItems = priorityMappingItems,
          dstItems = backlogPriorityService.allPriorities()
        )
        .runSyncUnsafe()

      UserMappingFileService
        .init[JiraUserMappingItem, Task](
          mappingFilePath = MappingDirectory.default.userMappingFilePath,
          mappingListPath = MappingDirectory.default.userMappingListFilePath,
          srcItems = userMappingItems,
          dstItems = backlogUserService.allUsers(),
          dstApiConfiguration = config.backlogConfig
        )
        .runSyncUnsafe()

      finishExportMessage(nextCommandStr)
    }

    result.value
  }

  def `import`(
      config: AppConfiguration
  )(implicit s: Scheduler): Task[Either[AppError, Unit]] = {
    val backlogInjector = BacklogInjector.createInjector(config.backlogConfig)
    val spaceService    = backlogInjector.getInstance(classOf[SpaceService])
    val jiraInjector    = Guice.createInjector(new ImportModule(config))

    val backlogUserService =
      backlogInjector.getInstance(classOf[BacklogUserService])
    val backlogPriorityService =
      backlogInjector.getInstance(classOf[BacklogPriorityService])
    val backlogStatusService =
      backlogInjector.getInstance(classOf[BacklogStatusService])
    val backlogPaths = backlogInjector.getInstance(classOf[BacklogPaths])

    val result = for {
//      _ <- checkJiraApiAccessible(config.jiraConfig).handleError
      _ <- validateConfig(
        config,
        jiraInjector.getInstance(classOf[JiraRestClient]),
        spaceService
      ).handleError
      priorityMappings <-
        PriorityMappingFileService
          .execute[JiraPriorityMappingItem, Task](
            path = MappingDirectory.default.priorityMappingFilePath,
            dstItems = backlogPriorityService.allPriorities()
          )
          .mapError(MappingError)
          .handleError
      statusMappings <-
        StatusMappingFileService
          .execute[JiraStatusMappingItem, Task](
            path = MappingDirectory.default.statusMappingFilePath,
            dstItems = backlogStatusService.allStatuses()
          )
          .mapError(MappingError)
          .handleError
      userMappings <-
        UserMappingFileService
          .execute[JiraUserMappingItem, Task](
            path = MappingDirectory.default.userMappingFilePath,
            dstItems = backlogUserService.allUsers()
          )
          .mapError(MappingError)
          .handleError
      projectKeys <- confirmProject(
        config,
        backlogInjector.getInstance(classOf[ProjectService])
      ).handleError
      _ <- showCurrentConfigs(
        keys = projectKeys,
        priorityMappings = priorityMappings,
        statusMappings = statusMappings,
        userMappings = userMappings
      ).handleError
      _ <- finalConfirm(projectKeys).handleError
    } yield {
      //        mappingFileService.usersFromJson(jiraBacklogPaths.jiraUsersJson).foreach { user =>
      //          database.add(user)
      //        } // TODO users from db

      // Convert
      val converter = new MappingConvertService(backlogPaths)

      converter.convert(
        userMaps = userMappings.map(ValidatedJiraUserMapping.from),
        priorityMaps = priorityMappings.map(ValidatedJiraPriorityMapping.from),
        statusMaps = statusMappings.map(ValidatedJiraStatusMapping.from)
      )

      // Project users mapping
      implicit val mappingUserWrites: MappingUserWrites = new MappingUserWrites
      val projectUserWriter =
        jiraInjector.getInstance(classOf[ProjectUserWriter])
      val projectUsers = userMappings.map(ValidatedJiraUserMapping.from).map(Convert.toBacklog(_))
      projectUserWriter.write(projectUsers)

      // Import
      Boot.execute(config.backlogConfig, false, config.retryCount)

      // Finalize
      if (!versionName.contains("SNAPSHOT")) {
        Finalizer.finalize(config)
      }
    }

    result.value
  }

  private def confirmProject(
      config: AppConfiguration,
      projectService: ProjectService
  ): Task[Either[AppError, ConfirmedProjectKeys]] = {
    val result =
      if (projectService.optProject(config.backlogConfig.projectKey).isDefined) {
        for {
          input <- ConsoleDSL[Task].read(
            Messages(
              "cli.backlog_project_already_exist",
              config.backlogConfig.projectKey
            )
          )
        } yield {
          if (input.toLowerCase == "y")
            Right(
              ConfirmedProjectKeys(
                config.jiraConfig.projectKey,
                config.backlogConfig.projectKey
              )
            )
          else Left(ConfirmCanceled)
        }
      } else {
        AppDSL[Task].pure(
          Right[AppError, ConfirmedProjectKeys](
            ConfirmedProjectKeys(
              config.jiraConfig.projectKey,
              config.backlogConfig.projectKey
            )
          )
        )
      }

    result.handleError.value
  }

  private def showCurrentConfigs(
      keys: ConfirmedProjectKeys,
      priorityMappings: Seq[ValidatedPriorityMapping[JiraPriorityMappingItem]],
      statusMappings: Seq[ValidatedStatusMapping[JiraStatusMappingItem]],
      userMappings: Seq[ValidatedUserMapping[JiraUserMappingItem]]
  ): Task[Either[AppError, Unit]] = {
    val userStr     = userMappings.map(item => toMappingRow(item.src.displayName, item.dst.value)).mkString("\n")
    val priorityStr = priorityMappings.map(item => toMappingRow(item.src.value, item.dst.value)).mkString("\n")
    val statusStr   = statusMappings.map(item => s"- ${item.src.display} => ${item.dst.value}").mkString("\n")

    consoleDSL
      .println(s"""
                          |${Messages(
        "cli.mapping.show",
        Messages("common.projects")
      )}
                          |--------------------------------------------------
                          |- ${keys.jiraKey} => ${keys.backlogKey}
                          |--------------------------------------------------
                          |
                          |${Messages(
        "cli.mapping.show",
        ConsoleMessages.Mappings.userItem
      )}
                          |--------------------------------------------------
                          |$userStr
                          |--------------------------------------------------
                          |
                          |${Messages(
        "cli.mapping.show",
        ConsoleMessages.Mappings.priorityItem
      )}
                          |--------------------------------------------------
                          |$priorityStr
                          |--------------------------------------------------
                          |
                          |${Messages(
        "cli.mapping.show",
        ConsoleMessages.Mappings.statusItem
      )}
                          |--------------------------------------------------
                          |$statusStr
                          |--------------------------------------------------
                          |""".stripMargin)
      .map(_ => Right(()))
  }

  private def toMappingRow(src: String, dst: String): String =
    s"- $src => $dst"

  private def finalConfirm(
      confirmedProjectKeys: ConfirmedProjectKeys
  ): Task[Either[AppError, Unit]] =
    for {
      input <- ConsoleDSL[Task].read(Messages("cli.confirm"))
      result = if (input.toLowerCase == "y") Right(()) else Left(ConfirmCanceled)
    } yield result

  private def validateConfig(
      config: AppConfiguration,
      jiraRestClient: JiraRestClient,
      spaceService: SpaceService
  ): Task[Either[AppError, Unit]] = {
    val validator = AppConfigValidator(jiraRestClient, spaceService)
    val errors    = validator.validate(config)

    if (errors.isEmpty) appDSL.pure(Right(()))
    else {
      val message =
        s"""
           |
           |${Messages("cli.param.error")}
           |--------------------------------------------------
           |${errors.mkString("\n")}
           |
        """.stripMargin
      consoleDSL.errorln(message).map(_ => Left(ParameterError(errors)))
    }
  }

  private def startExportMessage(): Task[Either[AppError, Unit]] =
    ConsoleDSL[Task]
      .println(
        s"""
         |${Messages("export.start")}
         |--------------------------------------------------""".stripMargin
      )
      .map(Right(_))
}

case class ConfirmedProjectKeys(jiraKey: String, backlogKey: String)
