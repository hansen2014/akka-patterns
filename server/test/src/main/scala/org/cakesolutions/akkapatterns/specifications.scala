package org.cakesolutions.akkapatterns

import domain._
import org.cassandraunit.DataLoader
import org.cassandraunit.dataset.yaml.ClassPathYamlDataSet
import com.mongodb.DB
import me.prettyprint.hector.api.Cluster
import org.specs2.mutable.Specification
import collection.JavaConversions._
import org.specs2.specification.{Step, Fragments}
import akka.contrib.jul.JavaLogging
import org.specs2.control.StackTraceFilter
import akka.testkit.TestKit
import akka.actor.ActorSystem
import akka.util.Timeout


/** Convenient parent for all Specs, ensuring that exceptions are (mostly) correctly
  * logged. This is necessary because Specs2 tries to do its own exception logging
  * and ends up duplicating a lot of functionality already provided by J2SE.
  *
  * Provides access to a 'log' field.
  *
  * NOTE: don't forget to add `sequential` if your specs depend on ordering.
  */
abstract class NoActorSpecs extends Specification with JavaLogging {
  // change Specification to SpecificationWithJUnit for JUnit integration (not needed with SBT anymore)

  args.report(traceFilter = LoggedStackTraceFilter)
}

/** Convenient parent for Specs that test an Actor: logging is enabled, timeouts are set, specs
  * run sequentially and the actor system is closed down after all specs have run.
  *
  * The logging backend helps to catch a lot of root causes, as typically a failed actor
  * spec will result in a timeout in the spec plus a hidden-away Akka log revealing the true
  * exception.
  */
//@RunWith(classOf[JUnitRunner])
abstract class ActorSpecs extends TestKit(ActorSystem()) with Specification with JavaLogging {

  args.report(traceFilter = LoggedStackTraceFilter)

  sequential

  implicit def self = testActor

  implicit val timeout = Timeout(10000)

  // https://groups.google.com/d/topic/specs2-users/PdCeX4zxc0A/discussion
  override def map(fs: => Fragments) = super.map(fs) ^ Step(system.shutdown())
}


/** Convenient mixin that provides access to a cleanly prepared (before any spec is run) MongoDB.
  */
trait TestMongo extends Configuration with Configured with NoSqlConfig with Resources {
  this: Specification with JavaLogging =>

  // https://groups.google.com/d/topic/specs2-users/PdCeX4zxc0A/discussion
  override def map(fs: => Fragments) = Step(resetMongo()) ^ fs

  configure(mongo(Settings.test.db.mongo))

  def resetMongo() {
    val mongo = configured[DB]
    log.info(s"resetting ${mongo.getName}")
    mongo.eval(readResource("classpath:org/cakesolutions/akkapatterns/test/mongodb-base.js"))
  }
}


/** Convenient mixin that provides access to a cleanly prepared (before any spec is run) Cassandra.
  */
trait TestCassandra extends SpecificationStructure with Configuration with Configured with NoSqlConfig with Resources {
  this: Specification with JavaLogging =>

  // https://groups.google.com/d/topic/specs2-users/PdCeX4zxc0A/discussion
  override def map(fs: => Fragments) = super.map(fs) ^ Step(resetCassandra())

  configure(cassandra(Settings.test.db.cassandra))

  def resetCassandra() {
    log.info("resetting cassandra")
    val cassandraBase = new ClassPathYamlDataSet("org/cakesolutions/akkapatterns/test/cassandra-base.yaml")
    val cluster = configured[Cluster]
    val name = cluster.describeClusterName()
    val host = cluster.getKnownPoolHosts(false).head.getHost
    new DataLoader(name, host).load(cassandraBase)
  }
}

// from com.github.fommil.scala-logging
object LoggedStackTraceFilter extends StackTraceFilter with JavaLogging {
  def apply(e: Seq[StackTraceElement]) = Nil

  override def apply[T <: Exception](e: T): T = {
    log.error(e, "Specs2")
    // this only works because log.error will construct the LogRecord instantly
    e.setStackTrace(new Array[StackTraceElement](0))
    e
  }
}