package com.nulabinc.backlog.j2b.jira.domain

import com.nulabinc.jira.client.domain.{Status, User}

case class CollectData(users: Set[User], statuses: Set[Status])
