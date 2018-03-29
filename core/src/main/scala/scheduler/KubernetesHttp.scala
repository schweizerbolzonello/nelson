package nelson
package scheduler

import argonaut._
import argonaut.Argonaut._
import journal.Logger
import org.http4s.Status.NotFound
import org.http4s.client.UnexpectedStatus
import scalaz.~>
import scalaz.Scalaz._
import scalaz.concurrent.Task

import nelson.KubernetesJson.{DeploymentStatus, JobStatus}
import nelson.Datacenter.{Deployment, StackName}
import nelson.Manifest.{EnvironmentVariable, Plan, UnitDef, Versioned}
import nelson.docker.Docker.Image
import nelson.scheduler.SchedulerOp._

object KubernetesHttp {
  private val log = Logger[KubernetesHttp.type]
}

/**
 * SchedulerOp interpreter that uses the Kubernetes API server.
 *
 * See: https://kubernetes.io/docs/api-reference/v1.8/
 */
final class KubernetesHttp(client: KubernetesClient) extends (SchedulerOp ~> Task) {
  import KubernetesHttp.log

  def apply[A](fa: SchedulerOp[A]): Task[A] = fa match {
    case Delete(dc, deployment) =>
      delete(dc, deployment)
    case Launch(image, dc, ns, unit, plan, hash) =>
      launch(image, dc, ns, Versioned.unwrap(unit), unit.version, plan, hash)
    case Summary(dc, ns, stackName) =>
      summary(dc, ns, stackName)

    // TODO: Legacy, no longer used
    case RunningUnits(dc, prefix) =>
      Task.delay {
        log.debug(s"KubernetesHttp RunningUnits(${dc}, ${prefix})")
        Set.empty
      }
  }

  def delete(dc: Datacenter, deployment: Deployment): Task[Unit] = {
    val rootNs = deployment.namespace.name.root.asString
    val name = deployment.stackName.toString

    // Kubernetes has different endpoints for deployments, cron jobs, and jobs - given just
    // the name we don't know which one it is so we try each one in turn, presumably the
    // most common types first
    val deleteService =
      (client.deleteDeployment(rootNs, name) |@| client.deleteService(rootNs, name)) { case (_, _) => () }

    deleteService.handleWith {
      case UnexpectedStatus(NotFound) =>
        client.deleteCronJob(rootNs, name).map(_ => ()).handleWith {
          case UnexpectedStatus(NotFound) =>
            client.deleteJob(rootNs, name).map(_ => ()).handleWith {
              // at this point swallow 404, as we're being asked to delete something that does not exist
              // this can happen when a workflow fails and the cleanup process is subsequently called
              case UnexpectedStatus(NotFound) => Task.now(())
            }
        }
    }
  }

  def launch(image: Image, dc: Datacenter, ns: NamespaceName, unit: UnitDef, version: Version, plan: Plan, hash: String): Task[String] = {
    val stackName = StackName(unit.name, version, hash)
    val env = plan.environment.bindings ++ List(
      EnvironmentVariable("NELSON_STACKNAME",        stackName.toString),
      EnvironmentVariable("NELSON_DATACENTER",       dc.name),
      EnvironmentVariable("NELSON_ENV",              ns.root.asString),
      EnvironmentVariable("NELSON_NAMESPACE",        ns.asString),
      EnvironmentVariable("NELSON_DNS_ROOT",         dc.domain.name),
      EnvironmentVariable("NELSON_PLAN",             plan.name),
      EnvironmentVariable("NELSON_DOCKER_IMAGE",     image.toString),
      EnvironmentVariable("NELSON_MEMORY_LIMIT",     plan.environment.memory.map(_._2).getOrElse(512D).toInt.toString),
      EnvironmentVariable("NELSON_NODENAME",         s"$${node.unique.name}"),
      EnvironmentVariable("NELSON_VAULT_POLICYNAME", NomadJson.getPolicyName(ns, stackName.toString))
    )
    val newPlan = plan.copy(environment = plan.environment.copy(bindings = env))
    val schedule = Manifest.getSchedule(unit, plan)

    val rootNs = ns.root.asString
    val response = schedule match {
      case None        =>
        (client.createDeployment(rootNs, stackName, image, newPlan, unit.ports) |@| client.createService(rootNs, stackName, unit.ports)) {
          case (deployment, service) => Json("deployment" := deployment, "service" := service)
        }
      case Some(sched) =>
        sched.toCron match {
          case None           => client.createJob(rootNs, stackName, image, newPlan)
          case Some(cronExpr) => client.createCronJob(rootNs, stackName, image, newPlan, cronExpr)
        }
    }

    response.map(_.nospaces)
  }

  def summary(dc: Datacenter, ns: NamespaceName, stackName: StackName): Task[Option[DeploymentSummary]] = {
    // K8s has different endpoints for Deployment, CronJob, and Job, so we hit all of them until we find one
    deploymentSummary(dc, ns, stackName).handleWith {
      case UnexpectedStatus(NotFound) =>
        cronJobSummary(dc, ns, stackName).handleWith {
          case UnexpectedStatus(NotFound) =>
            jobSummary(dc, ns, stackName).handleWith {
              case UnexpectedStatus(NotFound) => Task.now(None)
            }
        }
    }
  }

  private def deploymentSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): Task[Option[DeploymentSummary]] = {
    val rootNs = ns.root.asString

    client.deploymentSummary(rootNs, stackName.toString).map {
      case DeploymentStatus(availableReplicas, unavailableReplicas) =>
        Some(DeploymentSummary(
          running   = availableReplicas,
          pending   = unavailableReplicas,
          completed = None,
          failed    = None
        ))
    }
  }

  private def cronJobSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): Task[Option[DeploymentSummary]] = {
    val rootNs = ns.root.asString

    client.cronJobSummary(rootNs, stackName.toString).map {
      case js@JobStatus(_, _, _) => Some(jobStatusToSummary(js))
    }
  }

  private def jobSummary(dc: Datacenter, ns: NamespaceName, stackName: StackName): Task[Option[DeploymentSummary]] = {
    val rootNs = ns.root.asString

    client.jobSummary(rootNs, stackName.toString).map {
      case js@JobStatus(_, _, _) => Some(jobStatusToSummary(js))
    }
  }

  private def jobStatusToSummary(js: JobStatus): DeploymentSummary =
    DeploymentSummary(
      running   = js.active,
      pending   = None,         // Doesn't seem like K8s API gives this info
      completed = js.succeeded,
      failed    = js.failed
    )
}
