package user.registry.entities;


import kalix.javasdk.annotations.Acl;
import kalix.javasdk.annotations.Id;
import kalix.javasdk.annotations.TypeId;
import kalix.javasdk.valueentity.ValueEntity;
import kalix.javasdk.valueentity.ValueEntityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import user.registry.Done;

@Id("id")
@TypeId("unique-address")
@RequestMapping("/unique-emails/{id}")
@Acl(allow = @Acl.Matcher(service = "*"))
public class UniqueEmailEntity extends ValueEntity<UniqueEmailEntity.UniqueEmail> {

  private final String address;
  private Logger logger = LoggerFactory.getLogger(getClass());

  public UniqueEmailEntity(ValueEntityContext context) {
    this.address = context.entityId();
  }

  public enum Status {
    NOT_USED,
    RESERVED,
    CONFIRMED
  }

  @Override
  public UniqueEmail emptyState() {
    return new UniqueEmail(address, Status.NOT_USED, null);
  }

  public record UniqueEmail(String address, Status status, String owner) {

    public UniqueEmail asConfirmed() {
      return new UniqueEmail(address, Status.CONFIRMED, owner);
    }

    public boolean isConfirmed() {
      return status == Status.CONFIRMED;
    }

    public boolean isInUse() {
      return status != Status.NOT_USED;
    }

    public boolean isUnused() {
      return status == Status.NOT_USED;
    }

  }

  public record ReserveEmail(String address, String owner) {
  }

  @PostMapping("/reserve")
  public Effect<Done> reserve(@RequestBody ReserveEmail cmd) {
    if (currentState().isInUse()) {
      return effects().error("Email already reserved");
    }

    logger.info("Reserving address: '{}'", cmd.address());
    return effects()
      .updateState(new UniqueEmail(cmd.address, Status.RESERVED, cmd.owner))
      .thenReply(Done.done());
  }

  @PostMapping("/confirm")
  public Effect<Done> confirm() {
    if (currentState().isUnused()) {
      return effects().error("Email not in use");
    }
    if (currentState().isConfirmed()) {
      logger.info("Email is already confirmed. Ignoring confirmation request.");
      return effects().error("Email already confirmed");
    }

    logger.info("Confirming address: '{}'", currentState().address);
    return effects()
      .updateState(currentState().asConfirmed())
      .thenReply(Done.done());
  }

  @DeleteMapping()
  public Effect<Done> delete() {
    logger.info("Deleting address: '{}'", currentState().address);
    return effects()
      .updateState(emptyState())
      .thenReply(Done.done());
  }


  @GetMapping
  public Effect<UniqueEmail> getState() {
    return effects().reply(currentState());
  }

}
