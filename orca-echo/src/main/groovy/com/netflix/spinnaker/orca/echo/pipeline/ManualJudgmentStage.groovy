/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.echo.pipeline

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.google.common.collect.ImmutableList

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.base.Strings
import com.netflix.spinnaker.fiat.shared.FiatStatus

import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.api.pipeline.OverridableTimeoutRetryableTask
import com.netflix.spinnaker.orca.api.pipeline.models.PipelineExecution
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.api.pipeline.TaskResult
import com.netflix.spinnaker.orca.echo.util.ManualJudgmentAuthorization

import javax.annotation.Nonnull
import java.util.concurrent.TimeUnit
import com.google.common.annotations.VisibleForTesting
import com.netflix.spinnaker.orca.*
import com.netflix.spinnaker.orca.echo.EchoService
import com.netflix.spinnaker.orca.api.pipeline.graph.StageDefinitionBuilder
import com.netflix.spinnaker.orca.api.pipeline.graph.TaskNode
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import static com.netflix.spinnaker.orca.echo.EchoService.Notification.InteractiveActions

@Component
class ManualJudgmentStage implements StageDefinitionBuilder, AuthenticatedStage {

  @Override
  void taskGraph(@Nonnull StageExecution stage, @Nonnull TaskNode.Builder builder) {
    builder
        .withTask("waitForJudgment", WaitForManualJudgmentTask.class)
  }

  @Override
  void prepareStageForRestart(@Nonnull StageExecution stage) {
    stage.context.remove("judgmentStatus")
    stage.context.remove("lastModifiedBy")
  }

  @Override
  Optional<PipelineExecution.AuthenticationDetails> authenticatedUser(StageExecution stage) {
    def stageData = stage.mapTo(StageData)
    if (stageData.state != StageData.State.CONTINUE || !stage.lastModified?.user || !stageData.propagateAuthenticationContext) {
      return Optional.empty()
    }

    return Optional.of(
        new PipelineExecution.AuthenticationDetails(
            stage.lastModified.user,
            stage.lastModified.allowedAccounts))
  }

  @Slf4j
  @Component
  @VisibleForTesting
  static class WaitForManualJudgmentTask implements OverridableTimeoutRetryableTask {
    final long backoffPeriod = 15000
    final long timeout = TimeUnit.DAYS.toMillis(3)

    private final EchoService echoService
    private final ManualJudgmentAuthorization manualJudgmentAuthorization

    @Autowired
    WaitForManualJudgmentTask(Optional<EchoService> echoService,
                              ManualJudgmentAuthorization manualJudgmentAuthorization) {
      this.echoService = echoService.orElse(null)
      this.manualJudgmentAuthorization = manualJudgmentAuthorization
    }

    @Override
    TaskResult execute(StageExecution stage) {
      StageData stageData = stage.mapTo(StageData)
      String notificationState
      ExecutionStatus executionStatus

      switch (stageData.state) {
        case StageData.State.CONTINUE:
          notificationState = "manualJudgmentContinue"
          executionStatus = ExecutionStatus.SUCCEEDED
          break
        case StageData.State.STOP:
          notificationState = "manualJudgmentStop"
          executionStatus = ExecutionStatus.TERMINAL
          break
        default:
          notificationState = "manualJudgment"
          executionStatus = ExecutionStatus.RUNNING
          break
      }

      if (stageData.state != StageData.State.UNKNOWN && !stageData.getRequiredJudgmentRoles().isEmpty()) {
        // only check authorization _if_ a judgment has been made and required judgment roles have been specified
        def currentUser = stage.lastModified?.user

        if (!manualJudgmentAuthorization.isAuthorized(stageData.getRequiredJudgmentRoles(), currentUser)) {
          notificationState = "manualJudgment"
          executionStatus = ExecutionStatus.RUNNING
          stage.context.put("judgmentStatus", "")
        }
      }

      Map outputs = processNotifications(stage, stageData, notificationState)

      return TaskResult.builder(executionStatus).context(outputs).build()
    }

