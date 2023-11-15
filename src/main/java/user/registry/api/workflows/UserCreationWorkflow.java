package user.registry.api.workflows;

import kalix.javasdk.annotations.Id;
import kalix.javasdk.annotations.TypeId;
import kalix.javasdk.client.ComponentClient;
import kalix.javasdk.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import user.registry.Done;
import user.registry.Result;
import user.registry.entities.UniqueEmailEntity;
import user.registry.entities.UserEntity;

import java.util.Optional;

import static kalix.javasdk.workflow.Workflow.RecoverStrategy.maxRetries;

@TypeId("user-creation-workflow")
@RequestMapping("/api")
@Id("userId")
public class UserCreationWorkflow extends Workflow<UserCreationWorkflow.State> {

  private final Logger logger = LoggerFactory.getLogger(UserCreationWorkflow.class);
  private final ComponentClient componentClient;

  public UserCreationWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public enum Status {
    RESERVING_EMAIL,
    CREATING_USER,
    CONFIRMING_EMAIL,
    FINISHED,
    PAUSED,
    FAILED;
  }

  public record State(String userId,
                      UserEntity.Create createCmd,
                      Status status,
                      Optional<String> errorMessage) {

    public State withStatus(Status status) {
      return new State(userId, createCmd, status, errorMessage);
    }

    public State withErrorMessage(String errorMessage) {
      return new State(userId, createCmd, status, Optional.of(errorMessage));
    }

  }

  @GetMapping("/users/{userId}/creation-workflow")
  public Effect<State> getState() {
    return effects().reply(currentState());
  }

  @PostMapping("/users/{userId}")
  public Effect<State> start(@PathVariable String userId, @RequestBody UserEntity.Create cmd) {
    if (currentState() == null) {
    logger.info("Starting workflow (id:{})", commandContext().workflowId());

    var state = new State(userId, cmd, Status.RESERVING_EMAIL, Optional.empty());

    return effects()
      .updateState(state)
      .transitionTo("reserve-email", new UniqueEmailEntity.ReserveEmail(cmd.email(), userId))
      .thenReply(state);
    } else {
      logger.info("Workflow already started (id:{})", commandContext().workflowId());
      return effects().reply(currentState());
    }
  }

  @Override
  public WorkflowDef<State> definition() {

    //---------------------------------------------------------------------------------------------
    var confirmEmailStepName = "confirm-email";
    var confirmEmail =
      step(confirmEmailStepName)
        .call(
          () -> {
            logger.info("step[{}]: confirming address: '{}'", confirmEmailStepName, currentState().createCmd().email());
            return componentClient
              .forValueEntity(currentState().createCmd.email())
              .call(UniqueEmailEntity::confirm);
          }
        )
        // when this step finishes, the workflow is done
        .andThen(
          Done.class,
          __ ->
            effects()
              .updateState(currentState().withStatus(Status.FINISHED))
              .end());

    //---------------------------------------------------------------------------------------------
    var createUserStepName = "create-user";
    var createUser =
      step(createUserStepName)
        .asyncCall(
          UserEntity.Create.class,
          cmd -> {
            logger.info("step[{}]: creating user: {}", createUserStepName, cmd);
            return componentClient
              .forEventSourcedEntity(currentState().userId)
              .call(UserEntity::createUser).params(cmd).execute();
          }
        )
        // when done, move to email confirmation
        .andThen(
          Done.class,
          __ -> effects()
            .updateState(currentState().withStatus(Status.CONFIRMING_EMAIL))
            .transitionTo(confirmEmail.name()));


    //---------------------------------------------------------------------------------------------
    var reserveEmailStepName = "reserve-email";
    var reserveEmail =
      step(reserveEmailStepName)
        .asyncCall(
          UniqueEmailEntity.ReserveEmail.class,
          cmd -> {
            logger.info("step[{}]: Reserving address '{}'", reserveEmailStepName, cmd.address());
            return componentClient
              .forValueEntity(cmd.address())
              .call(UniqueEmailEntity::reserve).params(cmd).execute()
              .thenApply(__ -> Result.success())
              .exceptionally(__ -> Result.failure("failed to reserve email: '" + cmd.address() + "'"));
          }
        )
        // once email reserved, create the user
        .andThen(
          Result.class,
          result -> {
            if (result instanceof Result.Failure failure) {
              logger.error("step[{}]: failed to reserve email: '{}'", reserveEmailStepName, currentState().createCmd().email());
              return effects()
                .updateState(
                  currentState()
                    .withStatus(Status.PAUSED)
                    .withErrorMessage(failure.message()))
                .pause();
            } else {
              logger.info("step[{}]: email '{}' reserved", reserveEmailStepName, currentState().createCmd().email());
              return effects()
                .updateState(currentState().withStatus(Status.CREATING_USER))
                .transitionTo(createUser.name(), currentState().createCmd());
            }
          });

    //---------------------------------------------------------------------------------------------
    // this is a failover step, it will be executed if we fail to create the user
    var unReserveEmailStepName = "un-reserve-email";
    var unReserveEmail =
      step(unReserveEmailStepName)
        .call(
          () -> {
            logger.info("step[{}]: deleting email reservation: '{}'", unReserveEmailStepName, currentState().createCmd().email());
            return componentClient
              .forValueEntity(currentState().createCmd().email())
              .call(UniqueEmailEntity::unReserve);
          }
        )
        // once email reservation is deleted, we can stop the workflow
        .andThen(Done.class, __ ->
          effects()
            .updateState(currentState()
              .withErrorMessage("failed to create user")
              .withStatus(Status.FAILED))
            .end());


    return workflow()
      .addStep(reserveEmail)
      .addStep(unReserveEmail)
      .addStep(createUser, maxRetries(3).failoverTo(unReserveEmail.name()))
      .addStep(confirmEmail);
  }


}
