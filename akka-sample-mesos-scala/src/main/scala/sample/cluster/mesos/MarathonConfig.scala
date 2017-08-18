package sample.cluster.mesos

import akka.actor.Address
import java.io.File
import java.net.URL
import com.typesafe.config._
import collection.JavaConverters._

/**
  * This object discovers seed nodes for the Akka Cluster using Marathon API
  */
object MarathonConfig {

  val config: Config = ConfigFactory.load()

  /**
    * Env var that contains the Host's IP Address
    */
  val HOST_IP_ENV_VAR = "LIBPROCESS_IP"

  /**
    * Env var that contains the Host's PORT bound to the container port
    */
  val HOST_PORT_ENV_VAR = "PORT_"

  /**
    * Docker's internal port used for the cluster.
    */
  val bindPort: String = config.getString("akka.remote.netty.tcp.bind-port")

  lazy val hostExternalIP: String = discoverHostIP()
  lazy val hostExternalPort: String = discoverHostPort()

  /**
    * Use Marathon API to discover other running tasks for this app.
    *
    * A task  may come as:
    *
      * {
          id: "akka-cluster.086db21b-7192-11e7-8203-0242ac107905",
          slaveId: "35f9af86-f5b0-4e95-a2b1-5f201b10fbaa-S0",
          host: "localhost",
          state: "TASK_RUNNING",
          startedAt: "2017-07-25T23:36:09.629Z",
          stagedAt: "2017-07-25T23:36:07.949Z",
          ports: [
            11696
          ],
          version: "2017-07-25T23:36:07.294Z",
          ipAddresses: [
            {
              ipAddress: "172.17.0.2",
              protocol: "IPv4"
            }
          ],
          appId: "/akka-cluster"
      }
    * This method extracts the host and the port of each task
    *
    * @return an array of strings with akka.tcp://{cluster-name}@{IP}:{PORT}
    */
  def getSeedNodes(): Seq[Address] = {
    val url: String = config.getString("akka.cluster.discovery.url")
    val portIndex: Int = config.getInt("akka.cluster.discovery.port-index")
    val clusterName: String = config.getString("akka.cluster.name")

    var tmpCfg : Config = null

    if (url.startsWith("http")) {
      tmpCfg = ConfigFactory
        .parseURL(new URL(url),
                    ConfigParseOptions.defaults().setSyntax(ConfigSyntax.JSON))
        .resolve()
    } else {
      tmpCfg = ConfigFactory
        .parseFileAnySyntax(new File(url))
        .resolve()
    }
    var notHealthyTasks = unhealthyTasks(tmpCfg)

    while (notHealthyTasks.size != 0) {
        val unhealthyTaskIds = notHealthyTasks.map(t => t.getString("id"))
        System.out.println(s"found ${notHealthyTasks.size} unhealthy tasks (${unhealthyTaskIds}), will try again in 5s")
        System.out.println()
        Thread.sleep(5000)
        if (url.startsWith("http")) {
            tmpCfg = ConfigFactory
                    .parseURL(new URL(url),
                        ConfigParseOptions.defaults().setSyntax(ConfigSyntax.JSON))
                    .resolve()
        } else {
            tmpCfg = ConfigFactory
                    .parseFileAnySyntax(new File(url))
                    .resolve()
        }
        notHealthyTasks = unhealthyTasks(tmpCfg)
    }

    System.out.println(s"can start cluster now that all ${tmpCfg.getConfigList("tasks").size()} tasks are healthy")

    var seq: Seq[Address] = Seq()
    tmpCfg.getConfigList("tasks").forEach(
      (item: Config) =>
        //seq = seq :+ ("akka.tcp://%s@%s:%s" format(clusterName, item.getString("host"), item.getIntList("ports").get(portIndex).toString)))
          seq = seq :+ Address("akka.tcp", clusterName, item.getString("host"), item.getIntList("ports").get(portIndex)))
      //for testing, case the first task to commit suicide
//    if (tmpCfg.getConfigList("tasks").get(0).getString("id") == System.getenv("MESOS_TASK_ID")){
//        System.out.println("THIS IS THE FIRST MARATHON TASK, COMMITTING SUICIDE")
//        Thread.sleep(5000)
//        System.exit(129)
//    }

    seq
  }
  private def unhealthyTasks(tmpCfg:Config) = tmpCfg.getConfigList("tasks").asScala.filter( t => t.hasPath("healthCheckResults") == false || t.getConfigList("healthCheckResults").get(0).getBoolean("alive") == false)

  /**
    * Returns the private IP address associated with the docker container
    * I.e. 172.17.0.7
    * @return IP Address as String
    */
  def getDockerPrivateAddress: String = {
    import java.net.NetworkInterface

    import scala.collection.JavaConversions._

    NetworkInterface.getNetworkInterfaces
      .find(_.getName equals "eth0")
      .flatMap { interface =>
        interface.getInetAddresses.find(_.isSiteLocalAddress).map(_.getHostAddress)
      }
      .getOrElse("127.0.0.1")
  }

  def discoverAkkaConfig(): Config = {

//    val seedNodes = getSeedNodes().map { address =>
//      s"""akka.cluster.seed-nodes += "$address""""
//    }.mkString("\n")
    val seedNodes = ""

    val privateDockerContainerAddress: String = getDockerPrivateAddress

    ConfigFactory.parseString(seedNodes)
      .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(hostExternalIP))
      .withValue("akka.remote.netty.tcp.bind-hostname", ConfigValueFactory.fromAnyRef(privateDockerContainerAddress))
      .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(hostExternalPort))
      .withFallback(config)
      .resolve()
  }

  private def discoverHostIP(): String = {
    sys.env(HOST_IP_ENV_VAR)
  }

  private def discoverHostPort(): String = {
    sys.env(HOST_PORT_ENV_VAR + bindPort)
  }

}