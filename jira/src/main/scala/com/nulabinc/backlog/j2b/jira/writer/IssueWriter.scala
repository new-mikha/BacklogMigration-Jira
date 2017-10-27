package com.nulabinc.backlog.j2b.jira.writer

import com.nulabinc.backlog.j2b.jira.service.IssueIOError
import com.nulabinc.backlog.migration.common.domain.BacklogIssue
import com.nulabinc.jira.client.domain.Issue

trait IssueWriter {

  def write(issues: Seq[Issue]): Either[IssueIOError, Seq[BacklogIssue]]

}
