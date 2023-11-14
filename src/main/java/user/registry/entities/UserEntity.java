package user.registry.entities;


import io.vavr.collection.List;
import io.vavr.control.Either;
import kalix.javasdk.StatusCode;
import kalix.javasdk.annotations.*;
import kalix.javasdk.eventsourcedentity.EventSourcedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import user.registry.Done;

@Id("id")
@TypeId("user")
@RequestMapping("/users/{id}")
@Acl(allow = @Acl.Matcher(service = "*"))
public class UserEntity extends EventSourcedEntity<UserEntity.User, UserEntity.Event> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  // commands
  public record Create(String name, String country, String email) {}
  public record ChangeEmail(String newEmail) {}

  // events
  public sealed interface Event {}
  @TypeName("user-created")
  public record UserWasCreated(String name, String country, String email) implements Event {}
  @TypeName("email-assigned")
  public record EmailAssigned( String newEmail) implements Event {}
  @TypeName("email-unassigned")
  public record EmailUnassigned(String oldEmail) implements Event {}

  public record User(String name, String country, String email) {

    static public List<Event> onCommand(Create cmd) {
      return List.of(
        new UserWasCreated(cmd.name(), cmd.country(), cmd.email()),
        new EmailAssigned(cmd.email)
      );
    }

    static public User onEvent(UserWasCreated evt) {
      return new User(evt.name, evt.country, evt.email);
    }

    public Either<String, List<Event>> onCommand(ChangeEmail cmd) {
      if(cmd.newEmail().equals(email))
        return Either.left("Email is the same as the current one");
      else
        return Either.right(List.of(
          new EmailAssigned(cmd.newEmail()),
          new EmailUnassigned(email)
        ));
    }

    public User onEvent(EmailAssigned evt) {
      return new User(name, country,  evt.newEmail());
    }
  }


  @PostMapping
  public Effect<Done> createUser(@RequestBody Create cmd) {

    if (cmd.name() == null) {
      return effects().error("Name is empty", StatusCode.ErrorCode.BAD_REQUEST);
    }

    logger.info("Creating user {}", cmd);
    if (currentState() != null) {
      return effects().error("User already created", StatusCode.ErrorCode.BAD_REQUEST);
    }
    return effects()
      .emitEvents(User.onCommand(cmd).asJava())
      .thenReply(__ -> Done.done());
  }

  @PutMapping("/change-email")
  public Effect<Done> changeEmail(@RequestBody ChangeEmail cmd) {
    if (currentState() == null) {
      return effects().error("User not found", StatusCode.ErrorCode.NOT_FOUND);
    }
    return currentState().onCommand(cmd)
      .fold(
        error -> effects().error(error),
        event -> effects().emitEvents(event.asJava()).thenReply(__ -> Done.done())
      );
  }


  @GetMapping
  public Effect<User> getState() {
    if (currentState() == null) {
      return effects().error("User not found", StatusCode.ErrorCode.NOT_FOUND);
    }
    return effects().reply(currentState());
  }

  @EventHandler
  public User onEvent(UserWasCreated evt) {
    return User.onEvent(evt);
  }

  @EventHandler
  public User onEvent(EmailAssigned evt) {
    return currentState().onEvent(evt);
  }


  @EventHandler
  public User onEvent(EmailUnassigned evt) {
    return currentState();
  }

}
