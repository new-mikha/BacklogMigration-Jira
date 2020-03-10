package com.nulabinc.backlog.j2b.deserializers

import com.nulabinc.backlog.j2b.jira.domain.mapping._
import com.nulabinc.backlog.migration.common.domain.mappings._
import org.apache.commons.csv.CSVRecord

object JiraMappingDeserializer {
  implicit val statusDeserializer: Deserializer[CSVRecord, StatusMapping[JiraStatusMappingItem]] =
    (record: CSVRecord) => new StatusMapping[JiraStatusMappingItem] {
      override val src: JiraStatusMappingItem = JiraStatusMappingItem(record.get(0), record.get(0))
      override val optDst: Option[BacklogStatusMappingItem] = Option(record.get(1)).map(s => BacklogStatusMappingItem(s))
    }

  implicit val priorityDeserializer: Deserializer[CSVRecord, PriorityMapping[JiraPriorityMappingItem]] =
    (record: CSVRecord) => new PriorityMapping[JiraPriorityMappingItem] {
      override val src: JiraPriorityMappingItem = JiraPriorityMappingItem(record.get(0))
      override val optDst: Option[BacklogPriorityMappingItem] = Option(record.get(1)).map(p => BacklogPriorityMappingItem(p))
    }

  implicit val userDeserializer: Deserializer[CSVRecord, UserMapping[JiraUserMappingItem]] =
    (record: CSVRecord) => new UserMapping[JiraUserMappingItem] {
      override val src: JiraUserMappingItem = JiraUserMappingItem(record.get(0))
      override val optDst: Option[BacklogUserMappingItem] =
        for {
          value <- Option(record.get(1))
          mappingType <- Option(record.get(2))
          item <- mappingType match {
            case "id" => Some(BacklogUserIdMappingItem(value))
            case "mail" => Some(BacklogUserMailMappingItem(value))
            case _ => None
          }
        } yield item
    }
}
