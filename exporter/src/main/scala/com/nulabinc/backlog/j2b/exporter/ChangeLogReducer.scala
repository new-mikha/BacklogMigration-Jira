package com.nulabinc.backlog.j2b.exporter

import com.nulabinc.backlog.migration.common.conf.BacklogConstantValue
import com.nulabinc.backlog.migration.common.domain._
import com.nulabinc.backlog.migration.common.utils.{FileUtil, Logging}
import com.nulabinc.jira.client.domain.Attachment
import com.osinka.i18n.Messages

import scalax.file.Path

private [exporter] class ChangeLogReducer(issueDirPath: Path,
                                        issue: BacklogIssue,
                                        comments: Seq[BacklogComment],
                                        attachments: Seq[Attachment])
  extends Logging {

  def reduce(targetComment: BacklogComment, changeLog: BacklogChangeLog): (Option[BacklogChangeLog], String) = {
    changeLog.field match {
//      case BacklogConstantValue.ChangeLog.ATTACHMENT => (AttachmentReducer.reduce(changeLog), "")
      case "done_ratio" =>
        val message =
          Messages("common.change_comment", Messages("common.done_ratio"), getValue(changeLog.optOriginalValue), getValue(changeLog.optNewValue))
        (None, s"${message}\n")
      case "relates" =>
        val message =
          Messages("common.change_comment", Messages("common.relation"), getValue(changeLog.optOriginalValue), getValue(changeLog.optNewValue))
        (None, s"${message}\n")
      case "is_private" =>
        val message = Messages("common.change_comment",
          Messages("common.private"),
          getValue(privateValue(changeLog.optOriginalValue)),
          getValue(privateValue(changeLog.optNewValue)))
        (None, s"${message}\n")
      case BacklogConstantValue.ChangeLog.RESOLUTION =>
        val message = Messages("common.change_comment", Messages("common.resolution"), getValue(changeLog.optOriginalValue), getValue(changeLog.optNewValue))
        (None, s"${message}\n")
      case "timeestimate" =>
        val message = Messages("common.change_comment", Messages("common.timeestimate"), getValue(changeLog.optOriginalValue), getValue(changeLog.optNewValue))
        (None, s"${message}\n")
        // TODO: Check project
//      case "project_id" =>
//        val message = Messages("common.change_comment",
//          Messages("common.project"),
//          getProjectName(changeLog.optOriginalValue),
//          getProjectName(changeLog.optNewValue))
//        (None, s"${message}\n")
      case _ =>
        (Some(changeLog.copy(optNewValue = ValueReducer.reduce(targetComment, changeLog))), "")
    }
  }

  private[this] def getValue(optValue: Option[String]): String = {
    optValue.getOrElse(Messages("common.empty"))
  }

//  private[this] def getProjectName(optValue: Option[String]): String = {
//    optValue match {
//      case Some(value) =>
//        StringUtil.safeStringToInt(value) match {
//          case Some(intValue) => exportContext.projectService.optProjectOfId(intValue).map(_.getName).getOrElse(Messages("common.empty"))
//          case _              => Messages("common.empty")
//        }
//      case _ => Messages("common.empty")
//    }
//  }

  private[this] def privateValue(optValue: Option[String]): Option[String] = {
    optValue match {
      case Some("0") => Some(Messages("common.no"))
      case Some("1") => Some(Messages("common.yes"))
      case _         => None
    }
  }

  object AttachmentReducer {
    def reduce(changeLog: BacklogChangeLog): Option[BacklogChangeLog] = {
      changeLog.optAttachmentInfo match {
        case Some(attachmentInfo) =>
          val optAttachment = attachments.find(attachment => FileUtil.normalize(attachment.fileName) == attachmentInfo.name)
          optAttachment match {
            case Some(_) => Some(changeLog)
            case _       => None
          }
        case _ => Some(changeLog)
      }
    }
  }

  object ValueReducer {
    def reduce(targetComment: BacklogComment, changeLog: BacklogChangeLog): Option[String] = {
      changeLog.field match {
//        case BacklogConstantValue.ChangeLog.VERSION | BacklogConstantValue.ChangeLog.MILESTONE =>
//          findProperty(comments)(changeLog.field) match {
//            case Some(lastComment) if lastComment.optCreated == targetComment.optCreated =>
//              changeLog.field match {
//                case BacklogConstantValue.ChangeLog.VERSION =>
//                  val issueValue = issue.versionNames.mkString(", ")
//                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
//                case BacklogConstantValue.ChangeLog.MILESTONE =>
//                  val issueValue = issue.milestoneNames.mkString(", ")
//                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
//                case BacklogConstantValue.ChangeLog.ISSUE_TYPE =>
//                  val issueValue = issue.optIssueTypeName.getOrElse("")
//                  if (issueValue.trim.isEmpty) changeLog.optNewValue else Some(issueValue)
//                case _ => throw new RuntimeException
//              }
//            case _ => changeLog.optNewValue
//          }
        case _ => changeLog.optNewValue
      }
    }

    private[this] def findProperty(comments: Seq[BacklogComment])(field: String): Option[BacklogComment] = {
      comments.reverse.find(comment => findProperty(comment)(field))
    }

    private[this] def findProperty(comment: BacklogComment)(field: String): Boolean = {
      comment.changeLogs.map(findProperty).exists(_(field))
    }

    private[this] def findProperty(changeLog: BacklogChangeLog)(field: String): Boolean = {
      changeLog.field == field
    }
  }

}
