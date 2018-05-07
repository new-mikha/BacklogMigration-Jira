package com.nulabinc.backlog.j2b.exporter.service

import better.files.{File => Path}
import javax.inject.Inject
import com.nulabinc.backlog.j2b.jira.conf.JiraApiConfiguration
import com.nulabinc.backlog.j2b.jira.domain.JiraProjectKey
import com.nulabinc.backlog.j2b.jira.service.IssueService
import com.nulabinc.backlog.migration.common.conf.BacklogPaths
import com.nulabinc.backlog.migration.common.utils.Logging
import com.nulabinc.jira.client.domain.changeLog.ChangeLog
import com.nulabinc.jira.client.{DownloadResult, JiraRestClient}
import com.nulabinc.jira.client.domain.issue.Issue

class JiraClientIssueService @Inject()(apiConfig: JiraApiConfiguration,
                                       projectKey: JiraProjectKey,
                                       jira: JiraRestClient,
                                       backlogPaths: BacklogPaths)
    extends IssueService with Logging {

  override def count(): Long = {
    jira.searchAPI.searchJql(s"project=${projectKey.value}", 0, 0) match {
      case Right(result) => result.total
      case Left(error) => {
        logger.error(error.message)
        0
      }
    }
  }

  override def issues(startAt: Long, maxResults: Long): Seq[Issue] =
    jira.issueAPI.projectIssues(projectKey.value, startAt, maxResults) match {
      case Right(result) => result
      case Left(error) => {
        logger.error(error.message)
        Seq.empty[Issue]
      }
    }

  override def changeLogs(issue: Issue): Seq[ChangeLog] = {

    def fetch(issue: Issue, startAt: Long, maxResults: Long, changeLogs: Seq[ChangeLog]): Seq[ChangeLog] =
      jira.issueAPI.changeLogs(issue.id.toString, startAt, maxResults) match {
        case Right(result) =>
          val appendedChangeLogs = changeLogs ++ result.values
          if (result.hasPage) fetch(issue, startAt + maxResults, maxResults, appendedChangeLogs)
          else appendedChangeLogs
        case Left(error) =>
          throw new RuntimeException(s"Cannot get issue change logs: ${error.message}")
      }

    fetch(issue, 0, 100, Seq.empty[ChangeLog])
  }

  override def downloadAttachments(attachmentId: Long, saveDirectory: Path, fileName: String): DownloadResult = {
    // content = https://(workspace name).atlassian.net/secure/attachment/(attachment ID)/(file name)
    jira.httpClient.download(jira.url + s"/secure/attachment/$attachmentId/$fileName", saveDirectory.toString)
  }

}
