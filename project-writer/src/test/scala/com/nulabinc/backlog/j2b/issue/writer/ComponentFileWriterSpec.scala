package com.nulabinc.backlog.j2b.issue.writer

import com.nulabinc.backlog.migration.common.convert.BacklogUnmarshaller
import com.nulabinc.jira.client.domain.Component
import org.specs2.mutable.Specification

class ComponentFileWriterSpec extends Specification with FileWriterTestHelper {

  if (paths.issueCategoriesJson.exists) paths.issueCategoriesJson.delete()

  "should write issue categories to file" >> {

    val components = Seq[Component](
      Component(id = 10000, name = "cat1"),
      Component(id = 10001, name = "cat2")
    )

    // Output to file
    new ComponentFileWriter().write(components)

    val actual = BacklogUnmarshaller.issueCategories(paths)

    actual.length       must beEqualTo(components.length)
    actual(0).optId.get must beEqualTo(components(0).id)
    actual(0).name      must beEqualTo(components(0).name)
    actual(1).optId.get must beEqualTo(components(1).id)
    actual(1).name      must beEqualTo(components(1).name)
  }

}
