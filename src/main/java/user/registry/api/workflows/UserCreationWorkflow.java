package user.registry.api.workflows;

import kalix.javasdk.annotations.GenerateId;
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

  public record State(String userId,
                      UserEntity.Create createCmd,
                      boolean userCreated,
                      boolean emailReserved,
                      Optional<String> errorMessage) {

    public State emailIsReserved() {
      return new State(userId, createCmd, userCreated, true, errorMessage);
    }

    public State userIsCreated() {
      return new State(userId, createCmd, true, emailReserved, errorMessage);
    }

    public State withErrorMessage(String errorMessage) {
      return new State(userId, createCmd, userCreated, emailReserved, Optional.of(errorMessage));
    }
  }

  @GetMapping("/users/{userId}/creation-workflow")
  public Effect<State> getState() {
    return effects().reply(currentState());
  }

  @PostMapping("/users/{userId}")
  public Effect<State> start(@PathVariable String userId, @RequestBody UserEntity.Create cmd) {
    logger.info("Starting workflow (id:{})", commandContext().workflowId());

    var state = new State(userId, cmd, false, false, Optional.empty());

    return effects()
      .updateState(state)
      .transitionTo("reserve-email", new UniqueEmailEntity.ReserveEmail(cmd.email(), userId))
      .thenReply(state);
  }


  @Override
  public WorkflowDef<State> definition() {

    //---------------------------------------------------------------------------------------------
    var confirmEmailStepName = "confirm-email";
    var confirmEmail =
      step(confirmEmailStepName)
        .call(() -> {
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
              .updateState(currentState().emailIsReserved())
              .end());


    //---------------------------------------------------------------------------------------------
    var createUserStepName = "create-user";
    var createUser =
      step(createUserStepName)
        .call(
          UserEntity.Create.class,
          cmd -> {
            logger.info("step[{}]: creating user: {}", createUserStepName, cmd);
            return componentClient
              .forEventSourcedEntity(currentState().userId)
              .call(UserEntity::createUser).params(cmd);
          }
        )
        // when done, move to email confirmation
        .andThen(
          Done.class,
          __ -> effects()
            .updateState(currentState().userIsCreated())
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
                .updateState(currentState().withErrorMessage(failure.message()))
                .pause();
            } else {
              logger.info("step[{}]: email '{}' reserved", reserveEmailStepName, currentState().createCmd().email());
              return effects()
                .updateState(currentState().emailIsReserved())
                .transitionTo(createUser.name(), currentState().createCmd());
            }
          });

    //---------------------------------------------------------------------------------------------
    // this is a failover step, it will be executed if we fail to create the user
    var deleteEmailReservationStepName = "delete-reservation-email";
    var deleteReservation =
      step(deleteEmailReservationStepName)
        .call(
          () -> {
            logger.info("step[{}]: deleting email reservation: '{}'", deleteEmailReservationStepName, currentState().createCmd().email());
            return componentClient
              .forValueEntity(currentState().createCmd().email())
              .call(UniqueEmailEntity::delete);
          }
        )
        // once email reservation is deleted, we can stop the workflow
        .andThen(Done.class, __ -> effects().end());


    return workflow()
      .addStep(reserveEmail)
      .addStep(deleteReservation)
      .addStep(createUser, maxRetries(3).failoverTo(deleteReservation.name()))
      .addStep(confirmEmail);
  }


}