    Map processNotifications(StageExecution stage, StageData stageData, String notificationState) {
      if (echoService) {
        // sendNotifications will be true if using the new scheme for configuration notifications.
        // The new scheme matches the scheme used by the other stages.
        // If the deprecated scheme is in use, only the original 'awaiting judgment' notification is supported.
        if (notificationState != "manualJudgment" && !stage.context.sendNotifications) {
          return [:]
        }

        stageData.notifications.findAll { it.shouldNotify(notificationState) }.each {
          try {
            it.notify(echoService, stage, notificationState)
          } catch (Exception e) {
            log.error("Unable to send notification (executionId: ${stage.execution.id}, address: ${it.address}, type: ${it.type})", e)
          }
        }

        return [notifications: stageData.notifications]
      } else {
        return [:]
      }
    }
  }

  static class StageData {
    String judgmentStatus = ""
    List<Notification> notifications = []
    Set<String> selectedStageRoles = []
    Set<String> requiredJudgmentRoles = []
    boolean propagateAuthenticationContext

    Set<String> getRequiredJudgmentRoles() {
      // UI is currently configuring 'selectedStageRoles' so this will fallback to that if not otherwise specified
      return requiredJudgmentRoles ?: selectedStageRoles ?: []
    }

    State getState() {
      switch (judgmentStatus?.toLowerCase()) {
        case "continue":
          return State.CONTINUE
        case "stop":
          return State.STOP
        default:
          return State.UNKNOWN
      }
    }

    enum State {
      CONTINUE,
      STOP,
      UNKNOWN
    }
  }

  static class Notification {
    String address
    String cc
    String type
    String publisherName
    List<String> when
    Map<String, Map> message

    Map<String, Date> lastNotifiedByNotificationState = [:]
    Long notifyEveryMs = -1

    @JsonIgnore
    Map<String, Object> other = new HashMap<>()

    @JsonAnyGetter
    Map<String, Object> other() {
      return other
    }

    @JsonAnySetter
    void setOther(String name, Object value) {
      other.put(name, value)
    }

    boolean shouldNotify(String notificationState, Date now = new Date()) {
      // The new scheme for configuring notifications requires the use of the when list (just like the other stages).
      // If this list is present, but does not contain an entry for this particular notification state, do not notify.
      if (when && !when.contains(notificationState)) {
        return false
      }

      Date lastNotified = lastNotifiedByNotificationState[notificationState]

      if (!lastNotified?.time) {
        return true
      }

      if (notifyEveryMs <= 0) {
        return false
      }

      return new Date(lastNotified.time + notifyEveryMs) <= now
    }

    void notify(EchoService echoService, StageExecution stage, String notificationState) {
      boolean useInteractiveBot = ("manualJudgment".equalsIgnoreCase(notificationState))
      echoService.create(new EchoService.Notification(
          notificationType: EchoService.Notification.Type.valueOf(type.toUpperCase()),
          to: address ? [address] : (publisherName ? [publisherName] : null),
          cc: cc ? [cc] : null,
          templateGroup: notificationState,
          severity: EchoService.Notification.Severity.HIGH,
          source: new EchoService.Notification.Source(
              executionType: stage.execution.type.toString(),
              executionId: stage.execution.id,
              application: stage.execution.application
          ),
          additionalContext: [
              stageName                        : stage.name,
              stageId                          : stage.refId,
              restrictExecutionDuringTimeWindow: stage.context.restrictExecutionDuringTimeWindow,
              execution                        : stage.execution,
              instructions                     : stage.context.instructions ?: "",
              message                          : message?.get(notificationState)?.text,
              judgmentInputs                   : stage.context.judgmentInputs,
              judgmentInput                    : stage.context.judgmentInput,
              judgedBy                         : stage.context.lastModifiedBy
          ],
          useInteractiveBot: useInteractiveBot,
          interactiveActions: useInteractiveBot ? getInteractiveActions(stage) : null
      ))
      lastNotifiedByNotificationState[notificationState] = new Date()
    }

    private static InteractiveActions getInteractiveActions(StageExecution stage) {
      new InteractiveActions(
          callbackServiceId: "orca",
          callbackMessageId: "${stage.getExecution().getType()}-${stage.getExecution().getId()}-${stage.getId()}",
          color: '#fcba03',
          actions: ImmutableList.of(
              new EchoService.Notification.ButtonAction(
                  name: "manual-judgement",
                  label: "Approve",
                  value: StageData.State.CONTINUE.name()
              ),
              new EchoService.Notification.ButtonAction(
                  name: "manual-judgement",
                  label: "Reject",
                  value: StageData.State.STOP.name()
              )
          )
      )
    }
  }
}
