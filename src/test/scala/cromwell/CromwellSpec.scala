package cromwell

import akka.actor.ActorSystem
import akka.testkit.{TestKit, ImplicitSender, DefaultTimeout}
import cromwell.binding._
import cromwell.binding.values.{WdlString, WdlValue}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

abstract class CromwellSpec(actorSystem: ActorSystem) extends TestKit(actorSystem) with DefaultTimeout
with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

}
