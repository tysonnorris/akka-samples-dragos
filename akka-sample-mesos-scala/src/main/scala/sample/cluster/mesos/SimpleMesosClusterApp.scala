package sample.cluster.mesos


import akka.actor.Address
import akka.actor.{ActorSystem, Props}
import akka.cluster.Cluster
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory


object SimpleMesosClusterApp {
  def main(args: Array[String]): Unit = {
    val config: Config = ConfigFactory.load()

    val current = System.currentTimeMillis()
    val waitTime = 20000 - ( current % 10000)
    System.out.println("current "+ current)
    System.out.println("wait " + waitTime)


    System.out.println("will start at "+ (current + waitTime))
    Thread.sleep(waitTime)
    System.out.println("starting actor system at " + System.currentTimeMillis())

    // Create an Akka system
    val system = ActorSystem(config.getString("akka.cluster.name"))

    // Create an actor that handles cluster domain events
    system.actorOf(Props[SimpleClusterListener], name = "clusterListener")

    val seedNodes = MarathonConfig.getSeedNodes(config)
    System.out.println(s"joining cluster with seed nodes ${seedNodes}")
    Cluster(system).joinSeedNodes(seedNodes.toList)

  }

}
